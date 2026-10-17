import asyncio
from unittest.mock import patch
from urllib.parse import unquote

from homeassistant import config_entries
from homeassistant.core import HomeAssistant
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import async_mock_service, async_capture_events

from custom_components.alarmeha.const import DOMAIN
from custom_components.alarmeha.phone import encode_extras

TOKEN = "A" * 43


def test_encode_extras():
    s = encode_extras({"token": "ab-_", "op": "criar", "hora": 6, "ativo": True, "rotulo": "Aula 07:45, sala"})
    assert s == "token:ab-_:String.urlencoded,op:criar:String.urlencoded,hora:6:int,ativo:true:boolean,rotulo:Aula%2007%3A45%2C%20sala:String.urlencoded"


async def _setup(hass: HomeAssistant):
    await async_setup_component(hass, "http", {})
    await async_setup_component(hass, "webhook", {})
    calls = async_mock_service(hass, "notify", "mobile_app_pixel")
    hass.config.external_url = "https://ha.example.com"
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    assert result["type"] == "form" and result["step_id"] == "user"
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"],
        {"pessoa": "Usuario", "notify_service": "notify.mobile_app_pixel", "package": "br.casa.alarmeha.debug",
         "ha_url": "https://ha.example.com/"},
    )
    assert result["step_id"] == "pair"
    ph = result["description_placeholders"]
    assert ph["qr"].startswith("![QR](data:image/svg+xml")
    assert '"url":"https://ha.example.com"' in ph["pairing"]
    bad = await hass.config_entries.flow.async_configure(result["flow_id"], {"token": "curto"})
    assert bad["errors"] == {"token": "invalid_token"}
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"token": TOKEN})
    assert result["type"] == "create_entry"
    await hass.async_block_till_done()
    return result["result"], calls


async def test_flow_and_events(hass: HomeAssistant, hass_client_no_auth):
    entry, calls = await _setup(hass)
    events = async_capture_events(hass, "alarmeha_evento")
    client = await hass_client_no_auth()
    url = f"/api/webhook/{entry.data['webhook_id']}"
    resp = await client.post(url, json={
        "origem": "alarmeha", "evento": "lista", "pedido": None,
        "alarmes": [{"ref": "g_aula", "id": 3, "hora": 6, "minuto": 30, "dias": 31, "rotulo": "Aula",
                     "ativo": True, "criado_pelo_ha": True, "proximo_disparo": "2099-01-01T06:30:00-03:00"},
                    {"ref": None, "id": 4, "ativo": False, "proximo_disparo": None}]})
    assert resp.status == 200
    await hass.async_block_till_done()
    assert len(events) == 1 and events[0].data["pessoa"] == "Usuario"
    states = {s.entity_id: s for s in hass.states.async_all()}
    alarms = states["sensor.alarmeha_usuario_alarms"]
    assert alarms.state == "2" and alarms.attributes["ativos"] == 1
    nxt = states["sensor.alarmeha_usuario_next_alarm"]
    assert nxt.state.startswith("2099-01-01T09:30:00")
    conn = next(s for k, s in states.items() if k.startswith("binary_sensor."))
    assert conn.state == "on"
    ultimo = states["sensor.alarmeha_usuario_last_event"]
    assert ultimo.state == "lista"


async def test_service_with_confirmation_and_retry(hass: HomeAssistant, hass_client_no_auth):
    entry, calls = await _setup(hass)
    client = await hass_client_no_auth()
    url = f"/api/webhook/{entry.data['webhook_id']}"
    calls.clear()

    task = hass.async_create_task(hass.services.async_call(
        DOMAIN, "criar", {"ref": "g_aula", "hora": 6, "minuto": 30, "dias": 31, "rotulo": "Aula 07:45", "timeout": 10},
        blocking=True, return_response=True))
    for _ in range(200):
        await asyncio.sleep(0.01)
        crit = [c for c in calls if "op:criar" in c.data["data"]["intent_extras"]]
        if crit:
            break
    assert len(crit) == 1
    data = crit[0].data
    assert data["message"] == "command_broadcast_intent"
    assert data["data"]["intent_package_name"] == "br.casa.alarmeha.debug"
    assert data["data"]["intent_action"] == "br.casa.alarmeha.action.COMANDO"
    extras = dict(p.rsplit(":", 1)[0].split(":", 1) for p in data["data"]["intent_extras"].split(","))
    assert extras["token"] == TOKEN and extras["op"] == "criar" and extras["hora"] == "6"
    assert unquote(extras["rotulo"]) == "Aula 07:45"
    pedido = extras["pedido"]
    resp = await client.post(url, json={"origem": "alarmeha", "evento": "criado", "pedido": pedido, "ref": "g_aula", "id": 9,
                                        "alarme": {"ref": "g_aula", "id": 9, "ativo": True}})
    assert resp.status == 200
    result = await task
    assert result["evento"] == "criado"


async def test_service_error_and_timeout(hass: HomeAssistant, hass_client_no_auth):
    import pytest
    from homeassistant.exceptions import HomeAssistantError
    entry, calls = await _setup(hass)
    real_timeout = asyncio.timeout
    with patch("custom_components.alarmeha.phone.asyncio.timeout", side_effect=lambda t: real_timeout(0.01)):
        calls.clear()
        with pytest.raises(HomeAssistantError, match="3 tentativas"):
            await hass.services.async_call(DOMAIN, "desligar", {"ref": "*", "timeout": 10}, blocking=True)
        assert len([c for c in calls if "op:desligar" in c.data["data"]["intent_extras"]]) == 3
    client = await hass_client_no_auth()
    url = f"/api/webhook/{entry.data['webhook_id']}"
    task = hass.async_create_task(hass.services.async_call(DOMAIN, "apagar", {"ref": "x1", "timeout": 10}, blocking=True))
    for _ in range(200):
        await asyncio.sleep(0.01)
        ap = [c for c in calls if "op:apagar" in c.data["data"]["intent_extras"]]
        if ap:
            break
    pedido = [p for p in ap[0].data["data"]["intent_extras"].split(",") if p.startswith("pedido:")][0].split(":")[1]
    await client.post(url, json={"origem": "alarmeha", "evento": "erro", "pedido": pedido, "op": "apagar", "ref": "x1", "motivo": "ref_inexistente"})
    with pytest.raises(HomeAssistantError, match="ref_inexistente"):
        await task
