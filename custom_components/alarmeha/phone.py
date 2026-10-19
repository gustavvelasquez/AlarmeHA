"""Celulares pareados (um por pessoa/aparelho) e comunicação com o app AlarmeHA."""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta
import logging
import secrets
from typing import TYPE_CHECKING, Any
from urllib.parse import quote

from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.util import dt as dt_util

from .const import (
    ACTION_COMANDO,
    CONNECTED_MAX_AGE_HOURS,
    DEFAULT_TIMEOUT,
    EVENT_ALARMEHA,
    MAX_ATTEMPTS,
    SIGNAL_UPDATE,
)

if TYPE_CHECKING:
    from .hub import AlarmeHaHub

_LOGGER = logging.getLogger(__name__)

RESPONSE_EVENTS = {"criado", "alterado", "apagado", "lista", "erro"}
STORED_FIELDS = (
    "agenda",
    "user_id", "person", "device_name", "mobile_entry_id", "notify_service",
    "package", "webhook_id", "token", "alarms", "last_event", "last_seen", "activated_at",
)


def encode_extras(fields: dict[str, Any]) -> str:
    """Monta intent_extras no formato do Companion (nome:valor:tipo, separados por vírgula)."""
    parts = []
    for key, value in fields.items():
        if value is None:
            continue
        if isinstance(value, bool):
            parts.append(f"{key}:{'true' if value else 'false'}:boolean")
        elif isinstance(value, int):
            parts.append(f"{key}:{value}:int")
        else:
            parts.append(f"{key}:{quote(str(value), safe='')}:String.urlencoded")
    return ",".join(parts)


