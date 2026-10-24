"""Testes da integração AlarmeHA 0.3.0 (hub único, ativação por código, isolamento por usuário)."""

from __future__ import annotations

import asyncio
from pathlib import Path
from unittest.mock import patch

import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry, async_capture_events

from homeassistant.core import HomeAssistant, ServiceCall, Context
from homeassistant.exceptions import Unauthorized
from homeassistant.helpers import device_registry as dr
from homeassistant.setup import async_setup_component

from custom_components.alarmeha.const import DOMAIN
from custom_components.alarmeha.phone import encode_extras

URL = "https://casa.example.com"


def test_encode_extras():
    assert encode_extras({"op": "criar", "hora": 7, "ativo": True, "rotulo": "Aula, cedo"}) == (
        "op:criar:String.urlencoded,hora:7:int,ativo:true:boolean,rotulo:Aula%2C%20cedo:String.urlencoded"
    )


@pytest.fixture
def notify_calls(hass: HomeAssistant):
    calls: dict[str, list[ServiceCall]] = {"pixel_admin": [], "moto_filha": []}
    for name, lst in calls.items():
        async def handler(call: ServiceCall, lst=lst) -> None:
            lst.append(call)
        hass.services.async_register("notify", f"mobile_app_{name}", handler)
    return calls


@pytest.fixture
async def setup(hass: HomeAssistant, hass_admin_user, hass_read_only_user, notify_calls):
    MockConfigEntry(domain="mobile_app", data={"user_id": hass_admin_user.id, "device_name": "Pixel Admin"}).add_to_hass(hass)
    MockConfigEntry(domain="mobile_app", data={"user_id": hass_read_only_user.id, "device_name": "Moto Filha"}).add_to_hass(hass)
    await async_setup_component(hass, "http", {})
    entry = MockConfigEntry(domain=DOMAIN, version=2, unique_id=DOMAIN, data={"ha_url": URL}, title="AlarmeHA")
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


async def _ws(hass, hass_ws_client, token):
    return await hass_ws_client(hass, access_token=token)


async def _activate(hass, client_ws, http):
    await client_ws.send_json_auto_id({"type": "alarmeha/ativar"})
    msg = await client_ws.receive_json()
    assert msg["success"], msg
    res = msg["result"]
    assert res["deep_link"] == f"alarmeha://ativar?url=https%3A%2F%2Fcasa.example.com&codigo={res['codigo']}"
    assert res["intent_link"].startswith("intent://ativar?") and "scheme=alarmeha" in res["intent_link"]
    r = await http.post("/api/alarmeha/ativar", json={"codigo": res["codigo"], "pacote": "br.casa.alarmeha", "dispositivo": "Celular"})
    assert r.status == 200
    return res["codigo"], await r.json()


async def test_config_flow_single_entry(hass: HomeAssistant, setup):
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    assert result["type"] == "abort"


async def test_config_flow_creates(hass: HomeAssistant):
    await async_setup_component(hass, "http", {})
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"ha_url": "ftp://x"})
    assert result["errors"] == {"ha_url": "invalid_url"}
    with patch("custom_components.alarmeha.async_setup_entry", return_value=True):
        result = await hass.config_entries.flow.async_configure(result["flow_id"], {"ha_url": URL + "/"})
    assert result["type"] == "create_entry" and result["data"] == {"ha_url": URL}


