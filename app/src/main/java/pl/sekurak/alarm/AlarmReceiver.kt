package pl.sekurak.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Odbiera sygnały z Companion App HA:
 *  - START  -> uruchom alarm
 *  - STOP   -> wyłączono w HA -> zgaś alarm
 *  - DISMISS (przycisk w powiadomieniu) -> zgaś alarm
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Signals.START -> {
                val secret = Prefs(context).secret
                if (secret.isNotEmpty() && intent.getStringExtra("secret") != secret) {
                    Log.w(TAG, "START odrzucony – zły sekret")
                    return
                }
                try {
                    AlarmService.start(context)
                } catch (e: Exception) {
                    // Android nie pozwolił odpalić serwisu z tła -> pełnoekranowe powiadomienie,
                    // ekran alarmu sam dokończy start serwisu.
                    Log.w(TAG, "Nie można uruchomić serwisu z tła, fallback", e)
                    postFallback(context)
                }
            }
            Signals.STOP, Signals.DISMISS -> AlarmService.finishAlarm(context)
        }
    }

    private fun postFallback(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH_FALLBACK, "Alarm (awaryjny)", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            )
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
        val full = PendingIntent.getActivity(
            c, 10,
            Intent(c, AlarmActivity::class.java)
                .putExtra(AlarmActivity.EXTRA_FALLBACK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(c, CH_FALLBACK)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("ALARM!")
            .setContentText("Alarm uruchomiony przez Home Assistant")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setAutoCancel(true)
            .build()
        try { nm.notify(NOTIF_FALLBACK, n) } catch (_: SecurityException) {}
    }

    companion object {
        const val CH_FALLBACK = "sekurak_fallback"
        const val NOTIF_FALLBACK = 3
        private const val TAG = "Sekurak"
    }
}
