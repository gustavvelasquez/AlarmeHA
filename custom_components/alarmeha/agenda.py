"""Acordar pela agenda: calcula o horário de acordar dos próximos dias a partir de um calendar.*.

Fase 1: só prévia (nada é enviado ao celular)."""

from __future__ import annotations

from datetime import date, datetime, time, timedelta
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

DIAS_PREVIA = 14

PADRAO: dict[str, Any] = {
    "ligado": False,
    "calendario": None,
    "antecedencia": 75,  # minutos
    "janela_inicio": "04:00",
    "janela_fim": "12:00",
    "minimo": "05:00",
    "sem_compromisso": None,  # None = não tocar; "HH:MM" = horário fixo
    "escala_inicio": None,  # "AAAA-MM-DD" do início de uma semana de trabalho (semanas alternadas)
}


def config(dados: dict[str, Any] | None) -> dict[str, Any]:
    return {**PADRAO, **(dados or {})}


def _hm(texto: str) -> time:
    h, m = texto.split(":")
    return time(int(h), int(m))


def _fmt_min(minutos: int) -> str:
    h, m = divmod(minutos, 60)
    return f"{h}h{m:02d}" if h else f"{m} min"


def semana_de_trabalho(dia: date, inicio: str | None) -> bool:
    if not inicio:
        return True
    ref = date.fromisoformat(inicio)
    ref -= timedelta(days=ref.weekday())
    seg = dia - timedelta(days=dia.weekday())
    return ((seg - ref).days // 7) % 2 == 0


def calcular(eventos: list[dict[str, Any]], cfg: dict[str, Any], hoje: date, dias: int = DIAS_PREVIA) -> list[dict[str, Any]]:
    """Função pura. eventos: [{"inicio": datetime local, "titulo": str, "dia_inteiro": bool}]."""
    ini, fim, minimo = _hm(cfg["janela_inicio"]), _hm(cfg["janela_fim"]), _hm(cfg["minimo"])
    antecedencia = timedelta(minutes=int(cfg["antecedencia"]))
    saida = []
    for n in range(1, dias + 1):
        dia = hoje + timedelta(days=n)
        item: dict[str, Any] = {"dia": dia.isoformat(), "horario": None, "motivo": ""}
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
            acordar = ev["inicio"] - antecedencia
            limite = datetime.combine(dia, minimo, tzinfo=ev["inicio"].tzinfo)
            calc = f'Agenda: {ev["titulo"] or "compromisso"} {ev["inicio"]:%H:%M} − {_fmt_min(int(cfg["antecedencia"]))} = {acordar:%H:%M}'
            if acordar < limite:
                acordar = limite
                calc += f' (ajustado para o mínimo {minimo:%H:%M})'
            item.update(horario=f"{acordar:%H:%M}", motivo=calc)
        elif cfg.get("sem_compromisso"):
            item.update(horario=cfg["sem_compromisso"], motivo="Sem compromisso: horário fixo")
        else:
            item["motivo"] = "Sem compromisso: não toca"
        saida.append(item)
    return saida


def _parse(valor: str) -> tuple[datetime, bool]:
    if "T" not in valor:
        return datetime.combine(date.fromisoformat(valor), time(0), tzinfo=dt_util.DEFAULT_TIME_ZONE), True
    d = dt_util.parse_datetime(valor)
    return dt_util.as_local(d), False


async def async_previa(hass: HomeAssistant, cfg: dict[str, Any]) -> list[dict[str, Any]]:
    hoje = dt_util.now().date()
    eventos: list[dict[str, Any]] = []
    entidade = cfg.get("calendario")
    if entidade:
        inicio = dt_util.start_of_local_day() + timedelta(days=1)
        resp = await hass.services.async_call(
            "calendar", "get_events",
            {"entity_id": entidade, "start_date_time": inicio.isoformat(), "end_date_time": (inicio + timedelta(days=DIAS_PREVIA)).isoformat()},
            blocking=True, return_response=True,
        )
        for ev in (resp or {}).get(entidade, {}).get("events", []):
            quando, dia_inteiro = _parse(ev["start"])
            eventos.append({"inicio": quando, "titulo": ev.get("summary", ""), "dia_inteiro": dia_inteiro})
    return calcular(eventos, cfg, hoje)