async def test_activation_code_exchange(hass, setup, hass_ws_client, hass_access_token, hass_client_no_auth, hass_admin_user):
    ws = await _ws(hass, hass_ws_client, hass_access_token)
    http = await hass_client_no_auth()
    code, body = await _activate(hass, ws, http)
    assert body["url"] == URL and body["urls"][0] == URL and body["url_externa"] == URL and body["webhook"].startswith("alarmeha_") and len(body["token"]) >= 40

    # Código é de uso único.
    r = await http.post("/api/alarmeha/ativar", json={"codigo": code, "pacote": "br.casa.alarmeha"})
    assert r.status == 403
    # Pacote desconhecido é recusado.
    await ws.send_json_auto_id({"type": "alarmeha/ativar"})
    code2 = (await ws.receive_json())["result"]["codigo"]
    r = await http.post("/api/alarmeha/ativar", json={"codigo": code2, "pacote": "com.malicioso"})
    assert r.status == 403
    r = await http.post("/api/alarmeha/ativar", json={"lixo": 1})
    assert r.status == 400

    hub = hass.data[DOMAIN]
    (phone,) = hub.phones.values()
    assert phone.user_id == hass_admin_user.id
    assert phone.notify_service == "notify.mobile_app_pixel_admin"
    device = dr.async_get(hass).async_get_device(identifiers={(DOMAIN, phone.phone_id)})
    assert device is not None
    await hass.async_block_till_done()
    assert any(s.entity_id.startswith("binary_sensor.alarmeha") for s in hass.states.async_all())

    # Webhook recebe eventos do app.
    events = async_capture_events(hass, "alarmeha_evento")
    r = await http.post(f"/api/webhook/{body['webhook']}", json={"origem": "alarmeha", "evento": "pareado"})
    assert r.status == 200
    await hass.async_block_till_done()
    assert events[0].data["user_id"] == hass_admin_user.id and phone.connected


async def test_reactivation_reuses_device(hass, setup, hass_ws_client, hass_access_token, hass_client_no_auth):
    ws = await _ws(hass, hass_ws_client, hass_access_token)
    http = await hass_client_no_auth()
    _, first = await _activate(hass, ws, http)
    _, second = await _activate(hass, ws, http)
    hub = hass.data[DOMAIN]
    assert len(hub.phones) == 1
    assert first["token"] != second["token"] and first["webhook"] != second["webhook"]
    r = await http.post(f"/api/webhook/{first['webhook']}", json={"origem": "alarmeha", "evento": "lista"})
    assert r.status == 200  # webhook antigo não existe mais: HA responde 200 sem processar
    assert next(iter(hub.phones.values())).last_seen is None


async def test_code_expires(hass, setup, hass_ws_client, hass_access_token, hass_client_no_auth):
    ws = await _ws(hass, hass_ws_client, hass_access_token)
    http = await hass_client_no_auth()
    await ws.send_json_auto_id({"type": "alarmeha/ativar"})
    code = (await ws.receive_json())["result"]["codigo"]
    with patch("custom_components.alarmeha.hub.time.monotonic", return_value=10**9):
        r = await http.post("/api/alarmeha/ativar", json={"codigo": code, "pacote": "br.casa.alarmeha"})
    assert r.status == 403


async def test_rate_limit(hass, setup, hass_client_no_auth):
    http = await hass_client_no_auth()
    for _ in range(30):
        await http.post("/api/alarmeha/ativar", json={"codigo": "XXXX", "pacote": "br.casa.alarmeha"})
    r = await http.post("/api/alarmeha/ativar", json={"codigo": "XXXX", "pacote": "br.casa.alarmeha"})
    assert r.status == 429


