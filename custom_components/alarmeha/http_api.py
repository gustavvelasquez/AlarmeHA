"""Endpoint de ativação: o app troca o código de uso único por webhook + token."""

from __future__ import annotations

from http import HTTPStatus
import logging

from aiohttp import web

from homeassistant.components.http import KEY_HASS, HomeAssistantView

from .const import ACTIVATE_URL, DOMAIN
from .hub import ActivationError

_LOGGER = logging.getLogger(__name__)


class AlarmeHaActivateView(HomeAssistantView):
    url = ACTIVATE_URL
    name = "api:alarmeha:ativar"
    requires_auth = False  # o código de uso único (10 caracteres, 10 min) é a credencial

    async def post(self, request: web.Request) -> web.Response:
        hass = request.app[KEY_HASS]
        hub = hass.data.get(DOMAIN)
        if hub is None:
            return self.json_message("AlarmeHA não configurado", HTTPStatus.SERVICE_UNAVAILABLE)
        if hub.too_many_failures():
            return self.json_message("muitas_tentativas", HTTPStatus.TOO_MANY_REQUESTS)
        try:
            body = await request.json()
            code = str(body["codigo"])
            package = str(body["pacote"])
            label = str(body.get("dispositivo") or "")
        except (ValueError, KeyError, TypeError):
            return self.json_message("requisicao_invalida", HTTPStatus.BAD_REQUEST)
        try:
            phone, urls = await hub.async_redeem(code, package, label)
        except ActivationError as err:
            return self.json_message(str(err), HTTPStatus.FORBIDDEN)

        _LOGGER.info("AlarmeHA: celular de %s ativado", phone.person)
        return self.json(
            {
                "webhook": phone.webhook_id,
                "token": phone.token,
                "nome": phone.person,
                "url": urls[0] if urls else hub.external_url,  # compatível com o app 0.3.0/0.3.1
                "urls": urls,  # ordem: origem da ativação, externa, interna
                "url_externa": hub.external_url or None,
                "url_interna": hub.internal_url,
            }
        )
