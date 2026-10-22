"""Diagnósticos da integração (Configurações → Dispositivos e serviços → AlarmeHA → Baixar diagnósticos)."""

from __future__ import annotations

from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant

from .const import VERSION


async def async_get_config_entry_diagnostics(hass: HomeAssistant, entry: ConfigEntry) -> dict[str, Any]:
    hub = entry.runtime_data
    return {
        "versao": VERSION,
        "celulares": [
            {"celular": p.phone_id, "ultimo_contato": p.last_seen.isoformat() if p.last_seen else None,
             "ultimo_evento": (p.last_event or {}).get("evento")}
            for p in hub.phones.values()
        ],
        "eventos": hub.diag.listar(),
    }
