"""Przycisk "Gdzie jest fon" – telefon zaczyna dzwonić."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import Sekurak
from .const import DOMAIN, ICONS, MANUAL_SOURCE
from .switch import device_info


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    async_add_entities([SzukajButton(hass.data[DOMAIN][entry.entry_id])])


class SzukajButton(ButtonEntity):
    _attr_has_entity_name = True
    _attr_name = "Gdzie jest fon"
    _attr_icon = ICONS["szukaj"]

    def __init__(self, sek: Sekurak) -> None:
        self._sek = sek
        self._attr_unique_id = f"{sek.entry.entry_id}_szukaj_button"
        self._attr_device_info = device_info(sek)

    async def async_press(self) -> None:
        await self._sek.async_start("szukaj", MANUAL_SOURCE)
