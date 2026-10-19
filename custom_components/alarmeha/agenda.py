"""Acordar pela agenda com tipos de evento.

Cada tipo (ex.: "Plantão") é reconhecido pela agenda e/ou por uma palavra no título e tem a sua
lista de alarmes relativos ao início do evento. No celular cada tipo tem alarmes fixos
("Plantão 1/4" ... "4/4", ref ag_<tipo>_<n>) que o HA só reajusta e liga/desliga."""

from __future__ import annotations

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
    "calendarios": [],  # agendas lidas além das ligadas a tipos
    "janela_inicio": "00:00",
    "janela_fim": "23:59",
    "sem_compromisso": None,  # None = não toca; "HH:MM" = horário fixo
    "escala_inicio": None,  # "AAAA-MM-DD" do início de uma semana de trabalho (semanas alternadas)
    "tipos": [],
}

TIPO_PADRAO = {"id": PADRAO_ID, "nome": "Padrão", "agendas": [], "palavras": [], "alarmes": [{"antes": 0}], "minimo": None}


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


def calcular(eventos: list[dict[str, Any]], cfg: dict[str, Any], hoje: date, dias: int = DIAS_PREVIA, desde: int = 1) -> list[dict[str, Any]]:
    """Função pura. eventos: [{"inicio": datetime local, "titulo", "agenda", "dia_inteiro"}].
    Devolve por dia: tipo, alarmes [{"n", "horario": datetime}], motivo."""
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
        if do_dia:
            ev = do_dia[0]
            t = tipo_do_evento(ev, cfg)
            minimo = datetime.combine(dia, _hm(t["minimo"]), tzinfo=ev["inicio"].tzinfo) if t.get("minimo") else None
            alarmes = []
            for i, al in enumerate(t["alarmes"]):
                h = ev["inicio"] - timedelta(minutes=int(al.get("antes", 0)))
                if minimo and h < minimo:
                    h = minimo
                alarmes.append({"n": i + 1, "horario": h})
            partes = ", ".join(f'{a["horario"]:%H:%M}' for a in alarmes)
            item.update(
                tipo=t["id"], alarmes=alarmes,
                motivo=f'{t["nome"]}: {ev["titulo"] or "compromisso"} às {ev["inicio"]:%H:%M} → {partes}',
            )
        elif cfg.get("sem_compromisso"):
            h = datetime.combine(dia, _hm(cfg["sem_compromisso"]), tzinfo=dt_util.DEFAULT_TIME_ZONE)
            item.update(tipo=PADRAO_ID, alarmes=[{"n": 1, "horario": h}], motivo="Sem compromisso: horário fixo (Padrão 1)")
        else:
            item["motivo"] = "Sem compromisso: não toca"
        saida.append(item)
    return saida


def _parse(valor: str) -> tuple[datetime, bool]:
    if "T" not in valor:
        return datetime.combine(date.fromisoformat(valor), time(0), tzinfo=dt_util.DEFAULT_TIME_ZONE), True
    return dt_util.as_local(dt_util.parse_datetime(valor)), False


async def async_eventos(hass: HomeAssistant, cfg: dict[str, Any]) -> list[dict[str, Any]]:
    entidades = [e for e in agendas_usadas(cfg) if hass.states.get(e)]
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
        for ev in dados.get("events", []):
            quando, dia_inteiro = _parse(ev["start"])
            eventos.append({"inicio": quando, "titulo": ev.get("summary", ""), "agenda": entidade, "dia_inteiro": dia_inteiro})
    return eventos


def previa_json(dias: list[dict[str, Any]], cfg: dict[str, Any]) -> list[dict[str, Any]]:
    nomes = {t["id"]: t["nome"] for t in cfg["tipos"]}
    return [
        {"dia": d["dia"].isoformat(), "tipo": nomes.get(d["tipo"]), "horarios": [f'{a["horario"]:%H:%M}' for a in d["alarmes"]], "motivo": d["motivo"]}
        for d in dias
    ]


async def async_previa(hass: HomeAssistant, cfg: dict[str, Any]) -> list[dict[str, Any]]:
    return previa_json(calcular(await async_eventos(hass, cfg), cfg, dt_util.now().date()), cfg)


def planejar(dias: list[dict[str, Any]], cfg: dict[str, Any], agora: datetime) -> dict[str, dict[str, Any]]:
    """Para cada alarme fixo (ref) decide o próximo disparo dentro de hoje/amanhã, ou desligado.
    O app só aceita alarme avulso para hoje (-1) ou amanhã (-2)."""
    plano: dict[str, dict[str, Any]] = {}
    hoje = agora.date()
    for t in cfg["tipos"]:
        total = len(t["alarmes"])
        for i in range(total):
            ref = f'ag_{slug(t["id"])}_{i + 1}'[:64]
            rotulo = f'{t["nome"]} {i + 1}/{total}' if total > 1 else t["nome"]
            prox = None
            for d in dias:
                if d["tipo"] != t["id"] or d["dia"] > hoje + timedelta(days=1):
                    continue
                for a in d["alarmes"]:
                    if a["n"] == i + 1 and a["horario"] > agora:
                        prox = a["horario"]
                        break
                if prox:
                    break
            if prox and cfg.get("ligado"):
                plano[ref] = {"hora": prox.hour, "minuto": prox.minute, "dias": -1 if prox.date() == hoje else -2,
                              "rotulo": rotulo, "ativo": True}
            else:
                plano[ref] = {"ativo": False, "rotulo": rotulo}
    return plano


async def async_sincronizar(hass: HomeAssistant, phone: AlarmeHaPhone) -> None:
    """Ajusta os alarmes fixos do celular. Só envia o que mudou desde o último envio."""
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
