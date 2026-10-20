"""Hub do AlarmeHA: guarda os celulares pareados, códigos de ativação e webhooks."""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
import logging
import secrets
import time
from typing import Any
from urllib.parse import quote, urlparse

from aiohttp import web

from homeassistant.auth.models import User
from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.network import NoURLAvailableError, get_url
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util, slugify

from .const import (
    RELEASES_URL,
    CODE_TTL_SECONDS,
    CONF_HA_URL,
    DOMAIN,
    MAX_FAILED_ACTIVATIONS,
    PACKAGES,
    SIGNAL_NEW_PHONE,
)
from .phone import AlarmeHaPhone


def clean_url(url: str | None) -> str | None:
    if not url:
        return None
    url = url.strip().rstrip("/")
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.path or parsed.query:
        return None
    return url

_LOGGER = logging.getLogger(__name__)
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


class ActivationError(HomeAssistantError):
    """Erro ao ativar um celular."""


@dataclass
class PendingCode:
    user_id: str
    mobile_entry_id: str
    expires: float
    origin: str


class AlarmeHaHub:
    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass = hass
        self.entry = entry
        self._store: Store[dict[str, Any]] = Store(hass, 1, f"{DOMAIN}.phones")
        self.phones: dict[str, AlarmeHaPhone] = {}
        self._codes: dict[str, PendingCode] = {}
        self._failures: deque[float] = deque()

    @property
    def external_url(self) -> str:
        return self.entry.options.get(CONF_HA_URL) or self.entry.data.get(CONF_HA_URL, "")

    # ---- persistência --------------------------------------------------------------

    async def async_load(self) -> None:
        data = await self._store.async_load() or {}
        for phone_id, pdata in (data.get("phones") or {}).items():
            try:
                self.phones[phone_id] = AlarmeHaPhone(self.hass, self, phone_id, pdata)
            except KeyError:
                _LOGGER.warning("AlarmeHA: celular %s com dados incompletos ignorado", phone_id)

    @callback
    def async_save(self) -> None:
        self._store.async_delay_save(
            lambda: {"phones": {pid: p.as_dict() for pid, p in self.phones.items()}}, 5
        )

    # ---- webhooks ------------------------------------------------------------------

    @callback
    def register_webhook(self, phone: AlarmeHaPhone) -> None:
        async def handle(hass: HomeAssistant, webhook_id: str, request: web.Request) -> web.Response:
            try:
                payload = await request.json()
            except ValueError:
                return web.Response(status=400)
            if isinstance(payload, dict):
                phone.handle_event(payload)
            return web.Response(status=200)

        webhook.async_register(
            self.hass, DOMAIN, f"AlarmeHA {phone.person}", phone.webhook_id, handle,
            local_only=False, allowed_methods=["POST"],
        )

    @callback
    def unregister_webhooks(self) -> None:
        for phone in self.phones.values():
            webhook.async_unregister(self.hass, phone.webhook_id)

    # ---- usuários e celulares do Companion -----------------------------------------

    def mobile_devices_for_user(self, user_id: str) -> list[dict[str, str]]:
        devices = []
        for entry in self.hass.config_entries.async_entries("mobile_app"):
            if entry.data.get("user_id") != user_id:
                continue
            name = entry.data.get("device_name") or entry.title
            devices.append(
                {"entry_id": entry.entry_id, "nome": name, "notify_service": f"notify.mobile_app_{slugify(name)}"}
            )
        return devices

    def person_name(self, user: User | None, user_id: str) -> str:
        for state in self.hass.states.async_all("person"):
            if state.attributes.get("user_id") == user_id:
                return state.attributes.get("friendly_name") or state.name
        return (user.name if user else None) or "Pessoa"

    @staticmethod
    def can_access(user: User | None, phone: AlarmeHaPhone) -> bool:
        """Admin vê tudo; os demais só o próprio celular."""
        if user is None:
            return False
        return user.is_admin or (phone.user_id is not None and phone.user_id == user.id)

    def phones_for(self, user: User) -> list[AlarmeHaPhone]:
        return [p for p in self.phones.values() if self.can_access(user, p)]

    # ---- ativação ------------------------------------------------------------------

    @property
    def internal_url(self) -> str | None:
        try:
            return get_url(self.hass, allow_external=False, allow_cloud=False).rstrip("/")
        except NoURLAvailableError:
            return None

    def urls_for(self, origin: str | None) -> list[str]:
        """URLs que o app pode usar, na ordem de tentativa: a da ativação, a externa e a interna."""
        urls: list[str] = []
        for url in (origin, self.external_url, self.internal_url):
            url = clean_url(url)
            if url and url not in urls:
                urls.append(url)
        return urls

    def create_code(self, user_id: str, mobile_entry_id: str, origin: str | None = None) -> dict[str, Any]:
        now = time.monotonic()
        self._codes = {c: p for c, p in self._codes.items() if p.expires > now and p.user_id != user_id}
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(10))
        # A URL que a pessoa está usando agora (Nabu Casa, IP local, proxy...) vem primeiro;
        # a URL externa configurada é o reserva.
        url = clean_url(origin) or clean_url(self.external_url) or ""
        self._codes[code] = PendingCode(user_id, mobile_entry_id, now + CODE_TTL_SECONDS, url)
        query = f"url={quote(url, safe='')}&codigo={code}"
        return {
            "codigo": code,
            "criado_em": dt_util.utcnow().isoformat(),
            "expira_em": CODE_TTL_SECONDS,
            "url": url,
            "deep_link": f"alarmeha://ativar?{query}",
            "intent_link": (
                f"intent://ativar?{query}#Intent;scheme=alarmeha;"
                f"S.browser_fallback_url={quote(RELEASES_URL, safe='')};end"
            ),
            "texto_pareamento": f'{{"v":2,"url":"{url}","codigo":"{code}"}}',
        }

    def too_many_failures(self) -> bool:
        now = time.monotonic()
        while self._failures and self._failures[0] < now - CODE_TTL_SECONDS:
            self._failures.popleft()
        return len(self._failures) >= MAX_FAILED_ACTIVATIONS

    async def async_redeem(self, code: str, package: str, device_label: str) -> tuple[AlarmeHaPhone, list[str]]:
        """Troca um código de uso único por webhook + token e cria (ou atualiza) o celular."""
        pending = self._codes.pop(code.strip().upper(), None)
        if pending is None or pending.expires < time.monotonic():
            self._failures.append(time.monotonic())
            raise ActivationError("codigo_invalido")
        urls = self.urls_for(pending.origin)
        if package not in PACKAGES:
            raise ActivationError("pacote_invalido")

        mobile = next(
            (d for d in self.mobile_devices_for_user(pending.user_id) if d["entry_id"] == pending.mobile_entry_id),
            None,
        )
        if mobile is None:
            raise ActivationError("companion_nao_encontrado")
        user = await self.hass.auth.async_get_user(pending.user_id)

        # O mesmo celular (mesma entrada do Companion ou mesmo serviço notify) é substituído, não duplicado.
        same = [
            p for p in self.phones.values()
            if p.mobile_entry_id == pending.mobile_entry_id
            or (p.notify_service == mobile["notify_service"] and p.user_id in (None, pending.user_id))
        ]
        same.sort(key=lambda p: p.mobile_entry_id != pending.mobile_entry_id)
        existing = same[0] if same else None
        for stale in same[1:]:
            await self.async_remove_phone(stale.phone_id)
        if existing:
            webhook.async_unregister(self.hass, existing.webhook_id)
            phone_id = existing.phone_id
        else:
            phone_id = secrets.token_hex(8)

        phone = AlarmeHaPhone(
            self.hass,
            self,
            phone_id,
            {
                "user_id": pending.user_id,
                "person": self.person_name(user, pending.user_id),
                "device_name": device_label[:60] or mobile["nome"],
                "mobile_entry_id": pending.mobile_entry_id,
                "notify_service": mobile["notify_service"],
                "package": package,
                "webhook_id": "alarmeha_" + secrets.token_urlsafe(24),
                "token": secrets.token_urlsafe(32),
                # Pareado de novo (ex.: app reinstalado): a lista, os envios, os pulos e os
                # "ocupados" do aparelho antigo não valem mais. A configuração da agenda fica.
                "alarms": [],
                "agenda": {k: v for k, v in (existing.agenda if existing else {}).items() if k != "_enviados"},
                "toques": existing.toques if existing else [],
                "pulados": {},
                "activated_at": dt_util.utcnow().isoformat(),
            },
        )
        self.phones[phone_id] = phone
        self.register_webhook(phone)
        self.async_save()
        if not existing:
            async_dispatcher_send(self.hass, SIGNAL_NEW_PHONE, phone)
        else:
            async_dispatcher_send(self.hass, phone.signal)
        # Reserva caso o aviso "pareado" do app se perca: reenvia o plano logo após parear.
        phone.sincronizar_depois(30)
        return phone, urls

    @callback
    def add_legacy_phone(self, data: dict[str, Any]) -> None:
        """Importa o celular de uma entrada da versão 1 (sem usuário: só admins veem)."""
        if any(p.webhook_id == data["webhook_id"] for p in self.phones.values()):
            return
        # Mantém o id da entrada antiga: entidades e dispositivo continuam os mesmos.
        phone_id = data.get("phone_id") or secrets.token_hex(8)
        self.phones[phone_id] = AlarmeHaPhone(self.hass, self, phone_id, data)
        async_dispatcher_send(self.hass, SIGNAL_NEW_PHONE, self.phones[phone_id])
        self.async_save()

    async def async_remove_phone(self, phone_id: str) -> None:
        phone = self.phones.pop(phone_id, None)
        if phone is None:
            return
        webhook.async_unregister(self.hass, phone.webhook_id)
        registry = dr.async_get(self.hass)
        if device := registry.async_get_device(identifiers={(DOMAIN, phone_id)}):
            registry.async_remove_device(device.id)
        self.async_save()

    def phone_for_device(self, device_id: str) -> AlarmeHaPhone | None:
        device = dr.async_get(self.hass).async_get(device_id)
        if device is None:
            return None
        for domain, ident in device.identifiers:
            if domain == DOMAIN and ident in self.phones:
                return self.phones[ident]
        return None
