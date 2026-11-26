"""Przełącznik na każdy typ alarmu: ON = alarm dzwoni na telefonie."""

from __future__ import annotations

from typing import Any

from homeassistant.components.switch import SwitchEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import Sekurak
from .const import DOMAIN, ICONS, SIGNAL_UPDATE, TYPES


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    sek: Sekurak = hass.data[DOMAIN][entry.entry_id]
    async_add_entities(SekurakSwitch(sek, typ) for typ in TYPES)


def device_info(sek: Sekurak) -> DeviceInfo:
    return DeviceInfo(
        identifiers={(DOMAIN, sek.entry.entry_id)},
        name=sek.entry.title,
        manufacturer="voltronicpl",
        model="Sekurak",
    )


class SekurakSwitch(SwitchEntity):
    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, sek: Sekurak, typ: str) -> None:
        self._sek = sek
        self._typ = typ
        self._attr_name = f"Alarm {TYPES[typ]}" if typ != "alarm" else "Alarm"
        self._attr_unique_id = f"{sek.entry.entry_id}_{typ}"
        self._attr_icon = ICONS[typ]
        self._attr_device_info = device_info(sek)

    @property
    def is_on(self) -> bool:
        return self._sek.active.get(self._typ, False)

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return {"typ": self._typ}

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._sek.async_start(self._typ)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._sek.async_stop(self._typ)

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                SIGNAL_UPDATE.format(self._sek.entry.entry_id),
                self._update,
            )
        )

    @callback
    def _update(self) -> None:
        self.async_write_ha_state()
