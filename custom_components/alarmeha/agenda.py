"""Acordar pela agenda com tipos de evento.

Cada tipo (ex.: "Plantão") é ligado a uma ou mais agendas: todo evento com horário dessas agendas
vira os alarmes do tipo (relativos ao início do evento). Palavras no título, opcionais, separam
tipos que dividem a mesma agenda. Agenda que nenhum tipo usa é ignorada. No celular cada tipo tem alarmes fixos
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
PADRAO_ID = "padrao"  # tipo antigo (até 0.5.8); os alarmes ag_padrao_* são desligados
LIVRE_ID = "sem_compromisso"  # alarme fixo do dia sem compromisso
RESERVADOS = (PADRAO_ID, LIVRE_ID)

PADRAO: dict[str, Any] = {
    "ligado": False,
    "janela_inicio": "00:00",
    "janela_fim": "23:59",
    "sem_compromisso": None,  # None = não toca; "HH:MM" = horário fixo
    "escala_inicio": None,  # "AAAA-MM-DD" do início de uma semana de trabalho (semanas alternadas)
    "tipos": [],
    "som_padrao": None,  # {"uri", "titulo"} para tipos sem toque próprio (e o dia sem compromisso); None = padrão do celular
}

TIPO_BASE = {"agendas": [], "palavras": [], "alarmes": [{"antes": 0}], "minimo": None, "som": None}
MAX_TODOS = 12  # alarmes fixos por tipo no celular (hoje + amanhã)
_OBSOLETOS = ("calendarios", "padrao", "eventos")


def sem_acento(texto: str) -> str:
    return unicodedata.normalize("NFD", texto or "").encode("ascii", "ignore").decode().lower()


def slug(texto: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", sem_acento(texto)).strip("_")[:20] or "tipo"


def migrar(dados: dict[str, Any] | None) -> dict[str, Any]:
    """Limpa a configuração salva até a 0.5.8: sem tipo Padrão, sem "agendas deste celular",
    sem a opção "eventos". Mantém _enviados (é por ele que os alarmes antigos são desligados)."""
    dados = {k: v for k, v in (dados or {}).items() if k not in _OBSOLETOS}
    if "tipos" in dados:
        dados["tipos"] = [{k: v for k, v in t.items() if k not in _OBSOLETOS}
                          for t in dados.get("tipos") or [] if t.get("id") not in RESERVADOS]
    return dados


def config(dados: dict[str, Any] | None) -> dict[str, Any]:
    cfg = {**PADRAO, **migrar(dados)}
    cfg["tipos"] = [{**TIPO_BASE, **t} for t in cfg.get("tipos") or []]
    return cfg


def agendas_usadas(cfg: dict[str, Any]) -> list[str]:
    return sorted({a for t in cfg["tipos"] for a in t.get("agendas") or [] if a})


def _hm(texto: str) -> time:
    h, m = texto.split(":")
    return time(int(h), int(m))


def _fmt_min(minutos: int) -> str:
    if minutos == 0:
        return "na hora"
    h, m = divmod(minutos, 60)
    return (f"{h}h{m:02d}" if h else f"{m} min") + " antes"


def tipo_do_evento(ev: dict[str, Any], cfg: dict[str, Any]) -> dict[str, Any] | None:
    """O tipo vale se a agenda do evento está nele E (não tem palavras OU o título tem uma).
    Com palavra que bate vence sem palavras; empate: o primeiro da lista. None = ignorado."""
    titulo = sem_acento(ev.get("titulo", ""))
    sem_palavra = None
    for t in cfg["tipos"]:
        if ev.get("agenda") not in (t.get("agendas") or []):
            continue
        palavras = [p for p in t.get("palavras") or [] if p]
        if not palavras:
            sem_palavra = sem_palavra or t
        elif any(sem_acento(p) in titulo for p in palavras):
            return t
    return sem_palavra


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
        saida.append({"n": i + 1, "total": len(t["alarmes"]), "titulo": (ev.get("titulo") or "").strip(),
                      "tipo": t["id"], "horario": h, "agenda": ev.get("agenda_nome") or ev.get("agenda") or ""})
    return saida


def _motivo(t: dict[str, Any], ev: dict[str, Any], alarmes: list[dict[str, Any]]) -> str:
    partes = ", ".join(f'{a["horario"]:%H:%M}' for a in alarmes)
    origem = ev.get("agenda_nome") or ev.get("agenda")
    return f'{t["nome"]}: {ev["titulo"] or "compromisso"} às {ev["inicio"]:%H:%M} → {partes}' + (f" ({origem})" if origem else "")


def rotulo_alarme(a: dict[str, Any], nome_tipo: str) -> str:
    """ "📅 <título do evento>" (ou o nome do tipo), com " n/N" se o tipo tem mais de um alarme por evento.
    Cabe em 64 (UTF-16, como no app): corta o título, nunca o sufixo."""
    total = int(a.get("total") or 1)
    sufixo = f' {a.get("n", 1)}/{total}' if total > 1 else ""
    titulo = " ".join((a.get("titulo") or nome_tipo).split())
    caber = 64 - 3 - len(sufixo)  # "📅 " ocupa 3 unidades UTF-16 no app
    if len(titulo) > caber:
        titulo = titulo[: caber - 1].rstrip() + "…"
    return f"📅 {titulo}{sufixo}"


def calcular(eventos: list[dict[str, Any]], cfg: dict[str, Any], hoje: date, dias: int = DIAS_PREVIA, desde: int = 1) -> list[dict[str, Any]]:
    """Função pura. eventos: [{"inicio": datetime local, "titulo", "agenda", "agenda_nome", "dia_inteiro"}].
    Devolve por dia: tipo, alarmes [{"n", "tipo", "horario": datetime, "agenda"}], motivo.
    Todo evento com horário de uma agenda ligada a um tipo vira os alarmes desse tipo."""
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
        motivos = []
        for ev in do_dia:
            t = tipo_do_evento(ev, cfg)
            if t is None:
                continue
            als = _alarmes_do_evento(ev, t, dia)
            item["alarmes"] += als
            item["tipo"] = item["tipo"] or t["id"]
            motivos.append(_motivo(t, ev, als))
        if not motivos and cfg.get("sem_compromisso"):
            h = datetime.combine(dia, _hm(cfg["sem_compromisso"]), tzinfo=dt_util.DEFAULT_TIME_ZONE)
            item.update(tipo=LIVRE_ID, alarmes=[{"n": 1, "tipo": LIVRE_ID, "horario": h, "agenda": ""}])
            motivos.append(f"Sem compromisso: horário fixo {h:%H:%M}")
        item["alarmes"].sort(key=lambda a: a["horario"])
        item["motivo"] = " · ".join(motivos) or "Sem compromisso: não toca"
        saida.append(item)
    return saida


def _parse(valor: str) -> tuple[datetime, bool]:
    if "T" not in valor:
        return datetime.combine(date.fromisoformat(valor), time(0), tzinfo=dt_util.DEFAULT_TIME_ZONE), True
    return dt_util.as_local(dt_util.parse_datetime(valor)), False


def agendas_para_ler(hass: HomeAssistant | None, cfg: dict[str, Any]) -> list[str]:
    """Só as agendas ligadas a algum tipo. Uma agenda nova no HA (ex.: a de outra pessoa) nunca entra sozinha."""
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
    nomes = {t["id"]: t["nome"] for t in cfg["tipos"]} | {LIVRE_ID: "Sem compromisso"}
    return [
        {"dia": d["dia"].isoformat(), "tipo": nomes.get(d["tipo"]), "horarios": [f'{a["horario"]:%H:%M}' for a in d["alarmes"]], "motivo": d["motivo"],
         "agendas": sorted({a["agenda"] for a in d["alarmes"] if a.get("agenda")})}
        for d in dias
    ]


async def async_previa(hass: HomeAssistant, cfg: dict[str, Any]) -> list[dict[str, Any]]:
    return previa_json(calcular(await async_eventos(hass, cfg), cfg, dt_util.now().date()), cfg)


def _dias(grupo: list[dict[str, Any]], hoje: date) -> int:
    """-1 hoje, -2 amanhã; hoje e amanhã juntos viram bits da semana como no app (seg=1 … dom=64)."""
    datas = {a["horario"].date() for a in grupo}
    if len(datas) == 1:
        return -1 if hoje in datas else -2
    return sum(1 << d.weekday() for d in datas)


def planejar(dias: list[dict[str, Any]], cfg: dict[str, Any], agora: datetime) -> dict[str, dict[str, Any]]:
    """Para cada alarme fixo (ref) decide os disparos dentro de hoje/amanhã, ou desligado.
    Disparo único vai como hoje (-1) ou amanhã (-2). Se hoje e amanhã têm o mesmo horário e o
    mesmo nome, um só alarme cobre os dois, marcado nos dois dias da semana (bits do app)."""
    plano: dict[str, dict[str, Any]] = {}
    hoje = agora.date()
    amanha = hoje + timedelta(days=1)
    livre = {"id": LIVRE_ID, "nome": "Sem compromisso", "som": None}
    for t in cfg["tipos"] + [livre]:
        futuros = sorted(
            (a for d in dias if d["dia"] <= amanha for a in d["alarmes"] if a.get("tipo") == t["id"] and a["horario"] > agora),
            key=lambda a: a["horario"],
        )
        # Mesmo horário e mesmo nome hoje e amanhã: um alarme só, nos dois dias da semana.
        grupos: dict[tuple, list[dict[str, Any]]] = {}
        for a in futuros:
            chave = (a["horario"].hour, a["horario"].minute, rotulo_alarme(a, t["nome"]) if t is not livre else a["horario"].date())  # sem compromisso: igual antes
            grupos.setdefault(chave, []).append(a)
        # Os grupos ocupam os alarmes fixos 1..N, na ordem do primeiro disparo.
        if len(grupos) > MAX_TODOS:
            _LOGGER.warning("AlarmeHA agenda: tipo %s tem %d alarmes até amanhã; só os %d primeiros vão para o celular",
                            t["nome"], len(grupos), MAX_TODOS)
        usados = list(grupos.values())[:MAX_TODOS]
        for i in range(MAX_TODOS if t is not livre else 1):
            grupo = usados[i] if i < len(usados) else []
            prox = grupo[0]["horario"] if grupo else None
            ref = f'ag_{slug(t["id"])}_{i + 1}'[:64]
            if t is livre:
                rotulo = "📅 Sem compromisso"
            elif prox:
                rotulo = rotulo_alarme(grupo[0], t["nome"])
            else:
                rotulo = "📅 " + t["nome"]
            if prox and cfg.get("ligado"):
                plano[ref] = {"hora": prox.hour, "minuto": prox.minute, "dias": _dias(grupo, hoje),
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
        phone.agenda = {**migrar(phone.agenda), "_enviados": enviados}
        phone.hub.async_save()
