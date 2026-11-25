package pl.sekurak.alarm

/** Typy alarmów. priority: wyższy wygrywa, gdy kilka alarmów działa naraz. */
enum class AlarmType(
    val key: String,
    val label: String,
    val title: String,
    val subtitle: String,
    val emoji: String,
    val color: Int,
    val bgRes: Int,
    val priority: Int
) {
    OGOLNY("ogolny", "Ogólny", "ALARM!", "Alarm uruchomiony przez Home Assistant",
        "🔔", 0xFFFF3B30.toInt(), 0, 2),
    DOMOFON("domofon", "Domofon", "DOMOFON", "Ktoś dzwoni do furtki",
        "🚪", 0xFF34C759.toInt(), R.drawable.bg_domofon, 1),
    ZALANIE("zalanie", "Zalanie", "ZALANIE!", "Wykryto wodę – sprawdź czujniki",
        "💧", 0xFF2F9BFF.toInt(), R.drawable.bg_zalanie, 3),
    ALARM("alarm", "Alarm", "ALARM!", "Alarm Ropam uzbrojony i wzbudzony",
        "🚨", 0xFFFF2D2D.toInt(), R.drawable.bg_alarm_dom, 4),
    SZUKAJ("szukaj", "Gdzie jest fon", "SZUKAM TELEFONU", "Ktoś szuka tego telefonu – tu jestem!",
        "📱", 0xFF22D3EE.toInt(), R.drawable.bg_szukaj, 0);

    companion object {
        fun from(key: String?): AlarmType =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: OGOLNY
    }
}
