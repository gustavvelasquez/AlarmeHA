"""Integração AlarmeHA: despertador Android controlado pelo Home Assistant."""

from __future__ import annotations

from datetime import timedelta
import logging
from pathlib import Path
from typing import Any

import voluptuous as vol

from homeassistant.components import frontend, panel_custom, persistent_notification
from homeassistant.components.http import StaticPathConfig
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, ServiceCall, ServiceResponse, SupportsResponse
from homeassistant.exceptions import ServiceValidationError, Unauthorized
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.event import async_call_later, async_track_time_interval
from homeassistant.helpers.typing import ConfigType

from .const import (
    CONF_HA_URL,
    DEFAULT_TIMEOUT,
    DOMAIN,
    HEARTBEAT_HOURS,
    LEGACY_NOTIFY_SERVICE,
    LEGACY_PACKAGE,
    LEGACY_PERSON,
    LEGACY_PHONE,
    LEGACY_TOKEN,
    LEGACY_WEBHOOK_ID,
    OPS,
    PANEL_ICON,
    PANEL_TITLE,
    PANEL_URL,
    STATIC_URL,
    VERSION,
)
from .apk import AlarmeHaApkView, ApkCache
from .http_api import AlarmeHaActivateView
from .hub import AlarmeHaHub
from .phone import AlarmeHaPhone
from .websocket import async_register_websocket

_LOGGER = logging.getLogger(__name__)

PLATFORMS = [Platform.BINARY_SENSOR, Platform.SENSOR]
CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)

type AlarmeHaConfigEntry = ConfigEntry[AlarmeHaHub]

REF = vol.All(cv.string, vol.Match(r"^[a-z0-9_-]{1,64}$"))
REF_OR_ALL = vol.Any("*", REF)
HORA = vol.All(vol.Coerce(int), vol.Range(min=0, max=23))
MINUTO = vol.All(vol.Coerce(int), vol.Range(min=0, max=59))
DIAS = vol.All(vol.Coerce(int), vol.Any(-2, -1, vol.Range(min=1, max=127)))
EXTRA_FIELDS = {
    vol.Optional("rotulo"): vol.All(cv.string, vol.Length(max=64)),
    vol.Optional("ativo"): cv.boolean,
    vol.Optional("vibrar"): cv.boolean,
    vol.Optional("unico"): cv.boolean,
}
# Campos de cada operação (sem o alvo); usados pelos serviços e pelo painel.
OP_FIELDS: dict[str, dict] = {
    "criar": {**EXTRA_FIELDS, vol.Required("ref"): REF, vol.Required("hora"): HORA,
              vol.Required("minuto"): MINUTO, vol.Required("dias"): DIAS},
    "editar": {**EXTRA_FIELDS, vol.Required("ref"): REF, vol.Optional("hora"): HORA,
               vol.Optional("minuto"): MINUTO, vol.Optional("dias"): DIAS},
    "ligar": {vol.Required("ref"): REF_OR_ALL},
    "desligar": {vol.Required("ref"): REF_OR_ALL},
    "apagar": {vol.Required("ref"): REF_OR_ALL},
    "listar": {},
}
TARGET = {
    vol.Optional("device_id"): vol.Any(cv.string, vol.All(cv.ensure_list, [cv.string])),
    vol.Optional("timeout", default=DEFAULT_TIMEOUT): vol.All(vol.Coerce(int), vol.Range(min=10, max=300)),
}
SERVICE_SCHEMAS = {op: vol.Schema({**TARGET, **fields}) for op, fields in OP_FIELDS.items()}


def _get_hub(hass: HomeAssistant) -> AlarmeHaHub:
    hub = hass.data.get(DOMAIN)
    if hub is None:
        raise ServiceValidationError("A integração AlarmeHA não está carregada")
    return hub


async def _resolve_phone(hass: HomeAssistant, call: ServiceCall) -> AlarmeHaPhone:
    hub = _get_hub(hass)
    device_id = call.data.get("device_id")
    if isinstance(device_id, list):
        if len(device_id) != 1:
            raise ServiceValidationError("Escolha um único celular (device_id)")
        device_id = device_id[0]
    if device_id:
        phone = hub.phone_for_device(device_id)
    elif len(hub.phones) == 1:
        phone = next(iter(hub.phones.values()))
    else:
        phone = None
    if phone is None:
        raise ServiceValidationError("Informe o device_id de um celular AlarmeHA")

    # Chamadas feitas por um usuário (não por automação) só podem mexer no próprio celular.
    if call.context.user_id:
        user = await hass.auth.async_get_user(call.context.user_id)
        if not AlarmeHaHub.can_access(user, phone):
            raise Unauthorized(context=call.context)
    return phone


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    async def handle(call: ServiceCall) -> ServiceResponse:
        phone = await _resolve_phone(hass, call)
        fields = {k: v for k, v in call.data.items() if k not in ("device_id", "timeout")}
        result = await phone.async_command(call.service, fields, call.data["timeout"])
        return result if call.return_response else None

    for op in OPS:
        hass.services.async_register(
            DOMAIN, op, handle, schema=SERVICE_SCHEMAS[op], supports_response=SupportsResponse.OPTIONAL
        )
    async_register_websocket(hass)
    return True


