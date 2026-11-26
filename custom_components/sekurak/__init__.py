"""Sekurak – alarmy na telefon przez apkę Sekurak i Companion App."""

from __future__ import annotations

import asyncio
import logging
import secrets
from uuid import uuid4
from typing import Any
from urllib.parse import quote

from aiohttp import web

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import Event, HomeAssistant, callback
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.event import async_call_later, async_track_state_change_event
from homeassistant.helpers.network import NoURLAvailableError, get_url

from .const import (
    ACTION_START,
    ACTION_STOP,
    CONF_ARM_ENTITY,
    CONF_EXIT_DELAY,
    CONF_SENSORS,
    DEFAULT_EXIT_DELAY,
    MANUAL_SOURCE,
    CONF_EXTERNAL,
    CONF_NOTIFY,
    CONF_SECRET,
    DEFAULT_EXTERNAL,
    DOMAIN,
    ONE_TIME_TTL,
    LEGACY_HELPERS,
    TURN_OFF_DOMAINS,
    PACKAGE,
    SIGNAL_UPDATE,
    TRIGGER_TYPES,
    is_armed,
    sensor_active,
    TYPES,
    conf_entities,
    fix_external,
)

_LOGGER = logging.getLogger(__name__)
PLATFORMS = [Platform.SWITCH, Platform.BUTTON]
CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)


async def async_setup(hass: HomeAssistant, config: dict) -> bool:
    """Karta „Sekurak” w lewym menu (czujniki, czuwanie)."""
    from .panel import async_setup_panel

    await async_setup_panel(hass)
    return True


def build_webhook_url(hass: HomeAssistant, external: str, webhook_id: str) -> str:
    """Adres webhooka z zewnątrz: pole z opcji, inaczej z HA (zewnętrzny / Nabu Casa),
    a w ostateczności lokalny."""
    path = webhook.async_generate_path(webhook_id)
    if external:
        return f"{external.rstrip('/')}{path}"
    try:
        return get_url(hass, allow_internal=False, allow_cloud=True, prefer_external=True) + path
    except NoURLAvailableError:
        return webhook.async_generate_url(hass, webhook_id)


