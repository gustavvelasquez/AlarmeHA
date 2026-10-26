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

from .diagnostico import EVENTO as DIAG_EVENTO
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
# 0.15.0: eventos mais velhos que isto (fila offline do celular, até 7 dias) só vão para o registro.
ATRASO_MAX_H = 24

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
    "alerta_silencio",
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
        self._ops: dict[str, str] = {}  # pedido -> op (só em memória)
        self.reconciliados: dict[str, tuple[datetime, int]] = {}  # ref -> (última recriação, vezes)
        self.ocupados: dict[str, datetime] = {}  # ref -> até quando (só em memória)
        # ref -> {"ocorrencia": ISO do toque pulado no app (None = religado), "quando": ISO do aviso}
        self.pulados: dict[str, dict[str, Any]] = {
            r: v for r, v in (data.get("pulados") or {}).items() if isinstance(v, dict)
        }
        enviado = data.get("last_command_at")
        # Último comando enviado ao celular (para o alerta de "celular calado").
        self.last_command_at: datetime | None = dt_util.parse_datetime(enviado) if enviado else None
        self.alerta_silencio: bool = bool(data.get("alerta_silencio"))

    def repareado(self, data: dict[str, Any]) -> None:
        """Pareado de novo: o MESMO objeto recebe as credenciais novas (entidades, sinais e
        temporizadores continuam ligados a ele). Pendências do aparelho antigo são canceladas."""
        for future in self._pending.values():
            if not future.done():
                future.cancel()
        self._pending.clear()
        self.ocupados.clear()
        self.reconciliados.clear()
        for key in ("user_id", "person", "device_name", "mobile_entry_id", "notify_service",
                    "package", "webhook_id", "token", "activated_at"):
            setattr(self, key, data[key])
        self.alarms = list(data.get("alarms") or [])
        self.agenda = dict(data.get("agenda") or {})
        self.toques = list(data.get("toques") or [])
        self.pulados = dict(data.get("pulados") or {})
        self.last_command_at = None
        self.alerta_silencio = False

    def as_dict(self) -> dict[str, Any]:
        data = {key: getattr(self, key) for key in STORED_FIELDS}
        data["last_seen"] = self.last_seen.isoformat() if self.last_seen else None
        data["last_command_at"] = self.last_command_at.isoformat() if self.last_command_at else None
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
        elif event in ("desligado", "perdido") and self.ocupados.pop(ref, None) is not None:
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

    @staticmethod
    def _atrasado(payload: dict[str, Any]) -> bool:
        """Evento cujo "quando" (horário no celular) passou de ATRASO_MAX_H horas. Sem "quando" válido: não é."""
        q = payload.get("quando")
        quando = dt_util.parse_datetime(q) if isinstance(q, str) else None
        if quando is None:
            return False
        if quando.tzinfo is None:
            quando = quando.replace(tzinfo=dt_util.UTC)
        return dt_util.utcnow() - quando > timedelta(hours=ATRASO_MAX_H)

    @callback
    def handle_event(self, payload: dict[str, Any]) -> None:
        event = payload.get("evento")
        if payload.get("origem") != "alarmeha" or not isinstance(event, str):
            return
        pedido = payload.get("pedido")
        # "apagar" de algo que já não existe é sucesso: não vira "erro" no último evento.
        benigno = event == "erro" and payload.get("motivo") == "ref_inexistente" and self._ops.get(pedido) == "apagar"
        if not benigno and not self._atrasado(payload):
            self.last_event = payload
        self.last_seen = dt_util.utcnow()
        if self.alerta_silencio:
            self.alerta_silencio = False
            _alerta(self.hass, self, None)
        if event == DIAG_EVENTO:
            # Modo diagnóstico: só vai para o registro em disco (nem barramento, nem histórico).
            self.hub.diag.add(self.phone_id, payload)
            self.hub.async_save()
            return
        if self._atrasado(payload):
            # 0.15.0: a fila do celular guarda até 7 dias. Evento com mais de ATRASO_MAX_H horas só
            # vai para o registro (diagnóstico): não age (toque, pulo, lista, pedido) nem vai ao barramento,
            # para não disparar automações por um "tocou" de dias atrás.
            self.hub.diag.add(self.phone_id, {**payload, "tipo": f"atrasado_{event}"[:40]})
            self.hub.async_save()
            return

        if event in ("tocou", "soneca", "desligado", "perdido"):  # 0.13.0: "perdido" = acabou o tempo
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
            from .agenda import faltando_no_celular  # noqa: PLC0415

            if (self.agenda or {}).get("ligado") and (faltam := faltando_no_celular(self)):
                _LOGGER.warning("AlarmeHA %s: lista sem %s; reconciliando", self.person, ", ".join(sorted(faltam)))
                self.sincronizar_depois(SINCRONIZAR_APOS_DESLIGAR_S)
        elif event in ("criado", "alterado", "pulado", "religado") and isinstance(payload.get("alarme"), dict):
            alarm = payload["alarme"]
            self._pulo_da_lista(alarm, quando)
            self.alarms = [a for a in self.alarms if a.get("id") != alarm.get("id")] + [alarm]
        elif event == "apagado":
            self.alarms = [a for a in self.alarms if a.get("id") != payload.get("id")]

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

        if event in ("desligado", "perdido", "pareado"):
            self.hass.async_create_task(self.async_request_list_silently())
        if event == "pareado":
            # Celular pareado (de novo): reenvia o plano completo sem esperar o próximo tick.
            self.sincronizar_depois(SINCRONIZAR_APOS_PAREAR_S)

    # ---- comandos para o celular ----------------------------------------------------

    async def _send(self, fields: dict[str, Any]) -> None:
        self.last_command_at = dt_util.utcnow()
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
        self._ops[pedido] = op
        resumo = {k: v for k, v in fields.items() if k in ("ref", "hora", "minuto", "dias", "ativo", "grupo")}
        try:
            for attempt in range(1, MAX_ATTEMPTS + 1):
                _LOGGER.info("AlarmeHA %s: %s %s (pedido %s, tentativa %s)", self.person, op, resumo, pedido, attempt)
                await self._send(payload)
                try:
                    async with asyncio.timeout(timeout):
                        result = await asyncio.shield(future)
                except TimeoutError:
                    continue
                if result.get("evento") == "erro":
                    if op == "apagar" and result.get("motivo") == "ref_inexistente":
                        return result  # já não existe: o objetivo foi atingido
                    raise HomeAssistantError(f"AlarmeHA ({self.person}) recusou '{op}': {result.get('motivo')}")
                return result
            raise HomeAssistantError(
                f"AlarmeHA ({self.person}) não confirmou '{op}' após {MAX_ATTEMPTS} tentativas"
            )
        finally:
            self._pending.pop(pedido, None)
            self._ops.pop(pedido, None)

    async def async_request_list_silently(self) -> None:
        try:
            await self._send({"token": self.token, "op": "listar", "pedido": secrets.token_hex(6)})
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("AlarmeHA %s: falha ao pedir lista: %s", self.person, err)


