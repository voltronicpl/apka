"""Konfiguracja Sekurak w UI: telefon + encje wyzwalające każdy typ."""

from __future__ import annotations

import secrets
from typing import Any

import voluptuous as vol

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry, ConfigFlow, OptionsFlow
from homeassistant.core import callback
from homeassistant.helpers import selector

from .const import (
    CONF_EXTERNAL,
    CONF_EXTRA_WEBHOOK,
    CONF_NOTIFY,
    CONF_SECRET,
    CONF_WEBHOOK_ID,
    DEFAULT_EXTERNAL,
    DEFAULT_EXTRA_WEBHOOK,
    DOMAIN,
    TRIGGER_DOMAINS,
    TRIGGER_TYPES,
    conf_entities,
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
    async def async_step_init(self, user_input: dict[str, Any] | None = None):
        if user_input is not None:
            return self.async_create_entry(data=user_input)

        opts = self.config_entry.options
        ent_sel = selector.EntitySelector(
            selector.EntitySelectorConfig(domain=TRIGGER_DOMAINS, multiple=True)
        )
        schema: dict = {}
        for typ in TRIGGER_TYPES:
            key = conf_entities(typ)
            schema[vol.Optional(key, default=opts.get(key, []))] = ent_sel
        schema[
            vol.Optional(CONF_SECRET, default=opts.get(CONF_SECRET, ""))
        ] = selector.TextSelector()
        schema[
            vol.Optional(CONF_EXTERNAL, default=opts.get(CONF_EXTERNAL, DEFAULT_EXTERNAL))
        ] = selector.TextSelector()
        schema[
            vol.Optional(
                CONF_EXTRA_WEBHOOK,
                default=opts.get(CONF_EXTRA_WEBHOOK, DEFAULT_EXTRA_WEBHOOK),
            )
        ] = selector.TextSelector()

        from . import build_webhook_url

        external = (opts.get(CONF_EXTERNAL, DEFAULT_EXTERNAL) or "").strip()
        wid = self.config_entry.data[CONF_WEBHOOK_ID]
        url = build_webhook_url(self.hass, external, wid)
        local = webhook.async_generate_url(self.hass, wid, allow_external=False)
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(schema),
            description_placeholders={
                "webhook_url": url,
                "webhook_local": local,
            },
        )
