"""Comandos websocket usados pelo painel Despertador."""

from __future__ import annotations

from typing import Any

import voluptuous as vol

from datetime import timedelta

from homeassistant.components import websocket_api
from homeassistant.components.http.auth import async_sign_path
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError

from .hub import clean_url
from .const import APK_PATH, APK_URL, DEFAULT_TIMEOUT, DOMAIN, OPS, RELEASES_URL


@callback
def async_register_websocket(hass: HomeAssistant) -> None:
    websocket_api.async_register_command(hass, ws_status)
    websocket_api.async_register_command(hass, ws_activate)
    websocket_api.async_register_command(hass, ws_command)
    websocket_api.async_register_command(hass, ws_remove)


def _hub(hass: HomeAssistant, connection, msg) -> Any:
    hub = hass.data.get(DOMAIN)
    if hub is None:
        connection.send_error(msg["id"], "not_loaded", "A integração AlarmeHA não está configurada")
    return hub


@websocket_api.websocket_command({vol.Required("type"): "alarmeha/status"})
@callback
def ws_status(hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict[str, Any]) -> None:
    if (hub := _hub(hass, connection, msg)) is None:
        return
    user = connection.user
    connection.send_result(
        msg["id"],
        {
            # Link assinado servido pelo próprio HA (mesma origem do painel, funciona no Companion).
            "apk_url": async_sign_path(
                hass, APK_PATH, timedelta(minutes=30), refresh_token_id=connection.refresh_token_id
            ),
            "apk_github_url": APK_URL,
            "releases_url": RELEASES_URL,
            "admin": user.is_admin,
            "url": hub.external_url,
            "celulares": [p.summary(user.id) for p in hub.phones_for(user)],
            "companion": [{"entry_id": d["entry_id"], "nome": d["nome"]} for d in hub.mobile_devices_for_user(user.id)],
        },
    )


@websocket_api.websocket_command(
    {
        vol.Required("type"): "alarmeha/ativar",
        vol.Optional("companion_entry_id"): str,
        vol.Optional("origem"): str,  # window.location.origin do painel
    }
)
@callback
def ws_activate(hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict[str, Any]) -> None:
    if (hub := _hub(hass, connection, msg)) is None:
        return
    if not clean_url(msg.get("origem")) and not hub.external_url:
        connection.send_error(msg["id"], "no_url", "Configure a URL externa do Home Assistant na integração AlarmeHA")
        return
    devices = hub.mobile_devices_for_user(connection.user.id)
    wanted = msg.get("companion_entry_id")
    if wanted:
        devices = [d for d in devices if d["entry_id"] == wanted]
    if not devices:
        connection.send_error(
            msg["id"], "no_companion",
            "Nenhum celular com o app Companion encontrado para o seu usuário. Entre no app Companion com a sua conta.",
        )
        return
    if len(devices) > 1:
        connection.send_error(msg["id"], "choose_companion", "Escolha qual celular ativar")
        return
    connection.send_result(msg["id"], hub.create_code(connection.user.id, devices[0]["entry_id"], msg.get("origem")))


@websocket_api.websocket_command(
    {
        vol.Required("type"): "alarmeha/comando",
        vol.Required("celular"): str,
        vol.Required("op"): vol.In(OPS),
        vol.Optional("dados", default={}): dict,
        vol.Optional("timeout", default=DEFAULT_TIMEOUT): vol.All(int, vol.Range(min=5, max=120)),
    }
)
@websocket_api.async_response
async def ws_command(hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict[str, Any]) -> None:
    from . import OP_FIELDS  # noqa: PLC0415

    if (hub := _hub(hass, connection, msg)) is None:
        return
    phone = hub.phones.get(msg["celular"])
    if phone is None or not hub.can_access(connection.user, phone):
        connection.send_error(msg["id"], "unauthorized", "Celular não encontrado")
        return
    try:
        fields = vol.Schema(OP_FIELDS[msg["op"]])(msg["dados"])
    except vol.Invalid as err:
        connection.send_error(msg["id"], "invalid_format", str(err))
        return
    if msg["op"] in ("editar", "ligar", "desligar", "apagar") and fields.get("ref") == "*" and not connection.user.is_admin:
        connection.send_error(msg["id"], "invalid_format", "Use um alarme específico")
        return
    try:
        result = await phone.async_command(msg["op"], fields, msg["timeout"])
    except HomeAssistantError as err:
        connection.send_error(msg["id"], "command_failed", str(err))
        return
    connection.send_result(msg["id"], {"resposta": result, "celular": phone.summary(connection.user.id)})


@websocket_api.websocket_command({vol.Required("type"): "alarmeha/remover", vol.Required("celular"): str})
@websocket_api.async_response
async def ws_remove(hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict[str, Any]) -> None:
    if (hub := _hub(hass, connection, msg)) is None:
        return
    phone = hub.phones.get(msg["celular"])
    if phone is None or not hub.can_access(connection.user, phone):
        connection.send_error(msg["id"], "unauthorized", "Celular não encontrado")
        return
    await hub.async_remove_phone(phone.phone_id)
    connection.send_result(msg["id"], {})
