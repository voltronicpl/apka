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

/** Wysyła webhook do HA po wyciszeniu alarmu (HA wyłącza przełącznik). */
object Webhook {
    private const val CH_INFO = "sekurak_info"
    private const val NOTIF_FAIL = 2

    /** Jedno wywołanie. Wołać poza wątkiem UI. */
    fun call(url: String): Result<Unit> = runCatching {
        require(url.startsWith("http")) { "Brak adresu webhooka w ustawieniach" }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write("""{"zrodlo":"sekurak"}""".toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) error("HA odpowiedział HTTP $code")
        } finally {
            c.disconnect()
        }
    }

    /** W tle, 3 próby co 2 s. Przy błędzie toast + powiadomienie. */
    fun sendWycisz(context: Context) {
        val app = context.applicationContext
        val url = Prefs(app).webhookUrl
        Thread {
            var r: Result<Unit> = Result.failure(IllegalStateException("?"))
            for (i in 0 until 3) {
                r = call(url)
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
