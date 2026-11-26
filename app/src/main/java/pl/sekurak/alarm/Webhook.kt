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
    /** Domyślny adres z zewnątrz – działa bez wpisywania czegokolwiek w apce. */
    const val DEFAULT_BASE = "https://pusiloft.ovh"
    const val DEFAULT_URL = "$DEFAULT_BASE/api/webhook/sekurak_wycisz_pudaul75"
    private const val CH_INFO = "sekurak_info"
    private const val NOTIF_FAIL = 2

    private fun body(types: List<AlarmType>) =
        """{"zrodlo":"sekurak","typy":[${types.joinToString(",") { "\"${it.key}\"" }}]}"""

    /** Wynik: adres, który zadziałał, i czy odpowiedziała integracja Sekurak. */
    data class Ok(val url: String, val confirmed: Boolean)

    /**
     * Adresy do spróbowania po kolei: wpisany, ten sam webhook przez adres zewnętrzny
     * (gdy wpisany jest lokalny http://…), i domyślny.
     */
    fun candidates(url: String): List<String> {
        val list = mutableListOf<String>()
        if (url.startsWith("http")) list += url
        val i = url.indexOf("/api/webhook/")
        if (i > 0 && !url.startsWith("https://")) list += DEFAULT_BASE + url.substring(i)
        list += DEFAULT_URL
        return list.distinct()
    }

    /** Jedno wywołanie jednego adresu. Zwraca true, gdy odpowiedziała integracja Sekurak. */
    private fun callOne(url: String, types: List<AlarmType>): Boolean {
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
            if (code !in 200..299) error("HA odpowiedział HTTP $code")
            val resp = runCatching { c.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            return resp.contains("sekurak")
        } finally {
            c.disconnect()
        }
    }

    /**
     * Próbuje kolejnych adresów. Sukces = integracja potwierdziła; jeśli żaden nie
     * potwierdził, ale któryś dał 2xx (np. stara automatyzacja YAML), też sukces.
     * Wołać poza wątkiem UI. Pusta lista = test (HA nic nie wyłącza).
     */
    fun call(url: String, types: List<AlarmType>): Result<Ok> {
        var unconfirmed: String? = null
        var lastErr: Throwable = IllegalStateException("Brak adresu webhooka")
        for (u in candidates(url)) {
            try {
                if (callOne(u, types)) return Result.success(Ok(u, true))
                if (unconfirmed == null) unconfirmed = u
            } catch (e: Exception) {
                lastErr = e
            }
        }
        return unconfirmed?.let { Result.success(Ok(it, false)) } ?: Result.failure(lastErr)
    }

    /** W tle, 3 próby co 2 s. Przy błędzie toast + powiadomienie. */
    fun sendWycisz(context: Context, types: List<AlarmType>) {
        val app = context.applicationContext
        val url = Prefs(app).webhookUrl
        Thread {
            var r: Result<Ok> = Result.failure(IllegalStateException("?"))
            for (i in 0 until 3) {
                r = call(url, types)
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
