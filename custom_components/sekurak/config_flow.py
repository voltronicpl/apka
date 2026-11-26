"""Konfiguracja Sekurak w UI: telefon, lista czujników, czuwanie alarmu."""

from __future__ import annotations

import secrets
from uuid import uuid4
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry, ConfigFlow, OptionsFlow
from homeassistant.core import callback
from homeassistant.helpers import selector

from .const import (
    ARM_DOMAINS,
    CONF_ARM_ENTITY,
    CONF_EXIT_DELAY,
    CONF_SENSORS,
    DEFAULT_EXIT_DELAY,
    TYPES,
    CONF_EXTERNAL,
    CONF_NOTIFY,
    CONF_SECRET,
    CONF_WEBHOOK_ID,
    DEFAULT_EXTERNAL,
    DOMAIN,
    TRIGGER_DOMAINS,
    fix_external,
)


def _phones(hass) -> list[str]:
    return sorted(
        f"notify.{s}"
        for s in hass.services.async_services().get("notify", {})
        if s.startswith("mobile_app_")
    )


def _phone_selector(hass):
    phones = _phones(hass)
    if not phones:
        return str
    return selector.SelectSelector(
        selector.SelectSelectorConfig(options=phones, custom_value=True)
    )


class SekurakConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    async def async_step_user(self, user_input: dict[str, Any] | None = None):
        if user_input is not None:
            notify = user_input[CONF_NOTIFY]
            if not notify.startswith("notify."):
                notify = f"notify.{notify}"
            await self.async_set_unique_id(notify)
            self._abort_if_unique_id_configured()
            name = notify.removeprefix("notify.mobile_app_")
            return self.async_create_entry(
                title=f"Sekurak {name}",
                data={
                    CONF_NOTIFY: notify,
                    CONF_WEBHOOK_ID: f"sekurak_{secrets.token_hex(8)}",
                },
            )
        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {vol.Required(CONF_NOTIFY): _phone_selector(self.hass)}
            ),
        )

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return SekurakOptionsFlow()


