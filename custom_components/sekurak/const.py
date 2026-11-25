"""Stałe integracji Sekurak."""

DOMAIN = "sekurak"

CONF_NOTIFY = "notify_service"
CONF_WEBHOOK_ID = "webhook_id"
CONF_SECRET = "secret"

PACKAGE = "pl.sekurak.alarm"
ACTION_START = "pl.sekurak.START"
ACTION_STOP = "pl.sekurak.STOP"

# Typy alarmów – klucze takie same jak w apce (AlarmType.kt)
TYPES: dict[str, str] = {
    "alarm": "Alarm",
    "zalanie": "Zalanie",
    "ogolny": "Ogólny",
    "domofon": "Domofon",
    "szukaj": "Gdzie jest fon",
}
# Typy, dla których w UI wybiera się encje wyzwalające
TRIGGER_TYPES = ["alarm", "zalanie", "domofon", "ogolny"]
ICONS = {
    "alarm": "mdi:shield-alert",
    "zalanie": "mdi:water-alert",
    "ogolny": "mdi:bell-ring",
    "domofon": "mdi:doorbell",
    "szukaj": "mdi:cellphone-sound",
}


def conf_entities(typ: str) -> str:
    """Klucz opcji z listą encji wyzwalających dany typ."""
    return f"encje_{typ}"


# Domeny encji, które mogą wyzwalać alarm, i ich "aktywne" stany
TRIGGER_DOMAINS = ["alarm_control_panel", "binary_sensor", "input_boolean", "switch"]
ACTIVE_STATES = {"triggered", "on"}

SIGNAL_UPDATE = f"{DOMAIN}_update_{{}}"
