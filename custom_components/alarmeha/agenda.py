"""Acordar pela agenda com tipos de evento.

Cada tipo (ex.: "Plantão") é reconhecido pela agenda e/ou por uma palavra no título e tem a sua
lista de alarmes relativos ao início do evento. No celular cada tipo tem alarmes fixos
("Plantão 1/4" ... "4/4", ref ag_<tipo>_<n>) que o HA só reajusta e liga/desliga."""

from __future__ import annotations

import asyncio

import logging
import re
import unicodedata
from datetime import date, datetime, time, timedelta
from typing import TYPE_CHECKING, Any

from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

if TYPE_CHECKING:
    from .phone import AlarmeHaPhone

_LOGGER = logging.getLogger(__name__)

DIAS_PREVIA = 14
PADRAO_ID = "padrao"

PADRAO: dict[str, Any] = {
    "ligado": False,
    "calendarios": [],  # agendas deste celular (Ajustes), além das ligadas a tipos
    "janela_inicio": "00:00",
    "janela_fim": "23:59",
    "sem_compromisso": None,  # None = não toca; "HH:MM" = horário fixo
    "escala_inicio": None,  # "AAAA-MM-DD" do início de uma semana de trabalho (semanas alternadas)
    "tipos": [],
    "som_padrao": None,  # {"uri", "titulo"} para tipos sem toque próprio; None = padrão do celular
}

TIPO_PADRAO = {"id": PADRAO_ID, "nome": "Padrão", "agendas": [], "palavras": [], "alarmes": [{"antes": 0}], "minimo": None,
               "eventos": "primeiro"}
EVENTOS = ("primeiro", "todos")  # primeiro = só o 1º compromisso do dia (acordar); todos = cada evento (remédios)
MAX_TODOS = 12  # alarmes fixos por tipo "todos" no celular (hoje + amanhã)


def sem_acento(texto: str) -> str:
    return unicodedata.normalize("NFD", texto or "").encode("ascii", "ignore").decode().lower()


