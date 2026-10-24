"""Modo diagnóstico (0.10.0): registro circular de 500 eventos, fora do barramento, persistente."""

from __future__ import annotations

import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry, async_capture_events

from homeassistant.core import Context, HomeAssistant
from homeassistant.exceptions import Unauthorized
from homeassistant.setup import async_setup_component

from custom_components.alarmeha.const import DOMAIN
from custom_components.alarmeha.diagnostico import MAX_EVENTOS, DiagLog, limpar
from custom_components.alarmeha.diagnostics import async_get_config_entry_diagnostics

from .test_alarmeha import _activate, _ws, setup, notify_calls  # noqa: F401

URL = "https://casa.example.com"


def _diag(tipo: str, **extra):
    return {"origem": "alarmeha", "evento": "diagnostico", "tipo": tipo, "versao": "0.10.0",
            "quando": "2026-01-01T07:00:00-03:00", "pessoa": "Pessoa", "dispositivo": "Celular", **extra}


def test_limpar_remove_dados_pessoais():
    out = limpar(_diag("tocou", ref="ag_trabalho_1", rotulo="Reunião", hora=7, alarme={"x": 1}))
    assert out == {"tipo": "tocou", "versao": "0.10.0", "quando": "2026-01-01T07:00:00-03:00", "ref": "ag_trabalho_1", "hora": 7}


async def test_registro_circular_e_persistente(hass: HomeAssistant, hass_storage):
    log = DiagLog(hass)
    await log.async_load()
    for i in range(MAX_EVENTOS + 20):
        log.add("cel1", _diag("agendado", id=i))
    eventos = log.listar()
    assert len(eventos) == MAX_EVENTOS and eventos[0]["id"] == 20 and eventos[-1]["id"] == MAX_EVENTOS + 19
    assert log.listar(5)[0]["id"] == MAX_EVENTOS + 15
    await log.async_flush()
    # "Reinício": outro registro lê o mesmo armazenamento.
    novo = DiagLog(hass)
    await novo.async_load()
    assert [e["id"] for e in novo.listar()] == [e["id"] for e in eventos]


async def test_webhook_servico_e_diagnostics(hass, setup, hass_ws_client, hass_access_token, hass_client_no_auth,
                                             hass_admin_user, hass_read_only_user):
    ws = await _ws(hass, hass_ws_client, hass_access_token)
    http = await hass_client_no_auth()
    _, body = await _activate(hass, ws, http)
    eventos_bus = async_capture_events(hass, "alarmeha_evento")
    for tipo in ("recebido", "agendado", "tocou", "soneca", "pulou", "dispensou", "erro"):
        r = await http.post(f"/api/webhook/{body['webhook']}", json=_diag(tipo, ref="ag_x_1", mensagem="falha" if tipo == "erro" else None))
        assert r.status == 200
    await hass.async_block_till_done()
    assert eventos_bus == []  # não vai para o barramento (nem para o histórico)

    res = await hass.services.async_call(DOMAIN, "diagnostico", {}, blocking=True, return_response=True,
                                         context=Context(user_id=hass_admin_user.id))
    assert res["total"] == 7 and [e["tipo"] for e in res["eventos"]][-1] == "erro"
    assert all("pessoa" not in e and "dispositivo" not in e for e in res["eventos"])
    res = await hass.services.async_call(DOMAIN, "diagnostico", {"limite": 2}, blocking=True, return_response=True)
    assert [e["tipo"] for e in res["eventos"]] == ["dispensou", "erro"]
    with pytest.raises(Unauthorized):
        await hass.services.async_call(DOMAIN, "diagnostico", {}, blocking=True, return_response=True,
                                       context=Context(user_id=hass_read_only_user.id))

    diag = await async_get_config_entry_diagnostics(hass, setup)
    assert len(diag["eventos"]) == 7 and diag["versao"]
    assert body["token"] not in str(diag) and body["webhook"] not in str(diag)


def test_limpar_mantem_origem_do_pulo_e_grupo():
    """0.13.0: pulou/religado trazem de onde veio o pulo e o grupo; perdido entra no registro."""
    out = limpar(_diag("pulou", ref="ag_x_1", origem_pulo="desativar_tudo", grupo="ag_x", grupo_nome="Nome"))
    assert out["origem_pulo"] == "desativar_tudo" and out["grupo"] == "ag_x" and "grupo_nome" not in out
    assert limpar(_diag("perdido", ref="ag_x_1"))["tipo"] == "perdido"


async def test_evento_perdido(hass, setup, hass_ws_client, hass_access_token, hass_client_no_auth):
    """0.13.0: "perdido" (tocou até acabar o tempo) vai para o barramento e libera o alarme como "desligado"."""
    ws = await _ws(hass, hass_ws_client, hass_access_token)
    http = await hass_client_no_auth()
    _, body = await _activate(hass, ws, http)
    eventos_bus = async_capture_events(hass, "alarmeha_evento")
    url = f"/api/webhook/{body['webhook']}"
    base = {"origem": "alarmeha", "ref": "ag_x_1", "id": 5, "quando": "2026-01-01T07:00:00-03:00"}
    assert (await http.post(url, json={**base, "evento": "tocou"})).status == 200
    await hass.async_block_till_done()
    phone = next(iter(hass.data[DOMAIN].phones.values()))
    assert phone.ocupado("ag_x_1")
    assert (await http.post(url, json={**base, "evento": "perdido"})).status == 200
    await hass.async_block_till_done()
    assert [e.data["evento"] for e in eventos_bus] == ["tocou", "perdido"]
    assert not phone.ocupado("ag_x_1")