async def test_per_user_isolation(
    hass, setup, hass_ws_client, hass_access_token, hass_read_only_access_token,
    hass_client_no_auth, hass_admin_user, hass_read_only_user, notify_calls,
):
    http = await hass_client_no_auth()
    ws_admin = await _ws(hass, hass_ws_client, hass_access_token)
    ws_user = await _ws(hass, hass_ws_client, hass_read_only_access_token)
    _, admin_body = await _activate(hass, ws_admin, http)
    _, user_body = await _activate(hass, ws_user, http)
    hub = hass.data[DOMAIN]
    admin_phone = next(p for p in hub.phones.values() if p.user_id == hass_admin_user.id)
    user_phone = next(p for p in hub.phones.values() if p.user_id == hass_read_only_user.id)
    assert user_phone.notify_service == "notify.mobile_app_moto_filha"

    # Status: usuário comum só vê o próprio celular; admin vê todos. Nunca expõe token/webhook.
    await ws_user.send_json_auto_id({"type": "alarmeha/status"})
    st = (await ws_user.receive_json())["result"]
    assert [c["id"] for c in st["celulares"]] == [user_phone.phone_id]
    assert user_body["token"] not in str(st) and user_body["webhook"] not in str(st)
    await ws_admin.send_json_auto_id({"type": "alarmeha/status"})
    st = (await ws_admin.receive_json())["result"]
    assert {c["id"] for c in st["celulares"]} == {admin_phone.phone_id, user_phone.phone_id}

    # Comando no celular de outra pessoa é recusado e nada é enviado.
    await ws_user.send_json_auto_id({"type": "alarmeha/comando", "celular": admin_phone.phone_id, "op": "listar"})
    msg = await ws_user.receive_json()
    assert not msg["success"] and msg["error"]["code"] == "unauthorized"
    await ws_user.send_json_auto_id({"type": "alarmeha/remover", "celular": admin_phone.phone_id})
    assert not (await ws_user.receive_json())["success"]
    assert notify_calls["pixel_admin"] == [] and admin_phone.phone_id in hub.phones

    # Comando no próprio celular funciona (o app responde pelo webhook).
    async def reply():
        for _ in range(50):
            await asyncio.sleep(0)
            if notify_calls["moto_filha"]:
                break
        extras = notify_calls["moto_filha"][-1].data["data"]["intent_extras"]
        pedido = next(p.split(":")[1] for p in extras.split(",") if p.startswith("pedido:"))
        alarme = {"id": 5, "ref": "aula", "hora": 6, "minuto": 30, "dias": 31, "ativo": True}
        await http.post(f"/api/webhook/{user_body['webhook']}", json={"origem": "alarmeha", "evento": "criado", "pedido": pedido, "alarme": alarme})

    task = hass.async_create_task(reply())
    await ws_user.send_json_auto_id({"type": "alarmeha/comando", "celular": user_phone.phone_id, "op": "criar",
                                     "dados": {"ref": "aula", "hora": 6, "minuto": 30, "dias": 31}})
    msg = await ws_user.receive_json()
    await task
    assert msg["success"], msg
    assert msg["result"]["celular"]["alarmes"][0]["ref"] == "aula"

    # Serviços: usuário comum não pode mirar o celular de outra pessoa.
    admin_device = dr.async_get(hass).async_get_device(identifiers={(DOMAIN, admin_phone.phone_id)})
    with pytest.raises(Unauthorized):
        await hass.services.async_call(DOMAIN, "listar", {"device_id": admin_device.id}, blocking=True,
                                       context=Context(user_id=hass_read_only_user.id))
    # Automação (sem usuário) pode.
    hass.async_create_task(hass.services.async_call(DOMAIN, "listar", {"device_id": admin_device.id, "timeout": 10}, blocking=False))
    for _ in range(20):
        await asyncio.sleep(0)
    assert notify_calls["pixel_admin"]


async def test_activate_without_companion(hass, setup, hass_ws_client, hass_access_token):
    for entry in hass.config_entries.async_entries("mobile_app"):
        hass.config_entries.async_update_entry(entry, data={**entry.data, "user_id": "outra-pessoa"})
    ws = await _ws(hass, hass_ws_client, hass_access_token)
    await ws.send_json_auto_id({"type": "alarmeha/ativar"})
    msg = await ws.receive_json()
    assert msg["error"]["code"] == "no_companion"


async def test_migration_from_v1(hass: HomeAssistant, notify_calls, hass_client_no_auth):
    await async_setup_component(hass, "http", {})
    old = MockConfigEntry(
        domain=DOMAIN, version=1, title="AlarmeHA Usuario",
        data={"pessoa": "Usuario", "notify_service": "notify.mobile_app_pixel_admin", "package": "br.casa.alarmeha",
              "webhook_id": "alarmeha_antigo", "token": "tok-antigo", "ha_url": URL},
    )
    old.add_to_hass(hass)
    second = MockConfigEntry(domain=DOMAIN, version=1, title="AlarmeHA Filha",
                             data={"pessoa": "Filha", "notify_service": "notify.mobile_app_moto_filha",
                                   "package": "br.casa.alarmeha", "webhook_id": "w2", "token": "t2", "ha_url": URL})
    second.add_to_hass(hass)
    assert await hass.config_entries.async_setup(old.entry_id)
    await hass.async_block_till_done()
    assert old.version == 2 and old.data == {"ha_url": URL}
    hub = hass.data[DOMAIN]
    phone = hub.phones[old.entry_id]
    assert phone.token == "tok-antigo" and phone.user_id is None
    http = await hass_client_no_auth()
    await http.post("/api/webhook/alarmeha_antigo", json={"origem": "alarmeha", "evento": "lista", "alarmes": []})
    assert phone.last_seen is not None
    # A segunda entrada antiga não migra: o usuário é avisado para removê-la.
    assert second.version == 1
    assert second.state.name == "MIGRATION_ERROR"


