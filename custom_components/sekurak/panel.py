"""Karta „Sekurak” w lewym menu HA + API (websocket) do czujników i czuwania."""

from __future__ import annotations

from pathlib import Path
from uuid import uuid4
from typing import Any

import voluptuous as vol

from homeassistant.components import panel_custom, websocket_api
from homeassistant.components.http import StaticPathConfig
from homeassistant.core import HomeAssistant, callback

from .const import (
    ARMABLE_DOMAINS,
    CONF_ARM_CODE,
    CONF_AUTO_DELAY,
    CONF_AUTO_ON,
    CONF_AUTO_TRACKERS,
    DEFAULT_AUTO_DELAY,
    PRESENCE_DOMAINS,
    ACTION_DOMAINS,
    CONF_ALARM_ACTIONS,
    SENSOR_ACTIONS,
    ARM_DOMAINS,
    CONF_ARM_ENTITY,
    CONF_EXIT_DELAY,
    CONF_SENSORS,
    DEFAULT_EXIT_DELAY,
    DOMAIN,
    TRIGGER_DOMAINS,
    TYPES,
    is_armed,
    sensor_active,
)

PANEL_URL = "sekurak"
STATIC_URL = "/sekurak_static"
PANEL_JS = "sekurak-panel.js"
PANEL_VERSION = "1.0.19"


async def async_setup_panel(hass: HomeAssistant) -> None:
    """Rejestruje kartę i komendy websocket – raz na cały HA."""
    if hass.data.get(f"{DOMAIN}_panel"):
        return
    hass.data[f"{DOMAIN}_panel"] = True

    www = Path(__file__).parent / "www"
    await hass.http.async_register_static_paths(
        [StaticPathConfig(STATIC_URL, str(www), cache_headers=False)]
    )
    await panel_custom.async_register_panel(
        hass,
        webcomponent_name="sekurak-panel",
        frontend_url_path=PANEL_URL,
        sidebar_title="Sekurak",
        sidebar_icon="mdi:shield-home",
        module_url=f"{STATIC_URL}/{PANEL_JS}?v={PANEL_VERSION}",
        require_admin=True,
        config={},
    )
    websocket_api.async_register_command(hass, ws_get)
    websocket_api.async_register_command(hass, ws_save_sensor)
    websocket_api.async_register_command(hass, ws_delete_sensor)
    websocket_api.async_register_command(hass, ws_settings)


def _entry(hass: HomeAssistant, entry_id: str):
    e = hass.config_entries.async_get_entry(entry_id)
    if e is None or e.domain != DOMAIN:
        return None
    return e


def _arm_status(hass: HomeAssistant, entry) -> str:
    opts = entry.options
    ent = opts.get(CONF_ARM_ENTITY) or ""
    if not ent:
        return "brak"
    st = hass.states.get(ent)
    if not is_armed(st.state if st else None):
        return "rozbrojone"
    sek = hass.data.get(DOMAIN, {}).get(entry.entry_id)
    if sek is not None and getattr(sek, "_in_exit", False):
        return "wyjscie"
    return "uzbrojone"


def _entry_info(hass: HomeAssistant, entry) -> dict[str, Any]:
    opts = entry.options
    sek = hass.data.get(DOMAIN, {}).get(entry.entry_id)
    sensors = []
    for s in opts.get(CONF_SENSORS, []) or []:
        st = hass.states.get(s.get("entity_id", ""))
        sensors.append(
            {
                **s,
                "state": st.state if st else None,
                "alarmowy": sensor_active(st.state if st else None, bool(s.get("invert"))),
            }
        )
    return {
        "entry_id": entry.entry_id,
        "title": entry.title,
        "czujniki": sensors,
        "czuwanie": opts.get(CONF_ARM_ENTITY) or "",
        "czas_wyjscia": int(opts.get(CONF_EXIT_DELAY, DEFAULT_EXIT_DELAY)),
        "akcje_alarmu": list(opts.get(CONF_ALARM_ACTIONS) or []),
        "auto_uzbrojenie": bool(opts.get(CONF_AUTO_ON)),
        "telefony": list(opts.get(CONF_AUTO_TRACKERS) or []),
        "auto_opoznienie": int(opts.get(CONF_AUTO_DELAY, DEFAULT_AUTO_DELAY)),
        "kod_ustawiony": bool(opts.get(CONF_ARM_CODE)),
        "auto_at": sek.auto_at if sek else None,
        "da_sie_uzbroic": (opts.get(CONF_ARM_ENTITY) or "").split(".", 1)[0] in ARMABLE_DOMAINS,
        "status": _arm_status(hass, entry),
        "aktywne": [t for t, v in (sek.active.items() if sek else []) if v],
    }


