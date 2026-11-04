package pl.sekurak.alarm

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/** Pełnoekranowy ekran alarmu z wielkim przyciskiem WYCISZ ALARM. */
class AlarmActivity : AppCompatActivity() {

    private val animators = mutableListOf<AnimatorSet>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!AlarmService.running) {
            if (intent.getBooleanExtra(EXTRA_FALLBACK, false)) {
                AlarmService.start(this) // tryb awaryjny: serwis startuje z widocznego ekranu
            } else {
                finish()
                return
            }
        }
        open += this
        setContentView(R.layout.activity_alarm)

        // Tło: czerwona poświata oddychająca + pulsujące fale wokół dzwonka
        breathe(findViewById(R.id.glow))
        pulse(findViewById(R.id.ring), 1.6f, 1600, fade = true)
        pulse(findViewById(R.id.ring2), 1.6f, 1600, fade = true, delay = 800)
        pulse(findViewById(R.id.bell), 1.12f, 700)
        pulse(findViewById(R.id.btnStop), 1.04f, 700)

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            AlarmService.finishAlarm(this)
            finish()
        }

        // Wstecz nie zamyka alarmu – tylko przycisk.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
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
        fun closeAll() = open.toList().forEach { it.finish() }
    }
}
