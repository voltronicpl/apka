package pl.sekurak.alarm

/**
 * Sygnały z HA przez Companion App (command_broadcast_intent):
 *  START – włącz alarm, STOP – wyłączono w HA.
 * Powrót do HA (wyciszenie na telefonie) idzie webhookiem – patrz Webhook.kt.
 */
object Signals {
    const val START = "pl.sekurak.START"
    const val STOP = "pl.sekurak.STOP"
    const val DISMISS = "pl.sekurak.DISMISS" // wewnętrzny – przycisk w powiadomieniu
}