@websocket_api.require_admin
@websocket_api.websocket_command({vol.Required("type"): "sekurak/get"})
@callback
def ws_get(hass: HomeAssistant, connection, msg) -> None:
    entries = [_entry_info(hass, e) for e in hass.config_entries.async_entries(DOMAIN)]
    connection.send_result(
        msg["id"],
        {
            "entries": entries,
            "typy": TYPES,
            "domeny": TRIGGER_DOMAINS,
            "domeny_czuwania": ARM_DOMAINS,
            "domeny_akcji": ACTION_DOMAINS,
            "domeny_obecnosci": PRESENCE_DOMAINS,
        },
    )


def _clean(ents: list[str]) -> list[str]:
    return [e.strip() for e in dict.fromkeys(ents) if isinstance(e, str) and "." in e.strip()]


def _update(hass: HomeAssistant, entry, **changes: Any) -> None:
    opts = dict(entry.options)
    opts.update(changes)
    hass.config_entries.async_update_entry(entry, options=opts)


@websocket_api.require_admin
@websocket_api.websocket_command(
    {
        vol.Required("type"): "sekurak/sensor/save",
        vol.Required("entry_id"): str,
        vol.Optional("sensor_id"): vol.Any(str, None),
        vol.Required("entity_id"): str,
        vol.Optional("name", default=""): str,
        vol.Required("typ"): vol.In(list(TYPES)),
        vol.Optional("invert", default=False): bool,
        vol.Optional("akcje", default=[]): [str],
    }
)
@callback
def ws_save_sensor(hass: HomeAssistant, connection, msg) -> None:
    entry = _entry(hass, msg["entry_id"])
    if entry is None:
        connection.send_error(msg["id"], "not_found", "Nie ma takiej konfiguracji")
        return
    ent = msg["entity_id"].strip()
    if "." not in ent:
        connection.send_error(msg["id"], "invalid", "Wybierz encję")
        return
    sensors = [dict(s) for s in entry.options.get(CONF_SENSORS, []) or []]
    data = {
        "entity_id": ent,
        "name": msg["name"].strip() or ent,
        "typ": msg["typ"],
        "invert": bool(msg["invert"]),
        SENSOR_ACTIONS: _clean(msg["akcje"]),
    }
    sid = msg.get("sensor_id")
    cur = next((s for s in sensors if sid and s.get("id") == sid), None)
    if cur is not None:
        cur.update(data)
    else:
        sid = uuid4().hex
        sensors.append({"id": sid, **data})
    _update(hass, entry, **{CONF_SENSORS: sensors})
    connection.send_result(msg["id"], {"sensor_id": sid})


@websocket_api.require_admin
@websocket_api.websocket_command(
    {
        vol.Required("type"): "sekurak/sensor/delete",
        vol.Required("entry_id"): str,
        vol.Required("sensor_id"): str,
    }
)
@callback
def ws_delete_sensor(hass: HomeAssistant, connection, msg) -> None:
    entry = _entry(hass, msg["entry_id"])
    if entry is None:
        connection.send_error(msg["id"], "not_found", "Nie ma takiej konfiguracji")
        return
    sensors = [s for s in entry.options.get(CONF_SENSORS, []) or [] if s.get("id") != msg["sensor_id"]]
    _update(hass, entry, **{CONF_SENSORS: sensors})
    connection.send_result(msg["id"], {})


@websocket_api.require_admin
@websocket_api.websocket_command(
    {
        vol.Required("type"): "sekurak/settings",
        vol.Required("entry_id"): str,
        vol.Optional("czuwanie"): str,
        vol.Optional("czas_wyjscia"): vol.All(
            vol.Coerce(int), vol.Range(min=0, max=600)
        ),
        vol.Optional("akcje_alarmu"): [str],
        vol.Optional("auto_uzbrojenie"): bool,
        vol.Optional("telefony"): [str],
        vol.Optional("auto_opoznienie"): vol.All(vol.Coerce(int), vol.Range(min=0, max=3600)),
        vol.Optional("kod"): vol.Any(str, None),
    }
)
@callback
def ws_settings(hass: HomeAssistant, connection, msg) -> None:
    entry = _entry(hass, msg["entry_id"])
    if entry is None:
        connection.send_error(msg["id"], "not_found", "Nie ma takiej konfiguracji")
        return
    changes: dict[str, Any] = {}
    if "czuwanie" in msg:
        changes[CONF_ARM_ENTITY] = msg["czuwanie"].strip()
    if "czas_wyjscia" in msg:
        changes[CONF_EXIT_DELAY] = msg["czas_wyjscia"]
    if "akcje_alarmu" in msg:
        changes[CONF_ALARM_ACTIONS] = _clean(msg["akcje_alarmu"])
    if "auto_uzbrojenie" in msg:
        changes[CONF_AUTO_ON] = msg["auto_uzbrojenie"]
    if "telefony" in msg:
        changes[CONF_AUTO_TRACKERS] = _clean(msg["telefony"])
    if "auto_opoznienie" in msg:
        changes[CONF_AUTO_DELAY] = msg["auto_opoznienie"]
    if msg.get("kod") is not None:  # None = zostaw stary, "" = skasuj
        changes[CONF_ARM_CODE] = msg["kod"].strip()
    _update(hass, entry, **changes)
    connection.send_result(msg["id"], {})
