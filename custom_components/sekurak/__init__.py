"""Sekurak – alarmy na telefon przez apkę Sekurak i Companion App."""

from __future__ import annotations

import asyncio
import logging
from typing import Any
from urllib.parse import quote

from aiohttp import web

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import Event, HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.event import async_track_state_change_event
from homeassistant.helpers.network import NoURLAvailableError, get_url
from homeassistant.helpers.start import async_at_started

from .const import (
    ACTION_CONFIG,
    ACTION_START,
    ACTION_STOP,
    ACTIVE_STATES,
    CONF_EXTERNAL,
    CONF_EXTRA_WEBHOOK,
    CONF_NOTIFY,
    CONF_SECRET,
    CONF_WEBHOOK_ID,
    DEFAULT_EXTERNAL,
    DEFAULT_EXTRA_WEBHOOK,
    DOMAIN,
    LEGACY_HELPERS,
    TURN_OFF_DOMAINS,
    PACKAGE,
    SIGNAL_UPDATE,
    TRIGGER_TYPES,
    TYPES,
    conf_entities,
)

_LOGGER = logging.getLogger(__name__)
PLATFORMS = [Platform.SWITCH, Platform.BUTTON]


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
        self.registered: list[str] = []

    @property
    def options(self) -> dict[str, Any]:
        return {**self.entry.data, **self.entry.options}

    @property
    def webhook_ids(self) -> list[str]:
        """Główne ID (losowe) + dodatkowe (domyślnie stare z YAML)."""
        ids = [self.entry.data[CONF_WEBHOOK_ID]]
        extra = (self.options.get(CONF_EXTRA_WEBHOOK, DEFAULT_EXTRA_WEBHOOK) or "").strip()
        if extra and extra not in ids:
            ids.append(extra)
        return ids

    @property
    def external_base(self) -> str:
        return (self.options.get(CONF_EXTERNAL, DEFAULT_EXTERNAL) or "").strip().rstrip("/")

    @property
    def webhook_url(self) -> str:
        return build_webhook_url(self.hass, self.external_base, self.entry.data[CONF_WEBHOOK_ID])

    @property
    def webhook_url_local(self) -> str:
        return webhook.async_generate_url(
            self.hass, self.entry.data[CONF_WEBHOOK_ID], allow_external=False
        )

    # ---------- wysyłka do telefonu ----------
    async def _send(self, action: str, typ: str | None = None) -> None:
        notify = self.options[CONF_NOTIFY].removeprefix("notify.")
        parts = []
        if typ:
            parts.append(f"typ:{typ}")
        # adres webhooka jedzie do telefonu z każdym sygnałem – apka sama go zapamiętuje
        parts.append(f"webhook:{quote(self.webhook_url, safe='')}:urlencoded")
        # zapas: adres lokalny (działa w domu, gdy zewnętrzny nie przejdzie)
        try:
            local = self.webhook_url_local
            if local != self.webhook_url:
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

    async def async_send_config(self) -> None:
        """Wyślij telefonowi sam adres webhooka (po starcie HA / zmianie opcji)."""
        try:
            await self._send(ACTION_CONFIG)
            _LOGGER.info("Sekurak: wysłano adres webhooka do telefonu: %s", self.webhook_url)
        except Exception as err:  # noqa: BLE001 – brak usługi notify itp.
            _LOGGER.warning("Sekurak: nie udało się wysłać adresu webhooka: %s", err)

    async def async_start(self, typ: str) -> None:
        """Włącz alarm typu. START idzie 2x co 3 s (jak w starej automatyzacji),
        powtórka leci w tle, żeby nie blokować przycisku/usługi."""
        if self.active.get(typ):
            return
        self._set(typ, True)
        await self._send(ACTION_START, typ)
        self.hass.async_create_background_task(
            self._repeat_start(typ), f"sekurak_repeat_{typ}"
        )

    async def _repeat_start(self, typ: str) -> None:
        await asyncio.sleep(3)
        if self.active.get(typ):
            await self._send(ACTION_START, typ)

    async def async_stop(self, typ: str, notify_phone: bool = True) -> None:
        """Wyłącz alarm typu (z HA albo po wyciszeniu na telefonie)."""
        was = self.active.get(typ)
        self._set(typ, False)
        if was and notify_phone:
            await self._send(ACTION_STOP, typ)

    def _set(self, typ: str, value: bool) -> None:
        self.active[typ] = value
        async_dispatcher_send(self.hass, SIGNAL_UPDATE.format(self.entry.entry_id))

    # ---------- encje wyzwalające ----------
    @callback
    def async_listen(self) -> None:
        by_entity: dict[str, list[str]] = {}
        for typ in TRIGGER_TYPES:
            for ent in self.options.get(conf_entities(typ), []) or []:
                by_entity.setdefault(ent, []).append(typ)
        if not by_entity:
            return

        @callback
        def _changed(event: Event) -> None:
            new = event.data.get("new_state")
            old = event.data.get("old_state")
            if new is None or new.state not in ACTIVE_STATES:
                return
            if old is not None and old.state in ACTIVE_STATES:
                return
            for typ in by_entity.get(event.data["entity_id"], []):
                _LOGGER.info("Sekurak: %s -> %s", event.data["entity_id"], typ)
                self.hass.async_create_task(self.async_start(typ))

        self._unsubs.append(
            async_track_state_change_event(self.hass, list(by_entity), _changed)
        )

    # ---------- webhook z telefonu (wyciszenie) ----------
    async def handle_webhook(
        self, hass: HomeAssistant, webhook_id: str, request: web.Request
    ) -> web.Response:
        try:
            body = await request.json()
        except ValueError:
            body = {}
        typy = [t for t in (body.get("typy") or []) if t in TYPES]
        _LOGGER.info("Sekurak: webhook %s, wyciszone typy: %s", webhook_id, typy or "test")
        for typ in typy:
            # telefon już wyciszył – nie wysyłamy mu STOP
            await self.async_stop(typ, notify_phone=False)
            await self._turn_off_sources(typ)
        return web.json_response({"sekurak": "ok", "wyciszone": typy})

    async def _turn_off_sources(self, typ: str) -> None:
        """Gasi przełączniki, które odpaliły ten typ (wybrane w opcjach + stare z YAML)."""
        ents = list(self.options.get(conf_entities(typ), []) or [])
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
        for u in self._unsubs:
            u()
        self._unsubs.clear()


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    sek = Sekurak(hass, entry)
    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = sek

    registered: list[str] = []
    for wid in sek.webhook_ids:
        try:
            webhook.async_register(
                hass,
                DOMAIN,
                f"Sekurak ({entry.title})",
                wid,
                sek.handle_webhook,
                allowed_methods=["POST"],
            )
            registered.append(wid)
        except ValueError:
            _LOGGER.warning(
                "Sekurak: webhook %s jest już zajęty (np. przez starą automatyzację YAML) – pomijam",
                wid,
            )
    sek.registered = registered
    sek.async_listen()

    async def _started(_hass: HomeAssistant) -> None:
        await sek.async_send_config()

    entry.async_on_unload(async_at_started(hass, _started))
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    entry.async_on_unload(entry.add_update_listener(_reload))
    return True


async def _reload(hass: HomeAssistant, entry: ConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if ok:
        sek: Sekurak = hass.data[DOMAIN].pop(entry.entry_id)
        sek.async_unload()
        for wid in sek.registered:
            webhook.async_unregister(hass, wid)
    return ok
