"""Registro do Modo diagnóstico do app: os últimos 500 eventos técnicos, guardados em disco.

Fica fora do recorder: os eventos "diagnostico" não vão para o barramento nem para o histórico.
Leitura pelo serviço ``alarmeha.diagnostico`` (com resposta) e pelos diagnósticos da integração."""

from __future__ import annotations

from collections import deque
from typing import Any

from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from .const import DOMAIN

EVENTO = "diagnostico"
MAX_EVENTOS = 500
SALVAR_APOS_S = 10
# Campos que nunca entram no registro (identificam a pessoa ou o aparelho).
FORA = {"pessoa", "dispositivo", "origem", "evento", "rotulo", "grupo_nome", "alarme", "alarmes"}
MAX_TEXTO = 300


def limpar(payload: dict[str, Any]) -> dict[str, Any]:
    """Só campos técnicos, com valores simples e textos curtos."""
    out: dict[str, Any] = {}
    for k, v in payload.items():
        if k in FORA or not isinstance(k, str):
            continue
        if isinstance(v, str):
            out[k] = v[:MAX_TEXTO]
        elif v is None or isinstance(v, (bool, int, float)):
            out[k] = v
    return out


class DiagLog:
    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass
        self._store: Store[dict[str, Any]] = Store(hass, 1, f"{DOMAIN}.diagnostico")
        self.eventos: deque[dict[str, Any]] = deque(maxlen=MAX_EVENTOS)

    async def async_load(self) -> None:
        data = await self._store.async_load() or {}
        self.eventos.clear()
        self.eventos.extend(e for e in (data.get("eventos") or [])[-MAX_EVENTOS:] if isinstance(e, dict))

    @callback
    def add(self, phone_id: str, payload: dict[str, Any]) -> None:
        self.eventos.append({"recebido_em": dt_util.utcnow().isoformat(), "celular": phone_id, **limpar(payload)})
        self._store.async_delay_save(lambda: {"eventos": list(self.eventos)}, SALVAR_APOS_S)

    async def async_flush(self) -> None:
        await self._store.async_save({"eventos": list(self.eventos)})

    def listar(self, limite: int | None = None, celular: str | None = None) -> list[dict[str, Any]]:
        itens = [e for e in self.eventos if celular is None or e.get("celular") == celular]
        return itens[-limite:] if limite else itens