APK_BYTES = b"PK\x03\x04" + b"0" * 2000
LATEST = "https://api.github.com/repos/gustavvelasquez/AlarmeHA/releases/latest"
ASSET = "https://github.com/gustavvelasquez/AlarmeHA/releases/download/v0.3.1/AlarmeHA.apk"
ASSET_API = "https://api.github.com/repos/gustavvelasquez/AlarmeHA/releases/assets/1"


async def test_apk_served_by_ha(hass, setup, hass_ws_client, hass_access_token, hass_client_no_auth, aioclient_mock):
    import shutil
    shutil.rmtree(hass.config.path(".storage", "alarmeha_apk"), ignore_errors=True)
    aioclient_mock.get(LATEST, json={"tag_name": "v0.3.1", "assets": [
        {"name": "AlarmeHA.apk", "size": len(APK_BYTES), "url": ASSET_API, "browser_download_url": ASSET}]})
    aioclient_mock.get(ASSET_API, content=APK_BYTES)
    ws = await _ws(hass, hass_ws_client, hass_access_token)
    await ws.send_json_auto_id({"type": "alarmeha/status"})
    st = (await ws.receive_json())["result"]
    assert st["apk_url"].startswith("/api/alarmeha/apk?authSig=")
    assert st["releases_url"].endswith("/releases/latest")

    http = await hass_client_no_auth()
    assert (await http.get("/api/alarmeha/apk")).status == 401  # sem assinatura
    r = await http.get(st["apk_url"])
    assert r.status == 200
    assert r.headers["Content-Type"] == "application/vnd.android.package-archive"
    assert r.headers["Content-Disposition"] == 'attachment; filename="AlarmeHA-v0.3.1.apk"'
    assert int(r.headers["Content-Length"]) == len(APK_BYTES)
    assert await r.read() == APK_BYTES

    # Segunda vez vem do cache (sem novo download do GitHub).
    calls = aioclient_mock.call_count
    assert (await http.get(st["apk_url"])).status == 200
    assert aioclient_mock.call_count == calls
    # 0.14.1: baixa pela API de assets, pedindo o binário; sem aviso no painel.
    dl = [c for c in aioclient_mock.mock_calls if str(c[1]) == ASSET_API]
    assert dl and dl[0][3]["Accept"] == "application/octet-stream"
    await ws.send_json_auto_id({"type": "alarmeha/status"})
    assert (await ws.receive_json())["result"]["apk_aviso"] is None


async def test_apk_fallback_shows_warning(hass, setup, hass_ws_client, hass_access_token, hass_client_no_auth, aioclient_mock):
    """Download da versão nova falha: serve a cópia antiga e avisa o painel."""
    import shutil
    d = Path(hass.config.path(".storage", "alarmeha_apk"))
    shutil.rmtree(d, ignore_errors=True)
    d.mkdir(parents=True)
    (d / "AlarmeHA-v0.3.0.apk").write_bytes(APK_BYTES)
    asset = "https://api.github.com/repos/gustavvelasquez/AlarmeHA/releases/assets/2"
    aioclient_mock.get(LATEST, json={"tag_name": "v0.3.1", "assets": [
        {"name": "AlarmeHA.apk", "size": len(APK_BYTES) + 1, "url": asset, "browser_download_url": ASSET}]})
    aioclient_mock.get(asset, status=400)
    ws = await _ws(hass, hass_ws_client, hass_access_token)
    await ws.send_json_auto_id({"type": "alarmeha/status"})
    url = (await ws.receive_json())["result"]["apk_url"]
    http = await hass_client_no_auth()
    r = await http.get(url)
    assert r.status == 200
    assert r.headers["Content-Disposition"] == 'attachment; filename="AlarmeHA-v0.3.0.apk"'
    assert r.headers["X-AlarmeHA-Aviso"] == "servida=v0.3.0; nova=v0.3.1"
    await ws.send_json_auto_id({"type": "alarmeha/status"})
    aviso = (await ws.receive_json())["result"]["apk_aviso"]
    assert aviso["servida"] == "v0.3.0" and aviso["nova"] == "v0.3.1"
    assert aviso["erro"] and aviso["release_url"].endswith("/releases/latest")
    shutil.rmtree(d, ignore_errors=True)


