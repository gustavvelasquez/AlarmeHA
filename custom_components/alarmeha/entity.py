"""Base das entidades AlarmeHA."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity

from .const import CONF_PACKAGE, DOMAIN
from .phone import AlarmeHaPhone


class AlarmeHaEntity(Entity):
    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, phone: AlarmeHaPhone, key: str) -> None:
        self.phone = phone
        self._attr_translation_key = key
        self._attr_unique_id = f"{phone.entry.entry_id}_{key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, phone.entry.entry_id)},
            name=f"AlarmeHA {phone.person}",
            manufacturer="AlarmeHA",
            model=phone.entry.data[CONF_PACKAGE],
        )

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(self.hass, self.phone.signal, self.async_write_ha_state)
        )
