package pl.sekurak.alarm

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils

/** Pełnoekranowy ekran alarmu – wygląd zależy od typu alarmu. */
class AlarmActivity : AppCompatActivity() {

    private val animators = mutableListOf<AnimatorSet>()
    private var keepOpen = false
    private lateinit var shown: AlarmType

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!AlarmService.running) {
            if (intent.getBooleanExtra(EXTRA_FALLBACK, false)) {
                // tryb awaryjny: serwis startuje z widocznego ekranu
                AlarmService.start(this, AlarmType.from(intent.getStringExtra(AlarmService.EXTRA_TYPE)))
            } else {
                finish()
                return
            }
        }
        open += this
        setContentView(R.layout.activity_alarm)

        breathe(findViewById(R.id.glow))
        pulse(findViewById(R.id.ring), 1.6f, 1600, fade = true)
        pulse(findViewById(R.id.ring2), 1.6f, 1600, fade = true, delay = 800)
        pulse(findViewById(R.id.bell), 1.12f, 700)
        pulse(findViewById(R.id.tvEmoji), 1.15f, 700)
        pulse(findViewById(R.id.btnStop), 1.04f, 700)

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            AlarmService.finishAlarm(this, syncHa = true)
            finish()
        }
        findViewById<Button>(R.id.btnOpen).setOnClickListener { dismissAndOpen() }

        // Wstecz nie zamyka alarmu – tylko przycisk.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        applyType()
    }

    private fun currentType(): AlarmType =
        AlarmService.instance?.current
            ?: AlarmType.from(intent.getStringExtra(AlarmService.EXTRA_TYPE))

    /** Ustawia tło, kolory, ikonę i napisy według typu alarmu. */
    fun applyType() {
        val t = currentType()
        shown = t
        val bg = findViewById<ImageView>(R.id.bgImage)
        val bellBox = findViewById<View>(R.id.bellBox)
        val emoji = findViewById<TextView>(R.id.tvEmoji)
        if (t.bgRes != 0) {
            bg.setImageResource(t.bgRes)
            bg.visibility = View.VISIBLE
            bellBox.visibility = View.INVISIBLE
            emoji.visibility = View.VISIBLE
            emoji.text = t.emoji
        } else {
            bg.visibility = View.GONE
            bellBox.visibility = View.VISIBLE
            emoji.visibility = View.GONE
        }

        findViewById<TextView>(R.id.tvTitle).apply {
            text = t.title
            setTextColor(t.color)
            setShadowLayer(28f, 0f, 0f, ColorUtils.setAlphaComponent(t.color, 0xAA))
        }
        findViewById<TextView>(R.id.tvSubtitle).text = t.subtitle
        findViewById<ImageView>(R.id.bell).imageTintList = ColorStateList.valueOf(t.color)
        findViewById<View>(R.id.ring).background = oval(ColorUtils.setAlphaComponent(t.color, 0x66), 0, 0)
        findViewById<View>(R.id.ring2).background = oval(ColorUtils.setAlphaComponent(t.color, 0x66), 0, 0)
        findViewById<View>(R.id.bellCircle).background = oval(0xFF1C1C24.toInt(), 3, t.color)
        findViewById<View>(R.id.glow).background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = resources.displayMetrics.density * 420
            setGradientCenter(0.5f, if (t.bgRes != 0) 0.75f else 0.35f)
            colors = intArrayOf(ColorUtils.setAlphaComponent(t.color, 0x55), Color.TRANSPARENT)
        }

        findViewById<Button>(R.id.btnStop).background = pill(t.color, filled = true)
        val path = Prefs(this).openPath(t)
        findViewById<Button>(R.id.btnOpen).apply {
            visibility = if (path.isNotEmpty()) View.VISIBLE else View.GONE
            text = "WYCISZ I OTWÓRZ ${t.label.uppercase()}"
            background = pill(t.color, filled = false)
        }
    }

    private fun oval(fill: Int, strokeDp: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (strokeDp > 0) setStroke((strokeDp * resources.displayMetrics.density).toInt(), stroke)
    }

    private fun pill(color: Int, filled: Boolean): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = 64 * resources.displayMetrics.density
            if (filled) {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(color, ColorUtils.blendARGB(color, Color.BLACK, 0.25f))
            } else {
                setColor(0xCC0B0B0F.toInt())
                setStroke((2 * resources.displayMetrics.density).toInt(), color)
            }
        }
        return RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), shape, null)
    }

    /** Wycisza, wysyła webhook i otwiera kartę w Companion App (po odblokowaniu telefonu). */
    private fun dismissAndOpen() {
        val path = Prefs(this).openPath(shown).let { if (it.startsWith("/")) it else "/$it" }
        keepOpen = true
        AlarmService.finishAlarm(this, syncHa = true)
        val go = {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("homeassistant://navigate$path"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                Toast.makeText(this, "Nie znaleziono aplikacji Home Assistant", Toast.LENGTH_LONG).show()
            }
            finish()
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = go()
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        } else {
            go()
        }
    }

    private fun breathe(v: View) {
        val a = ObjectAnimator.ofFloat(v, "alpha", 0.35f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        animators += AnimatorSet().apply { play(a); start() }
    }

    private fun pulse(v: View, to: Float, dur: Long, fade: Boolean = false, delay: Long = 0) {
        val mode = if (fade) ValueAnimator.RESTART else ValueAnimator.REVERSE
        val list = mutableListOf(
            ObjectAnimator.ofFloat(v, "scaleX", 1f, to),
            ObjectAnimator.ofFloat(v, "scaleY", 1f, to)
        )
        if (fade) list += ObjectAnimator.ofFloat(v, "alpha", 0.7f, 0f)
        list.forEach {
            it.duration = dur
            it.repeatCount = ValueAnimator.INFINITE
            it.repeatMode = mode
        }
        if (fade) v.alpha = 0f
        animators += AnimatorSet().apply { playTogether(list.toList()); startDelay = delay; start() }
    }

    override fun onDestroy() {
        animators.forEach { it.cancel() }
        open -= this
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FALLBACK = "fallback"
        private val open = mutableSetOf<AlarmActivity>()

        /** Zamyka ekrany alarmu (oprócz tego, który właśnie otwiera kartę HA). */
        fun closeAll() = open.toList().filter { !it.keepOpen }.forEach { it.finish() }

        /** Odświeża wygląd po zmianie typu (np. przyszedł ważniejszy alarm). */
        fun refreshAll() = open.toList().forEach { it.applyType() }
    }
}
