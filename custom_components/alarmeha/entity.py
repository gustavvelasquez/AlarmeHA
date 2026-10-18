"""Base das entidades AlarmeHA (um dispositivo por celular ativado)."""

from __future__ import annotations

from collections.abc import Callable

from homeassistant.core import callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity

from .const import DOMAIN, SIGNAL_NEW_PHONE
from .phone import AlarmeHaPhone


class AlarmeHaEntity(Entity):
    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, phone: AlarmeHaPhone, key: str) -> None:
        self.phone = phone
        self._attr_translation_key = key
        self._attr_unique_id = f"{phone.phone_id}_{key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, phone.phone_id)},
            name=f"AlarmeHA {phone.person}",
            manufacturer="AlarmeHA",
            model=phone.device_name or phone.package,
        )

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(self.hass, self.phone.signal, self.async_write_ha_state)
        )


def setup_dynamic(hass, entry, async_add_entities, build: Callable[[AlarmeHaPhone], list[Entity]]) -> None:
    """Adiciona entidades dos celulares atuais e dos que forem ativados depois."""
    hub = entry.runtime_data
    known: set[str] = set()

    @callback
    def add(phone: AlarmeHaPhone) -> None:
        if phone.phone_id in known:
            return
        known.add(phone.phone_id)
        async_add_entities(build(phone))

    for phone in list(hub.phones.values()):
        add(phone)
    entry.async_on_unload(async_dispatcher_connect(hass, SIGNAL_NEW_PHONE, add))
