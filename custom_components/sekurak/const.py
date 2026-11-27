"""Stałe integracji Sekurak."""

DOMAIN = "sekurak"

CONF_NOTIFY = "notify_service"
CONF_WEBHOOK_ID = "webhook_id"
CONF_SECRET = "secret"
CONF_EXTERNAL = "external_url"

# Adres zewnętrzny HA (tak jak w Companion App). Puste = weź z HA (zewnętrzny URL / Nabu Casa).
DEFAULT_EXTERNAL = "https://www.pusiloft.ovh"


def fix_external(url: str) -> str:
    """Goły pusiloft.ovh nie prowadzi do HA (Cloudflare 404) – zawsze z www."""
    url = (url or "").strip().rstrip("/")
    for old in ("https://pusiloft.ovh", "http://pusiloft.ovh"):
        if url == old or url.startswith(old + "/"):
            return "https://www.pusiloft.ovh" + url[len(old):]
    return url
# Jednorazowy webhook wyciszenia: ważny do wyciszenia, najdłużej 15 min.
ONE_TIME_TTL = 15 * 60

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
ACTIVE_STATES = {"triggered", "on", "open"}
# Stany "spoczynku" – przy odwróconym sygnale to one odpalają alarm
REST_STATES = {"off", "closed", "disarmed"}

# ---- lista czujników (v1.0.16) ----
CONF_SENSORS = "czujniki"  # lista: {id, entity_id, name, typ, invert}
CONF_ARM_ENTITY = "czuwanie"  # encja: czy alarm jest uzbrojony
CONF_EXIT_DELAY = "czas_wyjscia"  # sekundy na wyjście po uzbrojeniu
DEFAULT_EXIT_DELAY = 30
ARM_DOMAINS = ["alarm_control_panel", "input_boolean", "binary_sensor", "switch"]
MANUAL_SOURCE = "Ręcznie z HA"
CONF_AUTO_ON = "auto_uzbrojenie"  # bool: uzbrajaj, gdy wszyscy wyjdą z domu
CONF_AUTO_TRACKERS = "telefony"  # person.* / device_tracker.*
CONF_AUTO_DELAY = "auto_opoznienie"  # sekundy od wyjścia ostatniego do uzbrojenia
DEFAULT_AUTO_DELAY = 120
CONF_ARM_CODE = "kod"  # kod centrali (alarm_control_panel), opcjonalny
PRESENCE_DOMAINS = ["person", "device_tracker"]
ARMABLE_DOMAINS = ("alarm_control_panel", "input_boolean", "switch")
CONF_ALARM_ACTIONS = "akcje_alarmu"  # encje odpalane przy włamaniu (czujnik typu Alarm)
SENSOR_ACTIONS = "akcje"  # w czujniku: jego własne encje do odpalenia
ACTION_DOMAINS = [
    "siren", "switch", "input_boolean", "light", "script", "scene",
    "automation", "button", "input_button", "fan", "media_player",
]
# te gasimy po wyciszeniu / wyłączeniu alarmu
ACTION_OFF_DOMAINS = ("siren", "switch", "input_boolean", "light", "fan", "media_player")


def sensor_active(state: str | None, invert: bool) -> bool:
    """Czy czujnik jest w stanie alarmowym (z uwzględnieniem odwrócenia)."""
    if state is None:
        return False
    return state in REST_STATES if invert else state in ACTIVE_STATES


def is_armed(state: str | None) -> bool:
    """Uzbrojony = armed_* (Ropam / alarm_control_panel), triggered albo on."""
    if not state:
        return False
    return state.startswith("armed_") or state in ("on", "triggered", "pending")

# Przełączniki ze starej automatyzacji YAML – gaszone po wyciszeniu na telefonie
LEGACY_HELPERS = {
    "ogolny": "input_boolean.input_boolean_alarm_telefoniczny",
    "domofon": "input_boolean.alarm_domofon",
    "zalanie": "input_boolean.alarm_zalanie",
    "alarm": "input_boolean.alarm_ropam",
    "szukaj": "input_boolean.alarm_szukaj_fon",
}
# Encje wyzwalające z tych domen są gaszone po wyciszeniu (Ropama nie rozbrajamy)
TURN_OFF_DOMAINS = ("input_boolean", "switch")

SIGNAL_UPDATE = f"{DOMAIN}_update_{{}}"
