"""Sekurak – alarmy na telefon przez apkę Sekurak i Companion App."""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from aiohttp import web

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import Event, HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.event import async_track_state_change_event

from .const import (
    ACTION_START,
    ACTION_STOP,
    ACTIVE_STATES,
    CONF_NOTIFY,
    CONF_SECRET,
    CONF_WEBHOOK_ID,
    DOMAIN,
    PACKAGE,
    SIGNAL_UPDATE,
    TRIGGER_TYPES,
    TYPES,
    conf_entities,
)

_LOGGER = logging.getLogger(__name__)
PLATFORMS = [Platform.SWITCH, Platform.BUTTON]


class Sekurak:
    """Stan alarmów jednej konfiguracji + wysyłka do telefonu."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass = hass
        self.entry = entry
        self.active: dict[str, bool] = {t: False for t in TYPES}
        self._unsubs: list = []

    @property
    def options(self) -> dict[str, Any]:
        return {**self.entry.data, **self.entry.options}

    @property
    def webhook_url(self) -> str:
        return webhook.async_generate_url(self.hass, self.entry.data[CONF_WEBHOOK_ID])

    # ---------- wysyłka do telefonu ----------
    async def _send(self, action: str, typ: str) -> None:
        notify = self.options[CONF_NOTIFY].removeprefix("notify.")
        extras = f"typ:{typ}"
        secret = (self.options.get(CONF_SECRET) or "").strip()
        if secret:
            extras += f",secret:{secret}"
        await self.hass.services.async_call(
            "notify",
            notify,
            {
                "message": "command_broadcast_intent",
                "data": {
                    "intent_action": action,
                    "intent_package_name": PACKAGE,
                    "intent_extras": extras,
                    "priority": "high",
                    "ttl": 0,
                },
            },
            blocking=False,
        )

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
        typy = body.get("typy") or []
        for typ in typy:
            if typ in TYPES:
                # telefon już wyciszył – STOP i tak nie szkodzi, ale go oszczędzamy
                await self.async_stop(typ, notify_phone=False)
        return web.Response(status=200, text="ok")

    @callback
    def async_unload(self) -> None:
        for u in self._unsubs:
            u()
        self._unsubs.clear()


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    sek = Sekurak(hass, entry)
    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = sek

    webhook.async_register(
        hass,
        DOMAIN,
        f"Sekurak ({entry.title})",
        entry.data[CONF_WEBHOOK_ID],
        sek.handle_webhook,
        allowed_methods=["POST"],
    )
    sek.async_listen()
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
        webhook.async_unregister(hass, entry.data[CONF_WEBHOOK_ID])
    return ok
