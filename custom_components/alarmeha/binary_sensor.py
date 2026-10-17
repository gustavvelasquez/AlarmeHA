"""Sensor binário 'conectado' do AlarmeHA (idade do último evento recebido)."""

from __future__ import annotations

from datetime import timedelta

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.event import async_track_time_interval
from homeassistant.util import dt as dt_util

from . import AlarmeHaConfigEntry
from .const import CONNECTED_MAX_AGE_HOURS
from .entity import AlarmeHaEntity


async def async_setup_entry(
    hass: HomeAssistant, entry: AlarmeHaConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    async_add_entities([ConnectedSensor(entry.runtime_data)])


class ConnectedSensor(AlarmeHaEntity, BinarySensorEntity):
    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY

    def __init__(self, phone) -> None:
        super().__init__(phone, "conectado")

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.async_on_remove(
            async_track_time_interval(self.hass, self._refresh, timedelta(minutes=10))
        )

    @callback
    def _refresh(self, _now) -> None:
        self.async_write_ha_state()

    @property
    def is_on(self) -> bool:
        seen = self.phone.last_seen
        return seen is not None and dt_util.utcnow() - seen < timedelta(hours=CONNECTED_MAX_AGE_HOURS)

    @property
    def extra_state_attributes(self):
        return {"ultimo_contato": self.phone.last_seen.isoformat() if self.phone.last_seen else None}
