"""Integração AlarmeHA: controla os alarmes do app AlarmeHA pelo Home Assistant."""

from __future__ import annotations

from datetime import timedelta
import json
import logging
from typing import Any

from aiohttp import web
import voluptuous as vol

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry, ConfigEntryState
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, ServiceCall, ServiceResponse, SupportsResponse
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import config_validation as cv, device_registry as dr
from homeassistant.helpers.event import async_call_later, async_track_time_interval
from homeassistant.helpers.typing import ConfigType

from .const import CONF_PERSON, CONF_WEBHOOK_ID, DEFAULT_TIMEOUT, DOMAIN, HEARTBEAT_HOURS, OPS
from .phone import AlarmeHaPhone

_LOGGER = logging.getLogger(__name__)

PLATFORMS = [Platform.BINARY_SENSOR, Platform.SENSOR]
CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)

type AlarmeHaConfigEntry = ConfigEntry[AlarmeHaPhone]

REF = vol.All(cv.string, vol.Match(r"^[a-z0-9_-]{1,64}$"))
REF_OR_ALL = vol.Any("*", REF)
TARGET = {
    vol.Optional("config_entry_id"): cv.string,
    vol.Optional("device_id"): cv.string,
    vol.Optional("timeout", default=DEFAULT_TIMEOUT): vol.All(vol.Coerce(int), vol.Range(min=10, max=300)),
}
HORA = vol.All(vol.Coerce(int), vol.Range(min=0, max=23))
MINUTO = vol.All(vol.Coerce(int), vol.Range(min=0, max=59))
DIAS = vol.All(vol.Coerce(int), vol.Any(-2, -1, vol.Range(min=1, max=127)))
EXTRA_FIELDS = {
    vol.Optional("rotulo"): vol.All(cv.string, vol.Length(max=64)),
    vol.Optional("ativo"): cv.boolean,
    vol.Optional("vibrar"): cv.boolean,
    vol.Optional("unico"): cv.boolean,
}
SCHEMAS = {
    "criar": vol.Schema(
        {**TARGET, **EXTRA_FIELDS, vol.Required("ref"): REF,
         vol.Required("hora"): HORA, vol.Required("minuto"): MINUTO, vol.Required("dias"): DIAS}
    ),
    "editar": vol.Schema(
        {**TARGET, **EXTRA_FIELDS, vol.Required("ref"): REF,
         vol.Optional("hora"): HORA, vol.Optional("minuto"): MINUTO, vol.Optional("dias"): DIAS}
    ),
    "ligar": vol.Schema({**TARGET, vol.Required("ref"): REF_OR_ALL}),
    "desligar": vol.Schema({**TARGET, vol.Required("ref"): REF_OR_ALL}),
    "apagar": vol.Schema({**TARGET, vol.Required("ref"): REF_OR_ALL}),
    "listar": vol.Schema(TARGET),
}


def _resolve_phone(hass: HomeAssistant, call: ServiceCall) -> AlarmeHaPhone:
    entry_id = call.data.get("config_entry_id")
    if not entry_id and (device_id := call.data.get("device_id")):
        device = dr.async_get(hass).async_get(device_id)
        entry_id = next(iter(device.config_entries), None) if device else None
    entries = [e for e in hass.config_entries.async_entries(DOMAIN) if e.state is ConfigEntryState.LOADED]
    if not entry_id and len(entries) == 1:
        entry_id = entries[0].entry_id
    entry = hass.config_entries.async_get_entry(entry_id) if entry_id else None
    if entry is None or entry.domain != DOMAIN or entry not in entries:
        raise ServiceValidationError("Informe config_entry_id ou device_id de um celular AlarmeHA carregado")
    return entry.runtime_data


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Registra os serviços alarmeha.*."""

    async def handle(call: ServiceCall) -> ServiceResponse:
        phone = _resolve_phone(hass, call)
        fields = {k: v for k, v in call.data.items() if k not in ("config_entry_id", "device_id", "timeout")}
        result = await phone.async_command(call.service, fields, call.data["timeout"])
        return result if call.return_response else None

    for op in OPS:
        hass.services.async_register(DOMAIN, op, handle, schema=SCHEMAS[op], supports_response=SupportsResponse.OPTIONAL)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: AlarmeHaConfigEntry) -> bool:
    phone = AlarmeHaPhone(hass, entry)
    await phone.async_load()
    entry.runtime_data = phone

    async def handle_webhook(hass: HomeAssistant, webhook_id: str, request: web.Request) -> web.Response:
        try:
            payload: Any = await request.json(loads=json.loads)
        except ValueError:
            return web.Response(status=400)
        if isinstance(payload, dict):
            phone.handle_event(payload)
        return web.Response(status=200)

    webhook.async_register(
        hass,
        DOMAIN,
        f"AlarmeHA {entry.data[CONF_PERSON]}",
        entry.data[CONF_WEBHOOK_ID],
        handle_webhook,
        local_only=False,
        allowed_methods=["POST"],
    )
    entry.async_on_unload(lambda: webhook.async_unregister(hass, entry.data[CONF_WEBHOOK_ID]))

    async def heartbeat(_now: Any = None) -> None:
        await phone.async_request_list_silently()

    entry.async_on_unload(async_call_later(hass, 30, heartbeat))
    entry.async_on_unload(async_track_time_interval(hass, heartbeat, timedelta(hours=HEARTBEAT_HOURS)))
    entry.async_on_unload(entry.add_update_listener(_async_reload))

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def _async_reload(hass: HomeAssistant, entry: AlarmeHaConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: AlarmeHaConfigEntry) -> bool:
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
