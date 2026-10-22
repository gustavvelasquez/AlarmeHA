"""0.11.0: janela de 7 dias, conjuntos (título, horário) com dias da semana e refs estáveis."""

from __future__ import annotations

import random
from datetime import date, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import patch
from zoneinfo import ZoneInfo

from custom_components.alarmeha import agenda

TZ = ZoneInfo("America/Sao_Paulo")
QUI = date(2026, 10, 22)  # quinta-feira
SEG, TER, QUA, QUI_B, SEX, SAB, DOM = 1, 2, 4, 8, 16, 32, 64
TODOS = 127

CFG = agenda.config({
    "ligado": True,
    "tipos": [
        {"id": "trabalho", "nome": "Trabalho", "agendas": ["calendar.trabalho"], "agrupar": "por_evento",
         "alarmes": [{"antes": 120}, {"antes": 90}, {"antes": 60}, {"antes": 30}]},
        {"id": "remedio", "nome": "Remédio", "agendas": ["calendar.remedio"], "agrupar": "por_tipo",
         "alarmes": [{"antes": 0}]},
    ],
})


def ev(dia: date, hh: int, mm: int, titulo: str, agenda_id: str) -> dict:
    return {"inicio": datetime(dia.year, dia.month, dia.day, hh, mm, tzinfo=TZ), "titulo": titulo,
            "agenda": agenda_id, "agenda_nome": agenda_id, "dia_inteiro": False}


def eventos(trabalho_dias=(1, 2, 3, 6), extra=()) -> list[dict]:
    """Trabalho 06:00 em sex/sáb/dom e na quarta; Teste Alarme 17:30 e 3 remédios todos os dias."""
    out = []
    for n in range(0, 14):
        d = QUI + timedelta(days=n)
        if n % 7 in trabalho_dias:
            out.append(ev(d, 6, 0, "Trabalho", "calendar.trabalho"))
        out.append(ev(d, 17, 30, "Teste Alarme", "calendar.trabalho"))
        for hh, nome in ((9, "Dose manhã"), (18, "Dose tarde"), (21, "Dose noite")):
            out.append(ev(d, hh, 0, nome, "calendar.remedio"))
    return out + list(extra)


def plano(evs, agora: datetime) -> dict:
    dias = agenda.calcular(evs, CFG, agora.date(), dias=agenda.JANELA_DIAS, desde=0)
    return agenda.planejar(dias, CFG, agora)


def cartoes(p: dict) -> dict[str, list[dict]]:
    out: dict[str, list[dict]] = {}
    for v in p.values():
        out.setdefault(v.get("grupo_nome"), []).append(v)
    return out


def at(d: date, hh: int, mm: int) -> datetime:
    return datetime(d.year, d.month, d.day, hh, mm, tzinfo=TZ)


def test_madrugada_de_quinta():
    c = cartoes(plano(eventos(), at(QUI, 2, 33)))
    assert sorted(c) == ["Remédio", "Teste Alarme", "Trabalho"]
    assert len(c["Trabalho"]) == 4 and {a["dias"] for a in c["Trabalho"]} == {SEX | SAB | DOM | QUA}
    assert [(a["hora"], a["minuto"]) for a in c["Trabalho"]] == [(4, 0), (4, 30), (5, 0), (5, 30)]
    assert len(c["Teste Alarme"]) == 4 and {a["dias"] for a in c["Teste Alarme"]} == {TODOS}
    assert len(c["Remédio"]) == 3 and {a["dias"] for a in c["Remédio"]} == {TODOS}
    assert {a["rotulo"] for a in c["Remédio"]} == {"📅 Dose manhã", "📅 Dose tarde", "📅 Dose noite"}


def test_virada_da_meia_noite_e_domingo_sem_trabalho():
    sex = plano(eventos(), at(QUI + timedelta(days=1), 0, 5))
    c = cartoes(sex)
    # Sexta: a janela vai até quinta seguinte (sem Trabalho); quinta passada saiu, a próxima entrou.
    assert {a["dias"] for a in c["Trabalho"]} == {SEX | SAB | DOM | QUA}
    dom = cartoes(plano(eventos(), at(QUI + timedelta(days=3), 7, 0)))  # domingo, Trabalho de hoje já tocou
    # Domingo de hoje já passou: sobra quarta e, com a janela andando, a sexta e o sábado seguintes.
    assert {a["dias"] for a in dom["Trabalho"]} == {QUA | SEX | SAB}
    seg = cartoes(plano(eventos(), at(QUI + timedelta(days=4), 0, 5)))  # segunda: sem Trabalho hoje
    assert len(seg["Trabalho"]) == 4 and {a["dias"] for a in seg["Trabalho"]} == {QUA | SEX | SAB | DOM}


