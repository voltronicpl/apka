package pl.sekurak.alarm

import android.content.Context
import android.content.Intent

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

    /** Wysyła sygnał do Companion App (łapie go czujnik Last update trigger). */
    fun toHa(c: Context, action: String) {
        c.applicationContext.sendBroadcast(Intent(action))
    }
}
