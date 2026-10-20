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

# Alarme tocando ou em soneca: a agenda não mexe nele (editar no app cancela a soneca).
OCUPADO_TOCANDO_MIN = 10  # o app desliga sozinho após alarm_max_reminder_secs (padrão 5 min)
OCUPADO_SONECA_FOLGA_MIN = 5
SINCRONIZAR_APOS_DESLIGAR_S = 60
SINCRONIZAR_APOS_PAREAR_S = 10  # dá tempo de a lista do celular chegar antes de reenviar o plano

RESPONSE_EVENTS = {"criado", "alterado", "apagado", "lista", "toques", "erro"}
STORED_FIELDS = (
    "agenda", "toques", "pulados",
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
        self.toques: list[dict[str, Any]] = data.get("toques") or []
        self._pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self.ocupados: dict[str, datetime] = {}  # ref -> até quando (só em memória)
        # ref -> {"ocorrencia": ISO do toque pulado no app (None = religado), "quando": ISO do aviso}
        self.pulados: dict[str, dict[str, Any]] = {
            r: v for r, v in (data.get("pulados") or {}).items() if isinstance(v, dict)
        }

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
            "toques": self.toques,
            "ativado_em": self.activated_at,
        }

    # ---- alarme tocando / em soneca ---------------------------------------------------

    def _ref_do_evento(self, payload: dict[str, Any]) -> str | None:
        ref = payload.get("ref")
        if isinstance(ref, str) and ref:
            return ref
        alarm_id = payload.get("id")
        for a in self.alarms:
            if isinstance(a, dict) and alarm_id is not None and a.get("id") == alarm_id and a.get("ref"):
                return a["ref"]
        return None

    def ocupado(self, ref: str) -> bool:
        ate = self.ocupados.get(ref)
        if ate is None:
            return False
        if dt_util.utcnow() >= ate:
            self.ocupados.pop(ref, None)
            return False
        return True

    def _marcar_toque(self, event: str, payload: dict[str, Any]) -> None:
        ref = self._ref_do_evento(payload)
        if ref is None:
            return
        agora = dt_util.utcnow()
        if event == "tocou":
            self.ocupados[ref] = agora + timedelta(minutes=OCUPADO_TOCANDO_MIN)
        elif event == "soneca":
            try:
                minutos = max(0, int(payload.get("minutos") or 0))
            except (TypeError, ValueError):
                minutos = 0
            minutos = minutos or OCUPADO_TOCANDO_MIN
            self.ocupados[ref] = agora + timedelta(minutes=minutos + OCUPADO_SONECA_FOLGA_MIN)
        elif event == "desligado" and self.ocupados.pop(ref, None) is not None:
            self.sincronizar_depois(SINCRONIZAR_APOS_DESLIGAR_S)

    def sincronizar_depois(self, segundos: float) -> None:
        from homeassistant.helpers.event import async_call_later  # noqa: PLC0415

        async def _sincronizar(_now: Any = None) -> None:
            from . import agenda  # noqa: PLC0415

            try:
                await agenda.async_sincronizar(self.hass, self)
            except Exception as err:  # noqa: BLE001
                _LOGGER.warning("AlarmeHA agenda: falha ao sincronizar %s: %s", self.phone_id, err)

        async_call_later(self.hass, segundos, _sincronizar)

    # ---- pulado / religado no app ----------------------------------------------------

    def _registrar_pulo(self, ref: Any, ocorrencia: Any, quando: Any) -> None:
        """Guarda o último estado de pulo de um ref. Avisos da fila offline podem chegar fora de
        ordem: vale o mais recente ("quando" do celular)."""
        if not isinstance(ref, str) or not ref:
            return
        quando = quando if isinstance(quando, str) else dt_util.utcnow().isoformat()
        ant = self.pulados.get(ref)
        if ant and isinstance(ant.get("quando"), str):
            a, b = dt_util.parse_datetime(ant["quando"]), dt_util.parse_datetime(quando)
            if a and b and b < a:
                return
        self.pulados[ref] = {"ocorrencia": ocorrencia if isinstance(ocorrencia, str) and ocorrencia else None,
                             "quando": quando}

    def _pulo_da_lista(self, alarm: Any, quando: Any) -> None:
        """App 0.4+: cada alarme da lista traz "pulado" (ISO ou null). App antigo: sem o campo, nada muda."""
        if isinstance(alarm, dict) and "pulado" in alarm and alarm.get("ref"):
            atual = (self.pulados.get(alarm["ref"]) or {}).get("ocorrencia")
            if atual != alarm.get("pulado"):
                self._registrar_pulo(alarm["ref"], alarm.get("pulado"), quando)

    def limpar_pulados(self, agora: datetime) -> None:
        """Esquece pulos de ocorrências que já passaram (e religados antigos)."""
        limite = agora - timedelta(days=2)
        for ref, info in list(self.pulados.items()):
            oc = dt_util.parse_datetime(info["ocorrencia"]) if info.get("ocorrencia") else None
            q = dt_util.parse_datetime(info["quando"]) if info.get("quando") else None
            if (oc is not None and oc < agora - timedelta(hours=1)) or (oc is None and (q is None or q < limite)):
                self.pulados.pop(ref, None)

    # ---- eventos vindos do celular -------------------------------------------------

    @callback
    def handle_event(self, payload: dict[str, Any]) -> None:
        event = payload.get("evento")
        if payload.get("origem") != "alarmeha" or not isinstance(event, str):
            return
        self.last_event = payload
        self.last_seen = dt_util.utcnow()

        if event in ("tocou", "soneca", "desligado"):
            self._marcar_toque(event, payload)
        quando = payload.get("quando")
        if event == "pulado":
            self._registrar_pulo(self._ref_do_evento(payload), payload.get("ocorrencia"), quando)
        elif event == "religado":
            self._registrar_pulo(self._ref_do_evento(payload), None, quando)

        if event == "toques" and isinstance(payload.get("toques"), list):
            self.toques = [t for t in payload["toques"] if isinstance(t, dict) and t.get("uri")][:150]
        elif event == "lista" and isinstance(payload.get("alarmes"), list):
            self.alarms = payload["alarmes"]
            for a in self.alarms:
                self._pulo_da_lista(a, quando)
        elif event in ("criado", "alterado", "pulado", "religado") and isinstance(payload.get("alarme"), dict):
            alarm = payload["alarme"]
            self._pulo_da_lista(alarm, quando)
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
        if event == "pareado":
            # Celular pareado (de novo): reenvia o plano completo sem esperar o próximo tick.
            self.sincronizar_depois(SINCRONIZAR_APOS_PAREAR_S)

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