# ---- celular calado ----------------------------------------------------------------

SILENCIO_MIN = 30


def motivo_silencio(phone: AlarmeHaPhone, agora: datetime) -> str | None:
    """Por que o celular deveria ter falado e está calado há mais de SILENCIO_MIN, ou None.
    Calado só não basta (celular parado não manda nada): precisa de comando enviado depois do
    último evento, ou de alarme enviado pelo HA que deveria ter tocado depois dele."""
    from .agenda import GUARDA_DEPOIS_MIN, _disparos  # noqa: PLC0415

    visto = phone.last_seen
    if visto is not None and agora - visto <= timedelta(minutes=SILENCIO_MIN):
        return None
    desde = visto or (dt_util.parse_datetime(phone.activated_at) if phone.activated_at else None)
    if desde is None:
        return None
    if phone.last_command_at and phone.last_command_at > desde and agora - phone.last_command_at > timedelta(minutes=SILENCIO_MIN):
        return "comando sem resposta"
    for alvo in ((phone.agenda or {}).get("_enviados") or {}).values():
        for quando in _disparos(alvo, agora):
            if desde < quando <= agora - timedelta(minutes=GUARDA_DEPOIS_MIN):
                return f"alarme das {quando:%H:%M} sem aviso de toque"
    return None


def _alerta(hass: HomeAssistant, phone: AlarmeHaPhone, motivo: str | None) -> None:
    from homeassistant.components import persistent_notification  # noqa: PLC0415

    nid = f"alarmeha_silencio_{phone.phone_id}"
    if motivo is None:
        persistent_notification.async_dismiss(hass, nid)
        return
    visto = dt_util.as_local(phone.last_seen).strftime("%d/%m %H:%M") if phone.last_seen else "nunca"
    persistent_notification.async_create(
        hass,
        f"O AlarmeHA de **{phone.person}** não manda nada ao HA desde {visto} ({motivo}). "
        "Abra o app no celular e veja Sobre → Home Assistant (último erro de envio).",
        title="AlarmeHA: celular sem resposta",
        notification_id=nid,
    )
    hass.bus.async_fire("alarmeha_silencio", {"phone_id": phone.phone_id, "pessoa": phone.person, "motivo": motivo})


def verificar_silencio(hass: HomeAssistant, phone: AlarmeHaPhone, agora: datetime) -> None:
    motivo = motivo_silencio(phone, agora)
    if motivo and not phone.alerta_silencio:
        phone.alerta_silencio = True
        _alerta(hass, phone, motivo)
        phone.hub.async_save()
