package pl.sekurak.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Serwis trzymający alarm: dźwięk w pętli, wibracje, pełnoekranowe powiadomienie. */
class AlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedVolume = -1
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopAlarm() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (instance === this) {
            showActivity()
            return START_NOT_STICKY
        }
        createChannels(this)
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ALARM, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground nieudany", e)
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        getSystemService(NotificationManager::class.java).cancel(AlarmReceiver.NOTIF_FALLBACK)

        wakeScreen()
        startSound()
        startVibration()
        showActivity()
        Signals.toHa(this, Signals.ALARM) // potwierdzenie do HA: telefon dzwoni

        val min = Prefs(this).autoStopMin
        if (min > 0) handler.postDelayed(autoStop, min * 60_000L)
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val full = PendingIntent.getActivity(
            this, 0, activityIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismiss = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, AlarmReceiver::class.java).setAction(Signals.DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_ALARM)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("ALARM!")
            .setContentText("Alarm uruchomiony przez Home Assistant")
            .setColor(0xFFFF3B30.toInt())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, "WYCISZ ALARM", dismiss)
            .build()
    }

    private fun activityIntent() = Intent(this, AlarmActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    /** Bezpośrednie otwarcie ekranu (z tła działa tylko z uprawnieniem "wyświetlanie nad innymi"). */
    private fun showActivity() {
        if (Settings.canDrawOverlays(this)) {
            try { startActivity(activityIntent()) } catch (e: Exception) { Log.w(TAG, "startActivity", e) }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sekurak:alarm").apply { acquire(30 * 60_000L) }
        try {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "sekurak:screen"
            ).acquire(10_000L)
        } catch (_: Exception) {}
    }

    private fun startSound() {
        val prefs = Prefs(this)
        val am = getSystemService(AudioManager::class.java)
        if (prefs.forceVolume) {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxOf(1, max * prefs.volume / 100), 0)
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val candidates = listOfNotNull(
            prefs.ringtoneUri,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        )
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                Log.w(TAG, "Nie da się odtworzyć $uri", e)
                mp.release()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        if (!Prefs(this).vibrate) return
        val v = if (Build.VERSION.SDK_INT >= 31)
            getSystemService(VibratorManager::class.java).defaultVibrator
        else getSystemService(Vibrator::class.java)
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 500), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    /** Gasi wszystko lokalnie natychmiast i wysyła KONIEC do HA. Wołać z wątku UI. */
    fun stopAlarm() {
        if (instance !== this) return
        instance = null
        handler.removeCallbacks(autoStop)
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        if (savedVolume >= 0) {
            getSystemService(AudioManager::class.java)
                .setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            savedVolume = -1
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        AlarmActivity.closeAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Signals.toHa(this, Signals.KONIEC)
    }

    override fun onDestroy() {
        if (instance === this) stopAlarm()
        super.onDestroy()
    }

    companion object {
        const val CH_ALARM = "sekurak_alarm"
        const val NOTIF_ALARM = 1
        private const val TAG = "Sekurak"

        @Volatile
        var instance: AlarmService? = null
            private set

        val running: Boolean get() = instance != null

        fun start(c: Context) {
            ContextCompat.startForegroundService(c, Intent(c, AlarmService::class.java))
        }

        /** Kończy alarm niezależnie od stanu (też tryb awaryjny bez serwisu). */
        fun finishAlarm(c: Context) {
            val svc = instance
            if (svc != null) {
                svc.stopAlarm()
            } else {
                c.getSystemService(NotificationManager::class.java).cancel(AlarmReceiver.NOTIF_FALLBACK)
                AlarmActivity.closeAll()
                Signals.toHa(c, Signals.KONIEC)
            }
        }

        fun createChannels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            val alarm = NotificationChannel(CH_ALARM, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // dźwięk gra serwis
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(alarm)
        }
    }
}
