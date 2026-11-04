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
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Ekran ustawień: typy alarmów, ustawienia wspólne, webhook, uprawnienia. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etWebhook: EditText
    private lateinit var etSecret: EditText
    private lateinit var etAutoStop: EditText
    private lateinit var tvVolume: TextView
    private lateinit var tvPerms: TextView

    private val ringtoneLabels = mutableMapOf<AlarmType, TextView>()
    private val pathFields = mutableMapOf<AlarmType, EditText>()
    private var pendingType: AlarmType = AlarmType.OGOLNY

    private val pickRingtone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
                r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            else @Suppress("DEPRECATION") r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.setRingtone(pendingType, uri)
            refreshRingtone(pendingType)
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            prefs.setRingtone(pendingType, uri)
            refreshRingtone(pendingType)
        }
    }

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPerms() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        AlarmService.createChannels(this)

        etWebhook = findViewById(R.id.etWebhook)
        etSecret = findViewById(R.id.etSecret)
        etAutoStop = findViewById(R.id.etAutoStop)
        tvVolume = findViewById(R.id.tvVolume)
        tvPerms = findViewById(R.id.tvPerms)

        etWebhook.setText(prefs.webhookUrl)
        etSecret.setText(prefs.secret)
        etAutoStop.setText(prefs.autoStopMin.toString())

        buildTypeCards()

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
            Toast.makeText(this, "Zapisano", Toast.LENGTH_SHORT).show()
        }

        val tvWebhook = findViewById<TextView>(R.id.tvWebhookStatus)
        findViewById<Button>(R.id.btnTestWebhook).setOnClickListener {
            save()
            tvWebhook.text = "Wysyłam…"
            val url = prefs.webhookUrl
            Thread {
                val r = Webhook.call(url, emptyList()) // test – HA nic nie wyłącza
                runOnUiThread {
                    tvWebhook.text = if (r.isSuccess) "✅ HA odebrał webhook"
                    else "❌ ${r.exceptionOrNull()?.message}"
                }
            }.start()
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
    }

    /** Kafelek ustawień dla każdego typu alarmu. */
    private fun buildTypeCards() {
        val container = findViewById<LinearLayout>(R.id.typesContainer)
        for (t in AlarmType.entries) {
            val card = layoutInflater.inflate(R.layout.item_type, container, false)
            card.findViewById<TextView>(R.id.tvTypeTitle).apply {
                text = "${t.emoji}  ${t.label}"
                setTextColor(t.color)
            }
            ringtoneLabels[t] = card.findViewById(R.id.tvTypeRingtone)
            card.findViewById<Button>(R.id.btnTypeRingtone).setOnClickListener {
                pendingType = t
                pickRingtone.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Dzwonek: ${t.label}")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, prefs.ringtone(t))
                })
            }
            card.findViewById<Button>(R.id.btnTypeFile).setOnClickListener {
                pendingType = t
                pickFile.launch(arrayOf("audio/*"))
            }
            card.findViewById<Button>(R.id.btnTypeDefault).setOnClickListener {
                prefs.setRingtone(t, null)
                refreshRingtone(t)
            }
            card.findViewById<SwitchCompat>(R.id.swTypeVibrate).apply {
                isChecked = prefs.vibrate(t)
                setOnCheckedChangeListener { _, c -> prefs.setVibrate(t, c) }
            }
            val et = card.findViewById<EditText>(R.id.etTypePath)
            et.setText(prefs.openPath(t))
            pathFields[t] = et
            card.findViewById<Button>(R.id.btnTypeTest).apply {
                text = "Testuj: ${t.label}"
                setOnClickListener {
                    save()
                    AlarmService.start(this@MainActivity, t)
                }
            }
            container.addView(card)
            refreshRingtone(t)
        }
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
        prefs.webhookUrl = etWebhook.text.toString()
        prefs.secret = etSecret.text.toString()
        prefs.autoStopMin = etAutoStop.text.toString().toIntOrNull() ?: 0
        pathFields.forEach { (t, et) -> prefs.setOpenPath(t, et.text.toString()) }
    }

    private fun refreshRingtone(t: AlarmType) {
        val uri = prefs.ringtone(t)
        val name = if (uri == null) "Domyślny dźwięk alarmu" else try {
            RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
        ringtoneLabels[t]?.text = "Dzwonek: $name"
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
