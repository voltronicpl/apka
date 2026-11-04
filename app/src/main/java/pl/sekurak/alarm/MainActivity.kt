package pl.sekurak.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Ekran ustawień: dzwonek, głośność, uprawnienia, test. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etSecret: EditText
    private lateinit var etAutoStop: EditText
    private lateinit var tvRingtone: TextView
    private lateinit var tvVolume: TextView
    private lateinit var tvPerms: TextView

    private val pickRingtone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
                r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            else @Suppress("DEPRECATION") r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.ringtoneUri = uri
            refreshRingtone()
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            prefs.ringtoneUri = uri
            refreshRingtone()
        }
    }

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPerms() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        AlarmService.createChannels(this)

        etSecret = findViewById(R.id.etSecret)
        etAutoStop = findViewById(R.id.etAutoStop)
        tvRingtone = findViewById(R.id.tvRingtone)
        tvVolume = findViewById(R.id.tvVolume)
        tvPerms = findViewById(R.id.tvPerms)

        etSecret.setText(prefs.secret)
        etAutoStop.setText(prefs.autoStopMin.toString())

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
            Toast.makeText(this, "Zapisano", Toast.LENGTH_SHORT).show()
        }

        // Dzwonek
        findViewById<Button>(R.id.btnPickRingtone).setOnClickListener {
            pickRingtone.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Dzwonek alarmu")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, prefs.ringtoneUri)
            })
        }
        findViewById<Button>(R.id.btnPickFile).setOnClickListener { pickFile.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.btnDefaultRingtone).setOnClickListener {
            prefs.ringtoneUri = null
            refreshRingtone()
        }

        // Głośność
        val sb = findViewById<SeekBar>(R.id.sbVolume)
        sb.progress = prefs.volume
        tvVolume.text = "Głośność alarmu: ${prefs.volume}%"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = p.coerceAtLeast(10)
                prefs.volume = v
                tvVolume.text = "Głośność alarmu: $v%"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        findViewById<SwitchCompat>(R.id.swForce).apply {
            isChecked = prefs.forceVolume
            setOnCheckedChangeListener { _, c -> prefs.forceVolume = c }
        }
        findViewById<SwitchCompat>(R.id.swVibrate).apply {
            isChecked = prefs.vibrate
            setOnCheckedChangeListener { _, c -> prefs.vibrate = c }
        }

        // Uprawnienia
        findViewById<Button>(R.id.btnPermNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
        findViewById<Button>(R.id.btnPermFull).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 34) openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            else Toast.makeText(this, "Na tej wersji Androida niepotrzebne", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnPermBattery).setOnClickListener { requestBattery() }
        findViewById<Button>(R.id.btnPermOverlay).setOnClickListener {
            openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        }

        findViewById<Button>(R.id.btnTestAlarm).setOnClickListener {
            save()
            AlarmService.start(this)
        }

        refreshRingtone()
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun save() {
        prefs.secret = etSecret.text.toString()
        prefs.autoStopMin = etAutoStop.text.toString().toIntOrNull() ?: 0
    }

    private fun refreshRingtone() {
        val uri = prefs.ringtoneUri
        val name = if (uri == null) "Domyślny dźwięk alarmu" else try {
            RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
        tvRingtone.text = "Dzwonek: $name"
    }

    private fun refreshPerms() {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val full = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        val batt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val overlay = Settings.canDrawOverlays(this)
        fun m(b: Boolean) = if (b) "✅" else "❌"
        tvPerms.text = buildString {
            appendLine("${m(notif)} Powiadomienia (wymagane)")
            appendLine("${m(full)} Pełny ekran nad blokadą (wymagane)")
            appendLine("${m(batt)} Bez optymalizacji baterii (wymagane)")
            append("${m(overlay)} Wyświetlanie nad innymi (zalecane – pełny ekran też gdy używasz telefonu)")
        }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action, Uri.parse("package:$packageName")).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