class Sekurak:
    """Stan alarmów jednej konfiguracji + wysyłka do telefonu."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass = hass
        self.entry = entry
        self.active: dict[str, bool] = {t: False for t in TYPES}
        self._unsubs: list = []
        # jednorazowy webhook: losowe ID na czas alarmu, kasowane po wyciszeniu / max 15 min
        self.one_time_id: str | None = None
        self._expire_unsub = None
        # czuwanie alarmu: do kiedy trwa czas na wyjście (monotonic), zaplanowane sprawdzenie
        self._in_exit: bool = False
        self._exit_unsub = None

    @property
    def sensors(self) -> list[dict]:
        return list(self.options.get(CONF_SENSORS, []) or [])

    @property
    def options(self) -> dict[str, Any]:
        return {**self.entry.data, **self.entry.options}

    @property
    def external_base(self) -> str:
        return fix_external(self.options.get(CONF_EXTERNAL, DEFAULT_EXTERNAL))

    # ---------- jednorazowy webhook ----------
    @callback
    def _ensure_one_time(self) -> str:
        """Losowe ID webhooka na ten alarm. Wspólne dla typów, które dzwonią naraz."""
        if self.one_time_id is None:
            wid = f"sekurak_{secrets.token_urlsafe(24)}"
            webhook.async_register(
                self.hass, DOMAIN, f"Sekurak ({self.entry.title})", wid,
                self.handle_webhook, allowed_methods=["POST"],
            )
            self.one_time_id = wid
            self._expire_unsub = async_call_later(self.hass, ONE_TIME_TTL, self._expire)
            _LOGGER.info("Sekurak: nowy jednorazowy webhook (ważny %s s)", ONE_TIME_TTL)
        return self.one_time_id

    @callback
    def _drop_one_time(self) -> None:
        if self._expire_unsub:
            self._expire_unsub()
            self._expire_unsub = None
        if self.one_time_id:
            webhook.async_unregister(self.hass, self.one_time_id)
            _LOGGER.info("Sekurak: jednorazowy webhook skasowany")
            self.one_time_id = None

    @callback
    def _expire(self, _now) -> None:
        self._expire_unsub = None
        self._drop_one_time()

    def url_for(self, wid: str) -> str:
        return build_webhook_url(self.hass, self.external_base, wid)

    def url_local_for(self, wid: str) -> str:
        return webhook.async_generate_url(self.hass, wid, allow_external=False)

    # ---------- wysyłka do telefonu ----------
    async def _send(
        self, action: str, typ: str, wid: str | None = None, source: str | None = None
    ) -> None:
        notify = self.options[CONF_NOTIFY].removeprefix("notify.")
        parts = [f"typ:{typ}"]
        if source:
            # nazwa czujnika wpisana w Konfiguruj – apka pokazuje ją na ekranie alarmu
            parts.append(f"czujnik:{quote(source, safe='')}:urlencoded")
        if wid:
            # jednorazowy adres wyciszenia jedzie razem z alarmem
            url = self.url_for(wid)
            parts.append(f"webhook:{quote(url, safe='')}:urlencoded")
            try:
                local = self.url_local_for(wid)
                if local != url:
                    parts.append(f"webhook2:{quote(local, safe='')}:urlencoded")
            except Exception:  # noqa: BLE001 – brak adresu lokalnego
                pass
        secret = (self.options.get(CONF_SECRET) or "").strip()
        if secret:
            parts.append(f"secret:{secret}")
        await self.hass.services.async_call(
            "notify",
            notify,
            {
                "message": "command_broadcast_intent",
                "data": {
                    "intent_action": action,
                    "intent_package_name": PACKAGE,
                    "intent_extras": ",".join(parts),
                    "priority": "high",
                    "ttl": 0,
                },
            },
            blocking=False,
        )

    async def async_start(self, typ: str, source: str | None = None) -> None:
        """Włącz alarm typu. START idzie 2x co 3 s (jak w starej automatyzacji),
        powtórka leci w tle, żeby nie blokować przycisku/usługi.
        Gdy typ już dzwoni, a odpalił kolejny czujnik – jeden START z jego nazwą
        (apka dopisze nazwę, nie zacznie dzwonić od nowa)."""
        if self.active.get(typ):
            if source and self.one_time_id:
                await self._send(ACTION_START, typ, self.one_time_id, source)
            return
        self._set(typ, True)
        await self._send(ACTION_START, typ, self._ensure_one_time(), source)
        self.hass.async_create_background_task(
            self._repeat_start(typ, source), f"sekurak_repeat_{typ}"
        )

    async def _repeat_start(self, typ: str, source: str | None) -> None:
        await asyncio.sleep(3)
        if self.active.get(typ) and self.one_time_id:
            await self._send(ACTION_START, typ, self.one_time_id, source)

    async def async_stop(self, typ: str, notify_phone: bool = True) -> None:
        """Wyłącz alarm typu (z HA albo po wyciszeniu na telefonie)."""
        was = self.active.get(typ)
        self._set(typ, False)
        if was and notify_phone:
            await self._send(ACTION_STOP, typ)

    def _set(self, typ: str, value: bool) -> None:
        self.active[typ] = value
        if not any(self.active.values()):
            self._drop_one_time()
        async_dispatcher_send(self.hass, SIGNAL_UPDATE.format(self.entry.entry_id))

    # ---------- encje wyzwalające ----------
    # ---------- czuwanie alarmu ----------
    @property
    def arm_entity(self) -> str | None:
        return self.options.get(CONF_ARM_ENTITY) or None

    @property
    def exit_delay(self) -> int:
        try:
            return int(self.options.get(CONF_EXIT_DELAY, DEFAULT_EXIT_DELAY))
        except (TypeError, ValueError):
            return DEFAULT_EXIT_DELAY

    def alarm_allowed(self) -> bool:
        """Czujniki typu Alarm działają tylko przy uzbrojonym czuwaniu, po czasie na wyjście.
        Brak encji czuwania = działają zawsze."""
        ent = self.arm_entity
        if not ent:
            return True
        st = self.hass.states.get(ent)
        if not is_armed(st.state if st else None):
            return False
        return not self._in_exit

    @callback
    def _cancel_exit(self) -> None:
        self._in_exit = False
        if self._exit_unsub:
            self._exit_unsub()
            self._exit_unsub = None

    @callback
    def _armed(self) -> None:
        """Uzbrojono: odlicz czas na wyjście, potem sprawdź wszystkie czujniki Alarmu."""
        self._cancel_exit()
        delay = max(0, self.exit_delay)
        self._in_exit = delay > 0
        _LOGGER.info("Sekurak: uzbrojono, czas na wyjście %s s", delay)
        self._exit_unsub = async_call_later(self.hass, delay, self._check_after_exit)

    @callback
    def _check_after_exit(self, _now) -> None:
        self._exit_unsub = None
        self._in_exit = False
        for s in self.sensors:
            if s.get("typ") != "alarm":
                continue
            st = self.hass.states.get(s["entity_id"])
            if sensor_active(st.state if st else None, bool(s.get("invert"))):
                _LOGGER.info("Sekurak: po uzbrojeniu %s w złym stanie -> alarm", s["entity_id"])
                self.hass.async_create_task(self.async_start("alarm", s.get("name")))

    # ---------- czujniki ----------
    @callback
    def async_listen(self) -> None:
        by_entity: dict[str, list[dict]] = {}
        for s in self.sensors:
            if s.get("entity_id") and s.get("typ") in TYPES:
                by_entity.setdefault(s["entity_id"], []).append(s)

        @callback
        def _changed(event: Event) -> None:
            new = event.data.get("new_state")
            old = event.data.get("old_state")
            for s in by_entity.get(event.data["entity_id"], []):
                inv = bool(s.get("invert"))
                if not sensor_active(new.state if new else None, inv):
                    continue
                if old is not None and sensor_active(old.state, inv):
                    continue  # tylko zmiana na stan alarmowy
                typ = s["typ"]
                if typ == "alarm" and not self.alarm_allowed():
                    continue
                _LOGGER.info("Sekurak: %s (%s) -> %s", s.get("name"), s["entity_id"], typ)
                self.hass.async_create_task(self.async_start(typ, s.get("name")))

        if by_entity:
            self._unsubs.append(
                async_track_state_change_event(self.hass, list(by_entity), _changed)
            )

        arm = self.arm_entity
        if arm:
            @callback
            def _arm_changed(event: Event) -> None:
                new = event.data.get("new_state")
                old = event.data.get("old_state")
                now_armed = is_armed(new.state if new else None)
                was_armed = is_armed(old.state if old else None)
                if now_armed and not was_armed:
                    self._armed()
                elif not now_armed:
                    self._cancel_exit()

            self._unsubs.append(async_track_state_change_event(self.hass, [arm], _arm_changed))
            st = self.hass.states.get(arm)
            if is_armed(st.state if st else None):
                # już uzbrojone przy starcie integracji – bez czasu na wyjście
                self._in_exit = False

    # ---------- webhook z telefonu (wyciszenie) ----------
    async def handle_webhook(
        self, hass: HomeAssistant, webhook_id: str, request: web.Request
    ) -> web.Response:
        try:
            body = await request.json()
        except ValueError:
            body = {}
        typy = [t for t in (body.get("typy") or []) if t in TYPES]
        _LOGGER.info("Sekurak: webhook wyciszenia, typy: %s", typy or "test")
        for typ in typy:
            # telefon już wyciszył – nie wysyłamy mu STOP
            await self.async_stop(typ, notify_phone=False)
            await self._turn_off_sources(typ)
        if typy:
            # adres był jednorazowy – po wyciszeniu przestaje działać
            self._drop_one_time()
        return web.json_response({"sekurak": "ok", "wyciszone": typy})

    async def _turn_off_sources(self, typ: str) -> None:
        """Gasi przełączniki (input_boolean/switch), które odpaliły ten typ, + stare z YAML.
        Odwróconych nie ruszamy (dla nich "spoczynek" to on)."""
        ents = [
            s["entity_id"] for s in self.sensors
            if s.get("typ") == typ and not s.get("invert") and s.get("entity_id")
        ]
        if typ in LEGACY_HELPERS:
            ents.append(LEGACY_HELPERS[typ])
        for ent in dict.fromkeys(ents):
            domain = ent.split(".", 1)[0]
            st = self.hass.states.get(ent)
            if domain not in TURN_OFF_DOMAINS or st is None or st.state != "on":
                continue
            _LOGGER.info("Sekurak: wyciszono %s – gaszę %s", typ, ent)
            await self.hass.services.async_call(
                domain, "turn_off", {"entity_id": ent}, blocking=False
            )

    @callback
    def async_unload(self) -> None:
        self._drop_one_time()
        self._cancel_exit()
        for u in self._unsubs:
            u()
        self._unsubs.clear()


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    _migrate_sensors(hass, entry)
    sek = Sekurak(hass, entry)
    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = sek

    # Brak stałego webhooka – każdy alarm dostaje własny, losowy, jednorazowy.
    sek.async_listen()
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    entry.async_on_unload(entry.add_update_listener(_reload))
    return True


def _migrate_sensors(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Stare pola "encje_<typ>" -> lista czujników (nazwa = nazwa encji z HA)."""
    opts = dict(entry.options)
    if CONF_SENSORS in opts:
        return
    sensors: list[dict] = []
    for typ in TRIGGER_TYPES:
        for ent in opts.get(conf_entities(typ), []) or []:
            st = hass.states.get(ent)
            name = (st.name if st else None) or ent
            sensors.append(
                {"id": uuid4().hex, "entity_id": ent, "name": name, "typ": typ, "invert": False}
            )
    if not sensors and not any(k.startswith("encje_") for k in opts):
        return
    for typ in TRIGGER_TYPES:
        opts.pop(conf_entities(typ), None)
    opts[CONF_SENSORS] = sensors
    _LOGGER.info("Sekurak: przeniesiono %s czujników do nowej listy", len(sensors))
    hass.config_entries.async_update_entry(entry, options=opts)


async def _reload(hass: HomeAssistant, entry: ConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if ok:
        sek: Sekurak = hass.data[DOMAIN].pop(entry.entry_id)
        sek.async_unload()
    return ok