def slug(texto: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", sem_acento(texto)).strip("_")[:20] or "tipo"


def config(dados: dict[str, Any] | None) -> dict[str, Any]:
    cfg = {**PADRAO, **(dados or {})}
    tipos = [t for t in cfg.get("tipos") or [] if t.get("id") != PADRAO_ID]
    padrao = next((t for t in cfg.get("tipos") or [] if t.get("id") == PADRAO_ID), None)
    cfg["tipos"] = [{**TIPO_PADRAO, **(padrao or {}), "id": PADRAO_ID}] + [{**TIPO_PADRAO, **t} for t in tipos]
    return cfg


def agendas_usadas(cfg: dict[str, Any]) -> list[str]:
    todas = list(cfg.get("calendarios") or [])
    for t in cfg["tipos"]:
        todas += t.get("agendas") or []
    return sorted(set(a for a in todas if a))


def _hm(texto: str) -> time:
    h, m = texto.split(":")
    return time(int(h), int(m))


def _fmt_min(minutos: int) -> str:
    if minutos == 0:
        return "na hora"
    h, m = divmod(minutos, 60)
    return (f"{h}h{m:02d}" if h else f"{m} min") + " antes"


def tipo_do_evento(ev: dict[str, Any], cfg: dict[str, Any]) -> dict[str, Any]:
    titulo = sem_acento(ev.get("titulo", ""))
    outros = cfg["tipos"][1:]
    for t in outros:  # palavra no título vence
        if any(p and sem_acento(p) in titulo for p in t.get("palavras") or []):
            return t
    for t in outros:
        if ev.get("agenda") in (t.get("agendas") or []):
            return t
    return cfg["tipos"][0]


def semana_de_trabalho(dia: date, inicio: str | None) -> bool:
    if not inicio:
        return True
    ref = date.fromisoformat(inicio)
    ref -= timedelta(days=ref.weekday())
    seg = dia - timedelta(days=dia.weekday())
    return ((seg - ref).days // 7) % 2 == 0


def _alarmes_do_evento(ev: dict[str, Any], t: dict[str, Any], dia: date) -> list[dict[str, Any]]:
    minimo = datetime.combine(dia, _hm(t["minimo"]), tzinfo=ev["inicio"].tzinfo) if t.get("minimo") else None
    saida = []
    for i, al in enumerate(t["alarmes"]):
        h = ev["inicio"] - timedelta(minutes=int(al.get("antes", 0)))
        if minimo and h < minimo:
            h = minimo
        saida.append({"n": i + 1, "tipo": t["id"], "horario": h, "agenda": ev.get("agenda_nome") or ev.get("agenda") or ""})
    return saida


def _motivo(t: dict[str, Any], ev: dict[str, Any], alarmes: list[dict[str, Any]]) -> str:
    partes = ", ".join(f'{a["horario"]:%H:%M}' for a in alarmes)
    origem = ev.get("agenda_nome") or ev.get("agenda")
    return f'{t["nome"]}: {ev["titulo"] or "compromisso"} às {ev["inicio"]:%H:%M} → {partes}' + (f" ({origem})" if origem else "")


def calcular(eventos: list[dict[str, Any]], cfg: dict[str, Any], hoje: date, dias: int = DIAS_PREVIA, desde: int = 1) -> list[dict[str, Any]]:
    """Função pura. eventos: [{"inicio": datetime local, "titulo", "agenda", "agenda_nome", "dia_inteiro"}].
    Devolve por dia: tipo, alarmes [{"n", "tipo", "horario": datetime, "agenda"}], motivo.

    Tipos "primeiro": só o 1º compromisso do dia entre eles decide. Tipos "todos": cada evento
    do tipo gera os seus alarmes, sem esconder nem ser escondido pelos outros tipos."""
    ini, fim = _hm(cfg["janela_inicio"]), _hm(cfg["janela_fim"])
    saida = []
    for n in range(desde, dias + 1):
        dia = hoje + timedelta(days=n)
        item: dict[str, Any] = {"dia": dia, "tipo": None, "alarmes": [], "motivo": ""}
        if not semana_de_trabalho(dia, cfg.get("escala_inicio")):
            item["motivo"] = "Escala: semana de folga"
            saida.append(item)
            continue
        do_dia = sorted(
            (e for e in eventos if not e.get("dia_inteiro") and e["inicio"].date() == dia and ini <= e["inicio"].time() <= fim),
            key=lambda e: e["inicio"],
        )
        classificados = [(e, tipo_do_evento(e, cfg)) for e in do_dia]
        primeiro = next(((e, t) for e, t in classificados if t.get("eventos") != "todos"), None)
        motivos: list[tuple[datetime, str]] = []
        if primeiro:
            ev, t = primeiro
            als = _alarmes_do_evento(ev, t, dia)
            item["alarmes"] += als
            item["tipo"] = t["id"]
            motivos.append((ev["inicio"], _motivo(t, ev, als)))
        for ev, t in classificados:
            if t.get("eventos") == "todos":
                als = _alarmes_do_evento(ev, t, dia)
                item["alarmes"] += als
                item["tipo"] = item["tipo"] or t["id"]
                motivos.append((ev["inicio"], _motivo(t, ev, als)))
        if not primeiro and cfg.get("sem_compromisso"):
            h = datetime.combine(dia, _hm(cfg["sem_compromisso"]), tzinfo=dt_util.DEFAULT_TIME_ZONE)
            item["alarmes"].append({"n": 1, "tipo": PADRAO_ID, "horario": h, "agenda": ""})
            item["tipo"] = item["tipo"] or PADRAO_ID
            motivos.insert(0, (h, "Sem compromisso: horário fixo (Padrão 1)"))
        item["alarmes"].sort(key=lambda a: a["horario"])
        if motivos:
            item["motivo"] = " · ".join(m for _, m in sorted(motivos, key=lambda x: x[0]))
        else:
            item["motivo"] = "Sem compromisso: não toca"
        saida.append(item)
    return saida


def _parse(valor: str) -> tuple[datetime, bool]:
    if "T" not in valor:
        return datetime.combine(date.fromisoformat(valor), time(0), tzinfo=dt_util.DEFAULT_TIME_ZONE), True
    return dt_util.as_local(dt_util.parse_datetime(valor)), False


def agendas_para_ler(hass: HomeAssistant | None, cfg: dict[str, Any]) -> list[str]:
    """Só as agendas marcadas para este celular em Ajustes, mais as que algum tipo usa.
    Uma agenda nova no HA (ex.: a de outra pessoa) nunca entra sozinha."""
    usadas = agendas_usadas(cfg)
    if hass is None:
        return usadas
    return [e for e in usadas if hass.states.get(e)]


async def async_eventos(hass: HomeAssistant, cfg: dict[str, Any]) -> list[dict[str, Any]]:
    entidades = agendas_para_ler(hass, cfg)
    if not entidades:
        return []
    inicio = dt_util.start_of_local_day()
    resp = await hass.services.async_call(
        "calendar", "get_events",
        {"entity_id": entidades, "start_date_time": inicio.isoformat(),
         "end_date_time": (inicio + timedelta(days=DIAS_PREVIA + 1)).isoformat()},
        blocking=True, return_response=True,
    )
    eventos = []
    for entidade, dados in (resp or {}).items():
        estado = hass.states.get(entidade)
        nome = estado.attributes.get("friendly_name", entidade) if estado else entidade
        for ev in dados.get("events", []):
            quando, dia_inteiro = _parse(ev["start"])
            eventos.append({"inicio": quando, "titulo": ev.get("summary", ""), "agenda": entidade, "agenda_nome": nome, "dia_inteiro": dia_inteiro})
    return eventos


def previa_json(dias: list[dict[str, Any]], cfg: dict[str, Any]) -> list[dict[str, Any]]:
    nomes = {t["id"]: t["nome"] for t in cfg["tipos"]}
    return [
        {"dia": d["dia"].isoformat(), "tipo": nomes.get(d["tipo"]), "horarios": [f'{a["horario"]:%H:%M}' for a in d["alarmes"]], "motivo": d["motivo"],
         "agendas": sorted({a["agenda"] for a in d["alarmes"] if a.get("agenda")})}
        for d in dias
    ]


async def async_previa(hass: HomeAssistant, cfg: dict[str, Any]) -> list[dict[str, Any]]:
    return previa_json(calcular(await async_eventos(hass, cfg), cfg, dt_util.now().date()), cfg)


def planejar(dias: list[dict[str, Any]], cfg: dict[str, Any], agora: datetime) -> dict[str, dict[str, Any]]:
    """Para cada alarme fixo (ref) decide o próximo disparo dentro de hoje/amanhã, ou desligado.
    O app só aceita alarme avulso para hoje (-1) ou amanhã (-2)."""
    plano: dict[str, dict[str, Any]] = {}
    hoje = agora.date()
    amanha = hoje + timedelta(days=1)
    for t in cfg["tipos"]:
        futuros = sorted(
            (a for d in dias if d["dia"] <= amanha for a in d["alarmes"]
             if a.get("tipo", d["tipo"]) == t["id"] and a["horario"] > agora),
            key=lambda a: a["horario"],
        )
        if t.get("eventos") == "todos":
            # Os próximos disparos de hoje/amanhã ocupam os alarmes fixos 1..N, em ordem.
            if len(futuros) > MAX_TODOS:
                _LOGGER.warning("AlarmeHA agenda: tipo %s tem %d alarmes até amanhã; só os %d primeiros vão para o celular",
                                t["nome"], len(futuros), MAX_TODOS)
            usados = futuros[:MAX_TODOS]
            total = max(len(usados), 1)
            slots = [(i + 1, usados[i]["horario"] if i < len(usados) else None) for i in range(MAX_TODOS)]
        else:
            total = len(t["alarmes"])
            slots = [(i + 1, next((a["horario"] for a in futuros if a["n"] == i + 1), None)) for i in range(total)]
        for n, prox in slots:
            ref = f'ag_{slug(t["id"])}_{n}'[:64]
            rotulo = "📅 " + (f'{t["nome"]} {n}/{total}' if total > 1 else t["nome"])
            if prox and cfg.get("ligado"):
                plano[ref] = {"hora": prox.hour, "minuto": prox.minute, "dias": -1 if prox.date() == hoje else -2,
                              "rotulo": rotulo, "ativo": True}
                som = t.get("som") or cfg.get("som_padrao") or {}
                if som.get("uri"):
                    plano[ref] |= {"som_uri": str(som["uri"])[:512], "som_titulo": str(som.get("titulo") or "")[:64]}
            else:
                plano[ref] = {"ativo": False, "rotulo": rotulo}
    return plano


_TRAVAS: dict[str, asyncio.Lock] = {}


async def async_atualizar_agendas(hass: HomeAssistant, entidades: list[str]) -> None:
    """Força a agenda (ex.: Remote Calendar, que sozinha só baixa 1x por dia) a baixar de novo."""
    entidades = [e for e in entidades if hass.states.get(e)]
    if not entidades:
        return
    try:
        await hass.services.async_call("homeassistant", "update_entity", {"entity_id": entidades}, blocking=True)
    except Exception as err:  # noqa: BLE001 - segue com o que já tem
        _LOGGER.debug("AlarmeHA agenda: não consegui atualizar %s: %s", entidades, err)


async def async_sincronizar(hass: HomeAssistant, phone: AlarmeHaPhone) -> None:
    """Ajusta os alarmes fixos do celular. Só envia o que mudou desde o último envio.

    Uma sincronização por celular de cada vez: salvar duas vezes seguidas (ou salvar
    durante o ciclo de 15 min) não pode mandar os mesmos comandos em dobro.
    """
    trava = _TRAVAS.setdefault(phone.phone_id, asyncio.Lock())
    async with trava:
        await _sincronizar(hass, phone)


async def _sincronizar(hass: HomeAssistant, phone: AlarmeHaPhone) -> None:
    cfg = config(phone.agenda)
    enviados: dict[str, Any] = dict(phone.agenda.get("_enviados") or {})
    if not cfg.get("ligado") and not any(v.get("ativo") for v in enviados.values()):
        return
    agora = dt_util.now()
    dias = calcular(await async_eventos(hass, cfg), cfg, agora.date(), dias=1, desde=0)
    plano = planejar(dias, cfg, agora)
    for ref in list(enviados):  # tipos/alarmes removidos: desliga o alarme antigo
        if ref not in plano and enviados[ref].get("ativo"):
            plano[ref] = {"ativo": False}
    # Quem manda é a agenda: se o alarme foi mexido no celular, reenvia o planejado.
    no_celular = {a.get("ref"): a for a in (phone.alarms or []) if isinstance(a, dict) and a.get("ref")}
    if no_celular:
        for ref, ant in list(enviados.items()):
            if not ant.get("ativo"):
                continue
            atual = no_celular.get(ref)
            if atual is None or (atual.get("hora"), atual.get("minuto"), bool(atual.get("ativo"))) != (ant.get("hora"), ant.get("minuto"), True):
                enviados.pop(ref)
    mudou = False
    for ref, alvo in plano.items():
        if enviados.get(ref) == alvo:
            continue
        if not alvo["ativo"] and ref not in enviados:
            continue  # nunca criado e nada a ligar
        try:
            if alvo["ativo"]:
                await phone.async_command("criar", {"ref": ref, "vibrar": True, **alvo}, 30)
            else:
                await phone.async_command("desligar", {"ref": ref}, 30)
            enviados[ref] = alvo
            mudou = True
        except Exception as err:  # noqa: BLE001 - tenta de novo no próximo ciclo
            _LOGGER.warning("AlarmeHA agenda: não consegui ajustar %s: %s", ref, err)
    if mudou:
        phone.agenda = {**phone.agenda, "_enviados": enviados}
        phone.hub.async_save()