async def test_apk_github_down(hass, setup, hass_ws_client, hass_access_token, hass_client_no_auth, aioclient_mock):
    import shutil
    shutil.rmtree(hass.config.path(".storage", "alarmeha_apk"), ignore_errors=True)
    aioclient_mock.get(LATEST, status=503)
    ws = await _ws(hass, hass_ws_client, hass_access_token)
    await ws.send_json_auto_id({"type": "alarmeha/status"})
    url = (await ws.receive_json())["result"]["apk_url"]
    http = await hass_client_no_auth()
    assert (await http.get(url)).status == 502


async def test_activation_uses_panel_origin(hass, setup, hass_ws_client, hass_access_token, hass_client_no_auth):
    await hass.config.async_update(internal_url="http://homeassistant.local:8123")
    ws = await _ws(hass, hass_ws_client, hass_access_token)
    await ws.send_json_auto_id({"type": "alarmeha/ativar", "origem": "https://abc.ui.nabu.casa"})
    res = (await ws.receive_json())["result"]
    assert res["url"] == "https://abc.ui.nabu.casa"
    assert "url=https%3A%2F%2Fabc.ui.nabu.casa&" in res["deep_link"]
    http = await hass_client_no_auth()
    r = await http.post("/api/alarmeha/ativar", json={"codigo": res["codigo"], "pacote": "br.casa.alarmeha"})
    body = await r.json()
    assert body["urls"] == ["https://abc.ui.nabu.casa", URL, "http://homeassistant.local:8123"]
    assert body["url_interna"] == "http://homeassistant.local:8123"

    # Origem inválida cai na URL externa.
    await ws.send_json_auto_id({"type": "alarmeha/ativar", "origem": "javascript:alert(1)"})
    assert (await ws.receive_json())["result"]["url"] == URL


async def test_panel_versioned(hass, setup):
    from homeassistant.components.frontend import DATA_PANELS
    from custom_components.alarmeha.const import VERSION
    panel = hass.data[DATA_PANELS]["despertador"]
    cfg = panel.config["_panel_custom"]
    assert cfg["name"] == "alarmeha-panel-" + VERSION.replace(".", "-")
    assert cfg["module_url"].endswith("?v=" + VERSION)
    assert panel.require_admin is False


async def test_reactivation_replaces_stale_phone(hass, setup, hass_ws_client, hass_access_token, hass_client_no_auth, hass_admin_user):
    hub = hass.data[DOMAIN]
    # Celular antigo do mesmo aparelho (ex.: migrado da 0.2.x, sem usuário e sem entrada do Companion).
    hub.add_legacy_phone({"phone_id": "antigo", "user_id": None, "person": "Usuario", "device_name": "Celular",
                          "mobile_entry_id": None, "notify_service": "notify.mobile_app_pixel_admin",
                          "package": "br.casa.alarmeha", "webhook_id": "alarmeha_velho", "token": "velho"})
    hub.add_legacy_phone({"phone_id": "outro", "user_id": None, "person": "Filha", "device_name": "Moto",
                          "mobile_entry_id": None, "notify_service": "notify.mobile_app_moto_filha",
                          "package": "br.casa.alarmeha", "webhook_id": "alarmeha_outro", "token": "x"})
    ws = await _ws(hass, hass_ws_client, hass_access_token)
    http = await hass_client_no_auth()
    await ws.send_json_auto_id({"type": "alarmeha/ativar"})
    res = (await ws.receive_json())["result"]
    r = await http.post("/api/alarmeha/ativar", json={"codigo": res["codigo"], "pacote": "br.casa.alarmeha"})
    assert r.status == 200
    assert set(hub.phones) == {"antigo", "outro"}  # reaproveitou o antigo, não duplicou
    phone = hub.phones["antigo"]
    assert phone.user_id == hass_admin_user.id and phone.token != "velho"
    await ws.send_json_auto_id({"type": "alarmeha/status"})
    mine = [c for c in (await ws.receive_json())["result"]["celulares"] if c["meu"]]
    assert len(mine) == 1 and mine[0]["ativado_em"] >= res["criado_em"]
