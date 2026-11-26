package pl.sekurak.alarm

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL

/** Webhook do HA po wyciszeniu alarmu: wysyła listę wyciszonych typów, HA wyłącza ich przełączniki. */
object Webhook {
    /** Stary webhook (automatyzacja YAML / integracja) – zawsze próbowany na końcu. */
    const val LEGACY_URL = "https://pusiloft.ovh/api/webhook/sekurak_wycisz_pudaul75"
    private const val CH_INFO = "sekurak_info"
    private const val NOTIF_FAIL = 2

    private fun body(types: List<AlarmType>) =
        """{"zrodlo":"sekurak","typy":[${types.joinToString(",") { "\"${it.key}\"" }}]}"""

    /** Wynik: adres, który zadziałał, czy odpowiedziała integracja Sekurak, i log prób. */
    data class Ok(val url: String, val confirmed: Boolean, val log: List<String>)

    class Failed(val log: List<String>) : Exception(log.joinToString("\n"))

    /** Dekoduje adres z intent_extras (może przyjść jako %-zakodowany). */
    fun decode(v: String?): String {
        val s = v?.trim().orEmpty()
        return if (s.contains("%3A", ignoreCase = true)) java.net.URLDecoder.decode(s, "UTF-8") else s
    }

    /** Jedno wywołanie jednego adresu. Zwraca kod HTTP i czy odpowiedziała integracja Sekurak. */
    private fun callOne(url: String, types: List<AlarmType>): Triple<Int, Boolean, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write(body(types).toByteArray()) }
            val code = c.responseCode
            val resp = if (code in 200..299)
                runCatching { c.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            else ""
            // kto odpowiedział – przy 404 widać, czy to proxy (nginx, Cloudflare…), a nie HA
            val server = c.getHeaderField("Server")?.takeIf { it.isNotBlank() } ?: "?"
            return Triple(code, resp.contains("sekurak"), server)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Próbuje kolejnych adresów. Sukces = integracja potwierdziła; jeśli żaden nie
     * potwierdził, ale któryś dał 2xx (np. stara automatyzacja YAML), też sukces.
     * Wołać poza wątkiem UI. Pusta lista typów = test (HA nic nie wyłącza).
     */
    fun call(urls: List<String>, types: List<AlarmType>): Result<Ok> {
        val log = mutableListOf<String>()
        if (urls.isEmpty()) {
            log += "Brak adresu – poczekaj, aż HA go przyśle (restart HA albo pierwszy alarm), albo wpisz ręcznie"
            return Result.failure(Failed(log))
        }
        // Wyciszenie idzie na WSZYSTKIE adresy (integracja + stary YAML), nie tylko pierwszy.
        var confirmed: String? = null
        var unconfirmed: String? = null
        for (u in urls) {
            try {
                val (code, ok, server) = callOne(u, types)
                when {
                    ok -> { log += "✅ $u → $code Sekurak"; if (confirmed == null) confirmed = u }
                    code in 200..299 -> { log += "⚠️ $u → $code (to nie integracja Sekurak, odpowiedział: $server)"; if (unconfirmed == null) unconfirmed = u }
                    else -> log += "❌ $u → HTTP $code (odpowiedział: $server)"
                }
            } catch (e: Exception) {
                log += "❌ $u → ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        confirmed?.let { return Result.success(Ok(it, true, log)) }
        return unconfirmed?.let { Result.success(Ok(it, false, log)) } ?: Result.failure(Failed(log))
    }

    /** W tle, 3 próby co 2 s. Przy błędzie toast + powiadomienie. */
    fun sendWycisz(context: Context, types: List<AlarmType>) {
        val app = context.applicationContext
        val urls = Prefs(app).webhookUrls
        Thread {
            var r: Result<Ok> = Result.failure(IllegalStateException("?"))
            for (i in 0 until 3) {
                r = call(urls, types)
                if (r.isSuccess) break
                if (i < 2) Thread.sleep(2000)
            }
            if (r.isFailure) {
                val msg = r.exceptionOrNull()?.message ?: "nieznany błąd"
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, "Alarm wyciszony, ale HA nie odpowiada", Toast.LENGTH_LONG).show()
                }
                notifyFail(app, msg)
            } else {
                app.getSystemService(NotificationManager::class.java).cancel(NOTIF_FAIL)
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun notifyFail(c: Context, msg: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "Informacje", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = NotificationCompat.Builder(c, CH_INFO)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Brak synchronizacji z Home Assistant")
            .setContentText("Alarm wyciszony, ale przełącznik w HA może być dalej ON")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Alarm wyciszony lokalnie, ale przełącznik w HA może być dalej ON.\nBłąd: $msg"))
            .setAutoCancel(true)
            .build()
        try { nm.notify(NOTIF_FAIL, n) } catch (_: SecurityException) {}
    }
}
