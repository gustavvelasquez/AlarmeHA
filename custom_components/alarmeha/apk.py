"""Entrega do APK pelo próprio Home Assistant (/api/alarmeha/apk).

O HA baixa o AlarmeHA.apk da última release do GitHub, guarda uma cópia local e serve com
Content-Type/Content-Disposition corretos, na mesma origem do painel. Assim o download acontece
dentro do app Companion, sem redirecionamentos para release-assets.githubusercontent.com.
"""

from __future__ import annotations

import asyncio
from http import HTTPStatus
import logging
from pathlib import Path
import time

from aiohttp import ClientError, ClientTimeout, web

from homeassistant.components.http import KEY_HASS, HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .const import APK_CACHE_HOURS, APK_PATH, GITHUB_LATEST_API

_LOGGER = logging.getLogger(__name__)
APK_MIME = "application/vnd.android.package-archive"
ASSET_NAME = "AlarmeHA.apk"


class ApkCache:
    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass
        self.dir = Path(hass.config.path(".storage", "alarmeha_apk"))
        self._lock = asyncio.Lock()
        self._checked = 0.0
        self.tag: str | None = None
        self.path: Path | None = None

    async def async_get(self) -> tuple[Path, str]:
        async with self._lock:
            if self.path and self.path.exists() and time.monotonic() - self._checked < APK_CACHE_HOURS * 3600:
                return self.path, self.tag or ""
            try:
                await self._refresh()
            except (ClientError, TimeoutError, ValueError, KeyError) as err:
                cached = await self.hass.async_add_executor_job(self._latest_cached)
                if cached is None:
                    raise
                _LOGGER.warning("AlarmeHA: GitHub indisponível (%s); usando APK em cache", err)
                self.path, self.tag = cached
            return self.path, self.tag or ""

    def _latest_cached(self) -> tuple[Path, str] | None:
        files = sorted(self.dir.glob("AlarmeHA-*.apk"), key=lambda p: p.stat().st_mtime) if self.dir.exists() else []
        return (files[-1], files[-1].stem.removeprefix("AlarmeHA-")) if files else None

    async def _refresh(self) -> None:
        session = async_get_clientsession(self.hass)
        timeout = ClientTimeout(total=120)
        async with session.get(GITHUB_LATEST_API, headers={"Accept": "application/vnd.github+json"}, timeout=timeout) as resp:
            resp.raise_for_status()
            release = await resp.json()
        tag = str(release["tag_name"])
        asset = next(a for a in release["assets"] if a["name"] == ASSET_NAME)
        size = int(asset["size"])
        target = self.dir / f"AlarmeHA-{tag}.apk"

        if not (await self.hass.async_add_executor_job(_size_of, target)) == size:
            async with session.get(asset["browser_download_url"], timeout=timeout) as resp:
                resp.raise_for_status()
                data = await resp.read()
            if len(data) != size or data[:2] != b"PK":
                raise ValueError("APK baixado do GitHub está incompleto")
            await self.hass.async_add_executor_job(self._store, target, data)
        self.path, self.tag, self._checked = target, tag, time.monotonic()

    def _store(self, target: Path, data: bytes) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        tmp = target.with_suffix(".part")
        tmp.write_bytes(data)
        tmp.replace(target)
        for old in self.dir.glob("AlarmeHA-*.apk"):
            if old != target:
                old.unlink(missing_ok=True)


def _size_of(path: Path) -> int:
    return path.stat().st_size if path.exists() else -1


class AlarmeHaApkView(HomeAssistantView):
    url = APK_PATH
    name = "api:alarmeha:apk"
    requires_auth = True  # o painel usa um link assinado (authSig) de curta duração

    def __init__(self, cache: ApkCache) -> None:
        self.cache = cache

    async def get(self, request: web.Request) -> web.StreamResponse:
        try:
            path, tag = await self.cache.async_get()
        except Exception as err:  # noqa: BLE001
            _LOGGER.error("AlarmeHA: não foi possível obter o APK: %s", err)
            return self.json_message("APK indisponível; use a página da release", HTTPStatus.BAD_GATEWAY)
        name = f"AlarmeHA-{tag}.apk" if tag else ASSET_NAME
        return web.FileResponse(
            path,
            headers={
                "Content-Type": APK_MIME,
                "Content-Disposition": f'attachment; filename="{name}"',
                "Cache-Control": "no-store",
                "X-Content-Type-Options": "nosniff",
            },
        )