def test_tocou_hoje_desmarca_so_hoje():
    p = plano(eventos(), at(QUI, 9, 1))
    remedio = {a["rotulo"]: a["dias"] for a in cartoes(p)["Remédio"]}
    assert remedio["📅 Dose manhã"] == TODOS & ~QUI_B and remedio["📅 Dose noite"] == TODOS
    assert all(a["ativo"] for a in p.values())


def test_mesmo_titulo_dois_horarios_no_mesmo_cartao():
    extra = [ev(QUI + timedelta(days=2), 8, 0, "Trabalho", "calendar.trabalho")]  # sábado também às 08:00
    c = cartoes(plano(eventos(extra=extra), at(QUI, 2, 33)))
    assert len(c["Trabalho"]) == 8 and len({a["grupo"] for a in c["Trabalho"]}) == 1
    assert sorted({a["dias"] for a in c["Trabalho"]}) == [SAB, SEX | SAB | DOM | QUA]


def test_titulo_sumindo_da_janela():
    so_sexta = [e for e in eventos() if e["titulo"] != "Trabalho"] + [ev(QUI + timedelta(days=1), 6, 0, "Trabalho", "calendar.trabalho")]
    assert "Trabalho" in cartoes(plano(so_sexta, at(QUI, 2, 33)))
    assert "Trabalho" not in cartoes(plano(so_sexta, at(QUI + timedelta(days=1), 7, 0)))


def test_refs_estaveis():
    a = plano(eventos(), at(QUI, 2, 33))
    evs = eventos()
    random.Random(1).shuffle(evs)
    b = plano(evs, at(QUI, 2, 35))
    assert a == b
    assert all(agenda.tipo_da_vaga(r, CFG) in ("trabalho", "remedio") for r in a)
    # Outro dia, mesmo conjunto: mesmas refs.
    c = plano(eventos(), at(QUI + timedelta(days=1), 0, 5))
    assert set(c) == set(a)


class FakePhone:
    def __init__(self, alarms, enviados):
        self.phone_id = "p1"
        self.person = "Pessoa"
        self.alarms = alarms
        self.agenda = {**CFG, "_enviados": enviados}
        self.pulados = {}
        self.comandos: list[tuple[str, str]] = []
        self.hub = SimpleNamespace(async_save=lambda: None)

    def ocupado(self, ref):
        return False

    async def async_command(self, op, fields, timeout):
        self.comandos.append((op, fields["ref"]))
        return {"evento": "ok"}


async def test_migracao_refs_antigas_sem_duplicar(hass):
    antigas = {f"ag_trabalho_{n}": {"hora": 4, "minuto": 0, "dias": -2, "ativo": True} for n in range(1, 5)}
    phone = FakePhone([{"ref": r, "hora": 4, "minuto": 0, "dias": -2, "ativo": True} for r in antigas], dict(antigas))
    agora = at(QUI, 2, 33)

    async def fake_eventos(hass, cfg, falhas=None):
        return eventos()

    with patch.object(agenda, "async_eventos", fake_eventos), patch.object(agenda.dt_util, "now", return_value=agora):
        await agenda._sincronizar(hass, phone)
    ops = phone.comandos
    apagados = [r for op, r in ops if op == "apagar"]
    criados = [r for op, r in ops if op == "criar"]
    assert sorted(apagados) == sorted(antigas)
    assert ops[:4] == [("apagar", r) for r in apagados]  # apaga antes de criar
    assert len(criados) == 11 and len(set(criados)) == 11  # 4 Trabalho + 4 Teste Alarme + 3 Remédio
    enviados = phone.agenda["_enviados"]
    assert set(enviados) == set(criados)

    # Segunda sincronização igual: nada a enviar.
    phone.comandos.clear()
    phone.alarms = [{"ref": r, **{k: v[k] for k in ("hora", "minuto", "dias", "ativo")}} for r, v in enviados.items()]
    with patch.object(agenda, "async_eventos", fake_eventos), patch.object(agenda.dt_util, "now", return_value=agora + timedelta(minutes=15)):
        await agenda._sincronizar(hass, phone)
    assert phone.comandos == []