class SekurakOptionsFlow(OptionsFlow):
    """Konfiguruj: menu – dodaj czujnik / edytuj czujnik / ustawienia."""

    def __init__(self) -> None:
        self._edit_id: str | None = None

    # ---- pomocnicze ----
    def _opts(self) -> dict[str, Any]:
        return dict(self.config_entry.options)

    def _sensors(self) -> list[dict]:
        return [dict(s) for s in self._opts().get(CONF_SENSORS, []) or []]

    def _save(self, **changes: Any):
        opts = self._opts()
        opts.update(changes)
        return self.async_create_entry(data=opts)

    def _sensor_schema(self, cur: dict | None, with_delete: bool) -> vol.Schema:
        cur = cur or {}
        type_sel = selector.SelectSelector(
            selector.SelectSelectorConfig(
                options=[selector.SelectOptionDict(value=k, label=v) for k, v in TYPES.items()],
                mode=selector.SelectSelectorMode.DROPDOWN,
            )
        )
        schema: dict = {
            vol.Required("entity_id", default=cur.get("entity_id", vol.UNDEFINED)): selector.EntitySelector(
                selector.EntitySelectorConfig(domain=TRIGGER_DOMAINS)
            ),
            vol.Required("name", default=cur.get("name", vol.UNDEFINED)): selector.TextSelector(),
            vol.Required("typ", default=cur.get("typ", "alarm")): type_sel,
            vol.Required("invert", default=bool(cur.get("invert", False))): selector.BooleanSelector(),
        }
        if with_delete:
            schema[vol.Required("usun", default=False)] = selector.BooleanSelector()
        return vol.Schema(schema)

    # ---- menu ----
    async def async_step_init(self, user_input: dict[str, Any] | None = None):
        menu = ["dodaj"]
        if self._sensors():
            menu.append("edytuj")
        menu.append("ustawienia")
        lines = [
            f"• {s.get('name')} ({s.get('entity_id')}) → {TYPES.get(s.get('typ'), s.get('typ'))}"
            + (", odwrócony" if s.get("invert") else "")
            for s in self._sensors()
        ]
        return self.async_show_menu(
            step_id="init",
            menu_options=menu,
            description_placeholders={"czujniki": "\n".join(lines) or "(brak)"},
        )

    async def async_step_dodaj(self, user_input: dict[str, Any] | None = None):
        if user_input is not None:
            sensors = self._sensors()
            sensors.append(
                {
                    "id": uuid4().hex,
                    "entity_id": user_input["entity_id"],
                    "name": user_input["name"].strip() or user_input["entity_id"],
                    "typ": user_input["typ"],
                    "invert": bool(user_input.get("invert")),
                }
            )
            return self._save(**{CONF_SENSORS: sensors})
        return self.async_show_form(step_id="dodaj", data_schema=self._sensor_schema(None, False))

    async def async_step_edytuj(self, user_input: dict[str, Any] | None = None):
        sensors = self._sensors()
        if user_input is not None:
            self._edit_id = user_input["czujnik"]
            return await self.async_step_edytuj_czujnik()
        opts = [
            selector.SelectOptionDict(
                value=s["id"],
                label=f"{s.get('name')} ({s.get('entity_id')}) → {TYPES.get(s.get('typ'), s.get('typ'))}",
            )
            for s in sensors
        ]
        return self.async_show_form(
            step_id="edytuj",
            data_schema=vol.Schema(
                {vol.Required("czujnik"): selector.SelectSelector(selector.SelectSelectorConfig(options=opts))}
            ),
        )

    async def async_step_edytuj_czujnik(self, user_input: dict[str, Any] | None = None):
        sensors = self._sensors()
        cur = next((s for s in sensors if s.get("id") == self._edit_id), None)
        if cur is None:
            return await self.async_step_init()
        if user_input is not None:
            if user_input.get("usun"):
                sensors = [s for s in sensors if s.get("id") != self._edit_id]
            else:
                cur.update(
                    entity_id=user_input["entity_id"],
                    name=user_input["name"].strip() or user_input["entity_id"],
                    typ=user_input["typ"],
                    invert=bool(user_input.get("invert")),
                )
            return self._save(**{CONF_SENSORS: sensors})
        return self.async_show_form(
            step_id="edytuj_czujnik", data_schema=self._sensor_schema(cur, True)
        )

    async def async_step_ustawienia(self, user_input: dict[str, Any] | None = None):
        opts = self._opts()
        if user_input is not None:
            changes = {
                CONF_EXIT_DELAY: int(user_input.get(CONF_EXIT_DELAY, DEFAULT_EXIT_DELAY)),
                CONF_EXTERNAL: user_input.get(CONF_EXTERNAL, ""),
                CONF_SECRET: user_input.get(CONF_SECRET, ""),
                CONF_ARM_ENTITY: user_input.get(CONF_ARM_ENTITY) or "",
            }
            return self._save(**changes)
        from . import build_webhook_url

        external = fix_external(opts.get(CONF_EXTERNAL, DEFAULT_EXTERNAL))
        schema = {
            vol.Optional(
                CONF_ARM_ENTITY, description={"suggested_value": opts.get(CONF_ARM_ENTITY) or None}
            ): selector.EntitySelector(selector.EntitySelectorConfig(domain=ARM_DOMAINS)),
            vol.Required(
                CONF_EXIT_DELAY, default=int(opts.get(CONF_EXIT_DELAY, DEFAULT_EXIT_DELAY))
            ): selector.NumberSelector(
                selector.NumberSelectorConfig(
                    min=0, max=600, step=1, unit_of_measurement="s",
                    mode=selector.NumberSelectorMode.BOX,
                )
            ),
            vol.Optional(CONF_EXTERNAL, default=external): selector.TextSelector(),
            vol.Optional(CONF_SECRET, default=opts.get(CONF_SECRET, "")): selector.TextSelector(),
        }
        return self.async_show_form(
            step_id="ustawienia",
            data_schema=vol.Schema(schema),
            description_placeholders={
                "webhook_url": build_webhook_url(self.hass, external, "sekurak_<losowy>")
            },
        )
