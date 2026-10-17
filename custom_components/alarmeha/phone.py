"""Estado e comunicação com um celular com AlarmeHA."""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta
import logging
import secrets
from typing import Any
from urllib.parse import quote

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from .const import (
    ACTION_COMANDO,
    CONF_NOTIFY_SERVICE,
    CONF_PACKAGE,
    CONF_PERSON,
    CONF_TOKEN,
    DEFAULT_TIMEOUT,
    DOMAIN,
    EVENT_ALARMEHA,
    MAX_ATTEMPTS,
    SIGNAL_UPDATE,
)

_LOGGER = logging.getLogger(__name__)

# Respostas que confirmam um pedido.
RESPONSE_EVENTS = {"criado", "alterado", "apagado", "lista", "erro"}


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
    """Um celular (uma pessoa) pareado."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass = hass
        self.entry = entry
        self._store: Store[dict[str, Any]] = Store(hass, 1, f"{DOMAIN}.{entry.entry_id}")
        self._pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self.alarms: list[dict[str, Any]] = []
        self.last_event: dict[str, Any] | None = None
        self.last_seen: datetime | None = None

    @property
    def person(self) -> str:
        return self.entry.data[CONF_PERSON]

    @property
    def signal(self) -> str:
        return SIGNAL_UPDATE.format(self.entry.entry_id)

    async def async_load(self) -> None:
        data = await self._store.async_load() or {}
        self.alarms = data.get("alarmes", [])
        self.last_event = data.get("ultimo_evento")
        if seen := data.get("visto_em"):
            self.last_seen = dt_util.parse_datetime(seen)

    @callback
    def _save(self) -> None:
        self._store.async_delay_save(
            lambda: {
                "alarmes": self.alarms,
                "ultimo_evento": self.last_event,
                "visto_em": self.last_seen.isoformat() if self.last_seen else None,
            },
            5,
        )

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
            {"config_entry_id": self.entry.entry_id, "pessoa": self.person, **payload},
        )
        async_dispatcher_send(self.hass, self.signal)
        self._save()

        # O próximo disparo muda depois que o alarme toca: atualiza a lista.
        if event == "desligado":
            self.hass.async_create_task(self.async_request_list_silently())

    # ---- comandos para o celular ----------------------------------------------------

    async def _send(self, fields: dict[str, Any]) -> None:
        service = self.entry.data[CONF_NOTIFY_SERVICE].removeprefix("notify.")
        await self.hass.services.async_call(
            "notify",
            service,
            {
                "message": "command_broadcast_intent",
                "data": {
                    "intent_package_name": self.entry.data[CONF_PACKAGE],
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
        payload = {"token": self.entry.data[CONF_TOKEN], "op": op, "pedido": pedido, **fields}
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
                    raise HomeAssistantError(
                        f"AlarmeHA ({self.person}) recusou '{op}': {result.get('motivo')}"
                    )
                return result
            raise HomeAssistantError(
                f"AlarmeHA ({self.person}) não confirmou '{op}' após {MAX_ATTEMPTS} tentativas"
            )
        finally:
            self._pending.pop(pedido, None)

    async def async_request_list_silently(self) -> None:
        """Pede a lista sem esperar confirmação (atualização e 'batimento')."""
        try:
            await self._send(
                {"token": self.entry.data[CONF_TOKEN], "op": "listar", "pedido": secrets.token_hex(6)}
            )
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("AlarmeHA %s: falha ao pedir lista: %s", self.person, err)

    # ---- valores derivados ----------------------------------------------------------

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
