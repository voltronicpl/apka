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
 * Sygnały z Companion App HA (extra "typ": ogolny / domofon / zalanie):
 *  - START  -> uruchom alarm danego typu
 *  - STOP   -> ten typ wyłączono w HA -> zgaś go
 *  - DISMISS (przycisk w powiadomieniu) -> zgaś wszystko + webhook do HA
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = AlarmType.from(intent.getStringExtra("typ"))
        when (intent.action) {
            Signals.START -> {
                val secret = Prefs(context).secret
                if (secret.isNotEmpty() && intent.getStringExtra("secret") != secret) {
                    Log.w(TAG, "START odrzucony – zły sekret")
                    return
                }
                try {
                    AlarmService.start(context, type)
                } catch (e: Exception) {
                    Log.w(TAG, "Nie można uruchomić serwisu z tła, fallback", e)
                    postFallback(context, type)
                }
            }
            Signals.STOP -> AlarmService.finishAlarm(context, syncHa = false, type = type)
            Signals.DISMISS -> AlarmService.finishAlarm(context, syncHa = true)
        }
    }

    private fun postFallback(c: Context, type: AlarmType) {
        fallbackType = type
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
                .putExtra(AlarmService.EXTRA_TYPE, type.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(c, CH_FALLBACK)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("${type.emoji} ${type.title}")
            .setContentText(type.subtitle)
            .setColor(type.color)
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

        /** Typ ostatniego alarmu awaryjnego (gdy serwis nie wystartował z tła). */
        @Volatile
        var fallbackType: AlarmType = AlarmType.OGOLNY
    }
}
