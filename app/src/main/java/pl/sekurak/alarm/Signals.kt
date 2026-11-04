package pl.sekurak.alarm

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Sygnały między Sekurakiem a Companion App HA.
 *  HA -> telefon:  START, STOP          (command_broadcast_intent)
 *  telefon -> HA:  ALARM, KONIEC        (czujnik Last update trigger w Companion App)
 */
object Signals {
    const val START = "pl.sekurak.START"
    const val STOP = "pl.sekurak.STOP"
    const val ALARM = "pl.sekurak.ALARM"
    const val KONIEC = "pl.sekurak.KONIEC"
    const val DISMISS = "pl.sekurak.DISMISS" // wewnętrzny – przycisk w powiadomieniu

    private val COMPANION_PACKAGES = listOf(
        "io.homeassistant.companion.android",          // wersja z Google Play
        "io.homeassistant.companion.android.minimal"   // wersja minimal / F-Droid
    )
    private const val REPEATS = 3
    private const val REPEAT_DELAY_MS = 2000L
    private val handler = Handler(Looper.getMainLooper())

    /** Wysyła sygnał do Companion App (łapie go czujnik Last update trigger). 3 razy co 2 s. */
    fun toHa(c: Context, action: String) {
        val app = c.applicationContext
        for (i in 0 until REPEATS) {
            handler.postDelayed({ sendOnce(app, action) }, i * REPEAT_DELAY_MS)
        }
    }

    private fun sendOnce(c: Context, action: String) {
        val flags = Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES
        // Bezpośrednio do apki Home Assistant
        for (pkg in COMPANION_PACKAGES) {
            try {
                c.sendBroadcast(Intent(action).setPackage(pkg).addFlags(flags))
            } catch (_: Exception) {}
        }
        // I dla pewności do wszystkich
        try {
            c.sendBroadcast(Intent(action).addFlags(flags))
        } catch (_: Exception) {}
    }
}
