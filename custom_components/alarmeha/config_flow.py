"""Config flow do AlarmeHA: um celular (pessoa) por entrada."""

from __future__ import annotations

import json
import secrets
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.core import callback
from homeassistant.helpers.network import NoURLAvailableError, get_url
from homeassistant.helpers.selector import (
    SelectSelector,
    SelectSelectorConfig,
    SelectSelectorMode,
    TextSelector,
    TextSelectorConfig,
    TextSelectorType,
)

from .const import (
    CONF_HA_URL,
    CONF_NOTIFY_SERVICE,
    CONF_PACKAGE,
    CONF_PERSON,
    CONF_TOKEN,
    CONF_WEBHOOK_ID,
    DOMAIN,
    PACKAGES,
)

TOKEN_SELECTOR = TextSelector(TextSelectorConfig(type=TextSelectorType.PASSWORD))


def _qr_data_uri(text: str) -> str:
    try:
        import segno  # noqa: PLC0415

        return segno.make(text, error="m").svg_data_uri(scale=6, border=2, dark="#000000", light="#ffffff")
    except Exception:  # noqa: BLE001
        return ""


def _valid_token(token: str) -> bool:
    return 20 <= len(token) <= 128 and all(c.isalnum() or c in "-_" for c in token)


class AlarmeHaConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    def __init__(self) -> None:
        self._data: dict[str, Any] = {}

    def _notify_services(self) -> list[str]:
        services = self.hass.services.async_services().get("notify", {})
        return sorted(f"notify.{name}" for name in services if name.startswith("mobile_app_"))

    def _default_url(self) -> str:
        try:
            return get_url(self.hass, allow_internal=False, prefer_external=True)
        except NoURLAvailableError:
            return ""

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        notify = self._notify_services()
        if not notify:
            return self.async_abort(reason="no_mobile_app")

        if user_input is not None:
            url = user_input[CONF_HA_URL].strip().rstrip("/")
            if not url.startswith(("https://", "http://")):
                errors[CONF_HA_URL] = "invalid_url"
            else:
                await self.async_set_unique_id(user_input[CONF_NOTIFY_SERVICE])
                self._abort_if_unique_id_configured()
                self._data = {
                    CONF_PERSON: user_input[CONF_PERSON].strip(),
                    CONF_NOTIFY_SERVICE: user_input[CONF_NOTIFY_SERVICE],
                    CONF_PACKAGE: user_input[CONF_PACKAGE],
                    CONF_HA_URL: url,
                    CONF_WEBHOOK_ID: "alarmeha_" + secrets.token_urlsafe(24),
                }
                return await self.async_step_pair()

        schema = vol.Schema(
            {
                vol.Required(CONF_PERSON): str,
                vol.Required(CONF_NOTIFY_SERVICE): SelectSelector(
                    SelectSelectorConfig(options=notify, mode=SelectSelectorMode.DROPDOWN)
                ),
                vol.Required(CONF_PACKAGE, default=PACKAGES[0]): SelectSelector(
                    SelectSelectorConfig(options=PACKAGES, mode=SelectSelectorMode.DROPDOWN)
                ),
                vol.Required(CONF_HA_URL, default=self._default_url()): str,
            }
        )
        return self.async_show_form(step_id="user", data_schema=schema, errors=errors)

    async def async_step_pair(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            token = user_input[CONF_TOKEN].strip()
            if _valid_token(token):
                return self.async_create_entry(
                    title=self._data[CONF_PERSON], data={**self._data, CONF_TOKEN: token}
                )
            errors[CONF_TOKEN] = "invalid_token"

        pairing = json.dumps(
            {
                "v": 1,
                "url": self._data[CONF_HA_URL],
                "webhook": self._data[CONF_WEBHOOK_ID],
                "nome": self._data[CONF_PERSON],
            },
            separators=(",", ":"),
            ensure_ascii=False,
        )
        qr = _qr_data_uri(pairing)
        return self.async_show_form(
            step_id="pair",
            data_schema=vol.Schema({vol.Required(CONF_TOKEN): TOKEN_SELECTOR}),
            errors=errors,
            description_placeholders={
                "qr": f"![QR]({qr})" if qr else "",
                "pairing": pairing,
            },
        )

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return AlarmeHaOptionsFlow()


class AlarmeHaOptionsFlow(OptionsFlow):
    """Troca de token, pacote ou URL (ex.: depois de 'Gerar novo token' no app)."""

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        entry = self.config_entry
        if user_input is not None:
            token = user_input.get(CONF_TOKEN, "").strip()
            url = user_input[CONF_HA_URL].strip().rstrip("/")
            if token and not _valid_token(token):
                errors[CONF_TOKEN] = "invalid_token"
            elif not url.startswith(("https://", "http://")):
                errors[CONF_HA_URL] = "invalid_url"
            else:
                data = {**entry.data, CONF_PACKAGE: user_input[CONF_PACKAGE], CONF_HA_URL: url}
                if token:
                    data[CONF_TOKEN] = token
                self.hass.config_entries.async_update_entry(entry, data=data)
                return self.async_create_entry(data={})

        pairing = json.dumps(
            {"v": 1, "url": entry.data[CONF_HA_URL], "webhook": entry.data[CONF_WEBHOOK_ID], "nome": entry.data[CONF_PERSON]},
            separators=(",", ":"),
            ensure_ascii=False,
        )
        qr = _qr_data_uri(pairing)
        schema = vol.Schema(
            {
                vol.Optional(CONF_TOKEN): TOKEN_SELECTOR,
                vol.Required(CONF_PACKAGE, default=entry.data[CONF_PACKAGE]): SelectSelector(
                    SelectSelectorConfig(options=PACKAGES, mode=SelectSelectorMode.DROPDOWN)
                ),
                vol.Required(CONF_HA_URL, default=entry.data[CONF_HA_URL]): str,
            }
        )
        return self.async_show_form(
            step_id="init",
            data_schema=schema,
            errors=errors,
            description_placeholders={"qr": f"![QR]({qr})" if qr else "", "pairing": pairing},
        )