class AlarmeHaPhone:
    """Um celular pareado."""

    def __init__(self, hass: HomeAssistant, hub: AlarmeHaHub, phone_id: str, data: dict[str, Any]) -> None:
        self.hass = hass
        self.hub = hub
        self.phone_id = phone_id
        self.user_id: str | None = data.get("user_id")
        self.person: str = data.get("person") or "Celular"
        self.device_name: str = data.get("device_name") or ""
        self.mobile_entry_id: str | None = data.get("mobile_entry_id")
        self.notify_service: str = data["notify_service"]
        self.package: str = data["package"]
        self.webhook_id: str = data["webhook_id"]
        self.token: str = data["token"]
        self.alarms: list[dict[str, Any]] = data.get("alarms") or []
        self.last_event: dict[str, Any] | None = data.get("last_event")
        seen = data.get("last_seen")
        self.last_seen: datetime | None = dt_util.parse_datetime(seen) if seen else None
        self.activated_at: str | None = data.get("activated_at")
        self.agenda: dict[str, Any] = data.get("agenda") or {}
        self._pending: dict[str, asyncio.Future[dict[str, Any]]] = {}

    def as_dict(self) -> dict[str, Any]:
        data = {key: getattr(self, key) for key in STORED_FIELDS}
        data["last_seen"] = self.last_seen.isoformat() if self.last_seen else None
        return data

    @property
    def signal(self) -> str:
        return SIGNAL_UPDATE.format(self.phone_id)

    @property
    def connected(self) -> bool:
        return self.last_seen is not None and dt_util.utcnow() - self.last_seen < timedelta(
            hours=CONNECTED_MAX_AGE_HOURS
        )

    @property
    def next_alarm(self) -> datetime | None:
        now = dt_util.utcnow()
        times = []
        for alarm in self.alarms:
            if alarm.get("ativo") and (value := alarm.get("proximo_disparo")):
                parsed = dt_util.parse_datetime(value)
                if parsed and parsed > now - timedelta(minutes=1):
                    times.append(parsed)
        return min(times) if times else None

    def summary(self, viewer_id: str | None) -> dict[str, Any]:
        """Dados para o painel (sem token nem webhook)."""
        nxt = self.next_alarm
        return {
            "id": self.phone_id,
            "pessoa": self.person,
            "aparelho": self.device_name,
            "pacote": self.package,
            "meu": self.user_id is not None and self.user_id == viewer_id,
            "conectado": self.connected,
            "visto_em": self.last_seen.isoformat() if self.last_seen else None,
            "proximo_alarme": nxt.isoformat() if nxt else None,
            "ultimo_evento": (self.last_event or {}).get("evento"),
            "alarmes": self.alarms,
            "ativado_em": self.activated_at,
        }

    # ---- eventos vindos do celular -------------------------------------------------

    @callback
    def handle_event(self, payload: dict[str, Any]) -> None:
        event = payload.get("evento")
        if payload.get("origem") != "alarmeha" or not isinstance(event, str):
            return
        self.last_event = payload
        self.last_seen = dt_util.utcnow()

        if event == "lista" and isinstance(payload.get("alarmes"), list):
            self.alarms = payload["alarmes"]
        elif event in ("criado", "alterado") and isinstance(payload.get("alarme"), dict):
            alarm = payload["alarme"]
            self.alarms = [a for a in self.alarms if a.get("id") != alarm.get("id")] + [alarm]
        elif event == "apagado":
            self.alarms = [a for a in self.alarms if a.get("id") != payload.get("id")]

        pedido = payload.get("pedido")
        if event in RESPONSE_EVENTS and pedido in self._pending:
            future = self._pending.pop(pedido)
            if not future.done():
                future.set_result(payload)

        self.hass.bus.async_fire(
            EVENT_ALARMEHA,
            {"phone_id": self.phone_id, "pessoa": self.person, "user_id": self.user_id, **payload},
        )
        async_dispatcher_send(self.hass, self.signal)
        self.hub.async_save()

        if event in ("desligado", "pareado"):
            self.hass.async_create_task(self.async_request_list_silently())

    # ---- comandos para o celular ----------------------------------------------------

    async def _send(self, fields: dict[str, Any]) -> None:
        service = self.notify_service.removeprefix("notify.")
        if not self.hass.services.has_service("notify", service):
            raise HomeAssistantError(f"Serviço notify.{service} não encontrado (app Companion do celular)")
        await self.hass.services.async_call(
            "notify",
            service,
            {
                "message": "command_broadcast_intent",
                "data": {
                    "intent_package_name": self.package,
                    "intent_action": ACTION_COMANDO,
                    "intent_extras": encode_extras(fields),
                },
            },
            blocking=True,
        )

    async def async_command(
        self, op: str, fields: dict[str, Any], timeout: int = DEFAULT_TIMEOUT
    ) -> dict[str, Any]:
        """Envia um comando e espera a confirmação (mesmo pedido), com até 3 tentativas."""
        pedido = secrets.token_hex(6)
        payload = {"token": self.token, "op": op, "pedido": pedido, **fields}
        future: asyncio.Future[dict[str, Any]] = self.hass.loop.create_future()
        self._pending[pedido] = future
        try:
            for attempt in range(1, MAX_ATTEMPTS + 1):
                _LOGGER.debug("AlarmeHA %s: %s (pedido %s, tentativa %s)", self.person, op, pedido, attempt)
                await self._send(payload)
                try:
                    async with asyncio.timeout(timeout):
                        result = await asyncio.shield(future)
                except TimeoutError:
                    continue
                if result.get("evento") == "erro":
                    raise HomeAssistantError(f"AlarmeHA ({self.person}) recusou '{op}': {result.get('motivo')}")
                return result
            raise HomeAssistantError(
                f"AlarmeHA ({self.person}) não confirmou '{op}' após {MAX_ATTEMPTS} tentativas"
            )
        finally:
            self._pending.pop(pedido, None)

    async def async_request_list_silently(self) -> None:
        try:
            await self._send({"token": self.token, "op": "listar", "pedido": secrets.token_hex(6)})
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("AlarmeHA %s: falha ao pedir lista: %s", self.person, err)
