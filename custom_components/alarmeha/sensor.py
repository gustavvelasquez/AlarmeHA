"""Sensores AlarmeHA."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from homeassistant.components.sensor import SensorDeviceClass, SensorEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import AlarmeHaConfigEntry
from .entity import AlarmeHaEntity, setup_dynamic


async def async_setup_entry(
    hass: HomeAssistant, entry: AlarmeHaConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    setup_dynamic(
        hass, entry, async_add_entities,
        lambda phone: [NextAlarmSensor(phone), LastEventSensor(phone), LastReceivedSensor(phone), AlarmsSensor(phone)],
    )


class NextAlarmSensor(AlarmeHaEntity, SensorEntity):
    _attr_device_class = SensorDeviceClass.TIMESTAMP

    def __init__(self, phone) -> None:
        super().__init__(phone, "proximo_alarme")

    @property
    def native_value(self) -> datetime | None:
        return self.phone.next_alarm


class LastEventSensor(AlarmeHaEntity, SensorEntity):
    def __init__(self, phone) -> None:
        super().__init__(phone, "ultimo_evento")

    @property
    def native_value(self) -> str | None:
        return self.phone.last_event.get("evento") if self.phone.last_event else None

    @property
    def extra_state_attributes(self) -> dict[str, Any] | None:
        if not self.phone.last_event:
            return None
        attrs = {k: v for k, v in self.phone.last_event.items() if k != "alarmes"}
        attrs["recebido_em"] = self.phone.last_seen.isoformat() if self.phone.last_seen else None
        return attrs


class LastReceivedSensor(AlarmeHaEntity, SensorEntity):
    """Quando chegou o último evento do celular (qualquer um)."""

    _attr_device_class = SensorDeviceClass.TIMESTAMP

    def __init__(self, phone) -> None:
        super().__init__(phone, "ultimo_evento_recebido")

    @property
    def native_value(self) -> datetime | None:
        return self.phone.last_seen

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        enviado = self.phone.last_command_at
        return {"ultimo_comando": enviado.isoformat() if enviado else None, "alerta_silencio": self.phone.alerta_silencio}


class AlarmsSensor(AlarmeHaEntity, SensorEntity):
    _attr_native_unit_of_measurement = "alarmes"

    def __init__(self, phone) -> None:
        super().__init__(phone, "alarmes")

    @property
    def native_value(self) -> int:
        return len(self.phone.alarms)

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return {
            "alarmes": self.phone.alarms,
            "ativos": sum(1 for a in self.phone.alarms if a.get("ativo")),
            "criados_pelo_ha": sum(1 for a in self.phone.alarms if a.get("criado_pelo_ha")),
        }