async def async_migrate_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Versão 1 (uma entrada por celular) -> versão 2 (uma entrada só)."""
    if entry.version == 1:
        others = [e for e in hass.config_entries.async_entries(DOMAIN) if e.entry_id != entry.entry_id and e.version >= 2]
        if others:
            persistent_notification.async_create(
                hass,
                f"A entrada antiga do AlarmeHA **{entry.title}** não é mais necessária. "
                "Remova-a em Configurações → Dispositivos e serviços → AlarmeHA. "
                "O celular dela deve ser ativado de novo pelo painel **Despertador**.",
                title="AlarmeHA: entrada antiga",
                notification_id=f"alarmeha_legacy_{entry.entry_id}",
            )
            return False
        old = dict(entry.data)
        legacy = None
        if old.get(LEGACY_WEBHOOK_ID) and old.get(LEGACY_TOKEN):
            legacy = {
                "phone_id": entry.entry_id,
                "user_id": None,
                "person": old.get(LEGACY_PERSON),
                "device_name": old.get(LEGACY_PERSON),
                "mobile_entry_id": None,
                "notify_service": old[LEGACY_NOTIFY_SERVICE],
                "package": old.get(LEGACY_PACKAGE, "br.casa.alarmeha"),
                "webhook_id": old[LEGACY_WEBHOOK_ID],
                "token": old[LEGACY_TOKEN],
            }
        hass.config_entries.async_update_entry(
            entry,
            data={CONF_HA_URL: old.get(CONF_HA_URL, ""), LEGACY_PHONE: legacy},
            title="AlarmeHA",
            unique_id=DOMAIN,
            version=2,
        )
    return True


async def async_setup_entry(hass: HomeAssistant, entry: AlarmeHaConfigEntry) -> bool:
    hub = AlarmeHaHub(hass, entry)
    await hub.async_load()
    if legacy := entry.data.get(LEGACY_PHONE):
        hub.add_legacy_phone(legacy)
        hass.config_entries.async_update_entry(
            entry, data={k: v for k, v in entry.data.items() if k != LEGACY_PHONE}
        )
    entry.runtime_data = hub
    hass.data[DOMAIN] = hub

    for phone in hub.phones.values():
        hub.register_webhook(phone)
    entry.async_on_unload(hub.unregister_webhooks)

    flags = hass.data.setdefault(f"{DOMAIN}_registered", set())
    if "http" not in flags:
        flags.add("http")
        hass.http.register_view(AlarmeHaActivateView())
        hass.http.register_view(AlarmeHaApkView(ApkCache(hass)))
        await hass.http.async_register_static_paths(
            [StaticPathConfig(STATIC_URL, str(Path(__file__).parent / "frontend"), False)]
        )
    await panel_custom.async_register_panel(
        hass,
        # Nome do elemento com a versão: um WebView que ficou aberto durante a atualização já tem
        # "alarmeha-panel" definido com o código antigo e não aceitaria redefinir a mesma tag.
        webcomponent_name=f"alarmeha-panel-{VERSION.replace('.', '-')}",
        frontend_url_path=PANEL_URL,
        module_url=f"{STATIC_URL}/alarmeha-panel.js?v={VERSION}",
        sidebar_title=PANEL_TITLE,
        sidebar_icon=PANEL_ICON,
        require_admin=False,
        config={},
    )
    entry.async_on_unload(lambda: frontend.async_remove_panel(hass, PANEL_URL))

    async def heartbeat(_now: Any = None) -> None:
        for phone in list(hub.phones.values()):
            await phone.async_request_list_silently()

    entry.async_on_unload(async_call_later(hass, 30, heartbeat))
    entry.async_on_unload(async_track_time_interval(hass, heartbeat, timedelta(hours=HEARTBEAT_HOURS)))
    entry.async_on_unload(entry.add_update_listener(_async_reload))

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def _async_reload(hass: HomeAssistant, entry: AlarmeHaConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: AlarmeHaConfigEntry) -> bool:
    ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if ok:
        hass.data.pop(DOMAIN, None)
    return ok
