"""Config flow do AlarmeHA: uma única entrada, criada pelo admin."""

from __future__ import annotations

from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.core import callback
from homeassistant.helpers.network import NoURLAvailableError, get_url

from .const import CONF_HA_URL, DOMAIN


def _clean_url(value: str) -> str | None:
    value = value.strip().rstrip("/")
    return value if value.startswith(("http://", "https://")) else None


class AlarmeHaConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 2

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        await self.async_set_unique_id(DOMAIN)
        self._abort_if_unique_id_configured()
        errors: dict[str, str] = {}
        if user_input is not None:
            if url := _clean_url(user_input[CONF_HA_URL]):
                return self.async_create_entry(title="AlarmeHA", data={CONF_HA_URL: url})
            errors[CONF_HA_URL] = "invalid_url"
        try:
            default = get_url(self.hass, allow_internal=False, prefer_external=True)
        except NoURLAvailableError:
            default = ""
        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema({vol.Required(CONF_HA_URL, default=default): str}),
            errors=errors,
        )

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return AlarmeHaOptionsFlow()


class AlarmeHaOptionsFlow(OptionsFlow):
    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        current = self.config_entry.options.get(CONF_HA_URL) or self.config_entry.data.get(CONF_HA_URL, "")
        if user_input is not None:
            if url := _clean_url(user_input[CONF_HA_URL]):
                return self.async_create_entry(data={CONF_HA_URL: url})
            errors[CONF_HA_URL] = "invalid_url"
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema({vol.Required(CONF_HA_URL, default=current): str}),
            errors=errors,
        )
