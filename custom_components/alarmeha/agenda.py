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

from .const import GUARDA_ANTES_MIN, GUARDA_DEPOIS_MIN, GUARDA_RESYNC_FOLGA_S

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
                      "tipo": t["id"], "horario": h, "agenda": ev.get("agenda_nome") or ev.get("agenda") or "",
                      "grupo": grupo_do_evento(ev, t)})
    return saida


def grupo_do_evento(ev: dict[str, Any], t: dict[str, Any]) -> str:
    """Id estável do compromisso (tipo + início + título), no formato de ref do app ([a-z0-9_-], até 64)."""
    return f'{slug(t["id"])}_{ev["inicio"]:%Y%m%d_%H%M}_{slug(ev.get("titulo") or "")[:16]}'[:64]


def nome_do_grupo(a: dict[str, Any], nome_tipo: str) -> str:
    titulo = " ".join((a.get("titulo") or "").split())
    return (f"{nome_tipo} · {titulo}" if titulo and titulo != nome_tipo else nome_tipo)[:64]


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
    Disparo único vai como hoje (-1) ou amanhã (-2).
    - Tipo de um alarme só (ex.: remédio) e "Sem compromisso": se hoje e amanhã têm o mesmo
      horário e o mesmo nome, um só alarme cobre os dois, marcado nos dias da semana (bits do app).
    - Compromisso com vários alarmes: nunca mescla dias. Cada ocorrência é (grupo, n), com o grupo
      do seu próprio compromisso. Quem do grupo já tocou continua na vaga, desligado e com
      "tocou" (HH:MM), até o último alarme do grupo passar; não volta a tocar."""
    plano: dict[str, dict[str, Any]] = {}
    hoje = agora.date()
    amanha = hoje + timedelta(days=1)
    livre = {"id": LIVRE_ID, "nome": "Sem compromisso", "som": None}
    for t in cfg["tipos"] + [livre]:
        do_tipo = sorted(
            (a for d in dias if d["dia"] <= amanha for a in d["alarmes"] if a.get("tipo") == t["id"]),
            key=lambda a: a["horario"],
        )
        com_grupo = t is not livre and len(t.get("alarmes") or []) > 1
        if com_grupo:
            # Grupo vivo = algum alarme dele ainda por tocar; os que já tocaram ficam (esmaecidos).
            vivos = {a.get("grupo") for a in do_tipo if a["horario"] > agora}
            candidatos = [a for a in do_tipo if a["horario"] > agora or (a.get("grupo") and a.get("grupo") in vivos)]
        else:
            candidatos = [a for a in do_tipo if a["horario"] > agora]
        grupos: dict[tuple, list[dict[str, Any]]] = {}
        for a in candidatos:
            if com_grupo:
                chave = (a.get("grupo") or a["horario"].isoformat(), a.get("n"))
            else:
                # Mesmo horário e mesmo nome hoje e amanhã: um alarme só, nos dois dias da semana.
                chave = (a["horario"].hour, a["horario"].minute, rotulo_alarme(a, t["nome"]) if t is not livre else a["horario"].date())
            grupos.setdefault(chave, []).append(a)
        # Os grupos ocupam os alarmes fixos 1..N, na ordem do primeiro disparo.
        if len(grupos) > MAX_TODOS:
            _LOGGER.warning("AlarmeHA agenda: tipo %s tem %d alarmes até amanhã; só os %d primeiros vão para o celular",
                            t["nome"], len(grupos), MAX_TODOS)
        usados = list(grupos.values())[:MAX_TODOS]
        inicio_grupo: dict[str, date] = {}
        for a in candidatos:
            if a.get("grupo"):
                inicio_grupo.setdefault(a["grupo"], a["horario"].date())
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
                tocou = prox <= agora
                plano[ref] = {"hora": prox.hour, "minuto": prox.minute, "dias": _dias(grupo, hoje),
                              "rotulo": rotulo, "ativo": not tocou}
                if tocou:
                    plano[ref]["tocou"] = f"{prox:%H:%M}"
                # Compromisso com mais de um alarme: o app agrupa (desligar/religar o grupo).
                # Com um alarme só (ex.: remédio) não vai grupo: fica sozinho e nunca é desligado junto.
                if com_grupo and int(grupo[0].get("total") or 1) > 1 and grupo[0].get("grupo"):
                    nome = nome_do_grupo(grupo[0], t["nome"])
                    if inicio_grupo.get(grupo[0]["grupo"]) == amanha:
                        nome = nome[: 64 - len(" · amanhã")] + " · amanhã"
                    plano[ref] |= {"grupo": grupo[0]["grupo"], "grupo_nome": nome}
                som = t.get("som") or cfg.get("som_padrao") or {}
                if som.get("uri"):
                    plano[ref] |= {"som_uri": str(som["uri"])[:512], "som_titulo": str(som.get("titulo") or "")[:64]}
            else:
                plano[ref] = {"ativo": False, "rotulo": rotulo}
    return plano


_TRAVAS: dict[str, asyncio.Lock] = {}
_RESYNC: dict[str, Any] = {}  # phone_id -> cancelamento da ressincronização agendada


def _disparos(alarme: dict[str, Any] | None, agora: datetime) -> list[datetime]:
    """Disparos possíveis de um alarme ativo (enviado ou da lista do celular) de ontem a amanhã.
    dias -1/-2 (ou desconhecido): qualquer um dos três dias (não sabemos quando foi enviado);
    bitmask: só os dias da semana marcados (seg=1 … dom=64)."""
    if not isinstance(alarme, dict) or not alarme.get("ativo"):
        return []
    try:
        h, m = int(alarme["hora"]), int(alarme["minuto"])
    except (KeyError, TypeError, ValueError):
        return []
    try:
        dias = int(alarme.get("dias") if alarme.get("dias") is not None else -1)
    except (TypeError, ValueError):
        dias = -1
    saida = []
    for d in (-1, 0, 1):
        dia = agora.date() + timedelta(days=d)
        if dias > 0 and not dias & (1 << dia.weekday()):
            continue
        saida.append(datetime.combine(dia, time(h, m), tzinfo=agora.tzinfo))
    return saida


def proximo_disparo(alarme: dict[str, Any], agora: datetime) -> datetime | None:
    """Próximo disparo (>= agora - GUARDA_DEPOIS_MIN) de um alarme planejado/enviado, ou None."""
    lim = agora - timedelta(minutes=GUARDA_DEPOIS_MIN)
    try:
        h, m = int(alarme["hora"]), int(alarme["minuto"])
        dias = int(alarme.get("dias") if alarme.get("dias") is not None else -1)
    except (KeyError, TypeError, ValueError):
        return None
    if dias in (-1, -2):
        return datetime.combine(agora.date() + timedelta(days=0 if dias == -1 else 1), time(h, m), tzinfo=agora.tzinfo)
    for d in range(-1, 8):
        dia = agora.date() + timedelta(days=d)
        quando = datetime.combine(dia, time(h, m), tzinfo=agora.tzinfo)
        if dias & (1 << dia.weekday()) and quando >= lim:
            return quando
    return None


def pulo_ativo(phone: AlarmeHaPhone, ref: str, agora: datetime, no_celular: dict[str, Any] | None = None) -> datetime | None:
    """Ocorrência que o usuário pulou no app (ainda por vir ou acabou de passar), ou None.
    Vale o que o HA guardou (eventos pulado/religado) e, se o aviso ainda não chegou, o campo
    "pulado" da lista do celular."""
    lim = agora - timedelta(minutes=GUARDA_DEPOIS_MIN)
    candidatos = []
    info = (getattr(phone, "pulados", None) or {}).get(ref)
    if isinstance(info, dict) and info.get("ocorrencia"):
        candidatos.append(info["ocorrencia"])
    elif isinstance(info, dict) and "ocorrencia" in info:
        return None  # religado no app depois do último pulo
    if isinstance(no_celular, dict) and no_celular.get("pulado"):
        candidatos.append(no_celular["pulado"])
    for c in candidatos:
        quando = dt_util.parse_datetime(c) if isinstance(c, str) else None
        if quando is not None and quando >= lim:
            return quando
    return None


def fim_da_guarda(alarmes: list[dict[str, Any] | None], agora: datetime) -> datetime | None:
    """Se algum disparo cai em [agora - GUARDA_DEPOIS_MIN, agora + GUARDA_ANTES_MIN], devolve
    quando a guarda acaba (disparo + GUARDA_DEPOIS_MIN); senão None."""
    fim = None
    for a in alarmes:
        for h in _disparos(a, agora):
            if agora - timedelta(minutes=GUARDA_DEPOIS_MIN) <= h <= agora + timedelta(minutes=GUARDA_ANTES_MIN):
                f = h + timedelta(minutes=GUARDA_DEPOIS_MIN)
                fim = f if fim is None or f > fim else fim
    return fim


def _agendar_resync(hass: HomeAssistant, phone: AlarmeHaPhone, quando: datetime, agora: datetime) -> None:
    """Uma ressincronização por celular, logo depois do fim da guarda (substitui a anterior)."""
    from homeassistant.helpers.event import async_call_later  # noqa: PLC0415

    cancelar = _RESYNC.pop(phone.phone_id, None)
    if callable(cancelar):
        cancelar()
    atraso = max(1.0, (quando - agora).total_seconds()) + GUARDA_RESYNC_FOLGA_S

    async def _rodar(_now: Any = None) -> None:
        _RESYNC.pop(phone.phone_id, None)
        try:
            await async_sincronizar(hass, phone)
        except Exception as err:  # noqa: BLE001
            _LOGGER.warning("AlarmeHA agenda: falha ao sincronizar %s: %s", phone.phone_id, err)

    _RESYNC[phone.phone_id] = async_call_later(hass, atraso, _rodar)


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
    no_celular = {a.get("ref"): a for a in (phone.alarms or []) if isinstance(a, dict) and a.get("ref")}
    for ref in list(enviados):  # tipos/alarmes removidos: desliga o alarme antigo
        if ref not in plano and enviados[ref].get("ativo"):
            plano[ref] = {"ativo": False}
    for ref, atual in no_celular.items():  # ligado no celular sem registro de envio (ex.: pareado de novo)
        if ref.startswith("ag_") and ref not in plano and atual.get("ativo"):
            plano[ref] = {"ativo": False}
    # Quem manda é a agenda: se o alarme foi mexido no celular, reenvia o planejado.
    adiar: datetime | None = None

    def guarda(ref: str) -> bool:
        """Alarme prestes a tocar / tocando / que acabou de tocar: não mexe, ressincroniza depois."""
        nonlocal adiar
        fim = fim_da_guarda([enviados.get(ref), no_celular.get(ref)], agora)
        if fim is None:
            return False
        adiar = fim if adiar is None or fim < adiar else adiar
        return True

    if no_celular:
        for ref, ant in list(enviados.items()):
            if not ant.get("ativo") or phone.ocupado(ref) or guarda(ref):
                continue
            if pulo_ativo(phone, ref, agora, no_celular.get(ref)):
                continue  # pulado no app: o "desligado" é do usuário, não reenvia
            atual = no_celular.get(ref)
            if atual is None or (atual.get("hora"), atual.get("minuto"), bool(atual.get("ativo"))) != (ant.get("hora"), ant.get("minuto"), True):
                enviados.pop(ref)
    mudou = False
    for ref, alvo in plano.items():
        if enviados.get(ref) == alvo:
            continue
        if not alvo["ativo"] and ref not in enviados and not (no_celular.get(ref) or {}).get("ativo"):
            continue  # nunca criado e nada a ligar
        pulo = pulo_ativo(phone, ref, agora, no_celular.get(ref))
        if pulo is not None and alvo["ativo"] and proximo_disparo(alvo, agora) == pulo:
            # O usuário pulou essa ocorrência no app: não religa nem reedita. Depois dela, segue normal.
            _LOGGER.debug("AlarmeHA agenda: %s pulado no app até %s", ref, pulo)
            continue
        if phone.ocupado(ref):
            # Tocando ou em soneca: mexer agora cancelaria a soneca. Fica para depois.
            _LOGGER.debug("AlarmeHA agenda: %s tocando/em soneca, ajuste adiado", ref)
            continue
        if guarda(ref):
            _LOGGER.debug("AlarmeHA agenda: %s perto do disparo, ajuste adiado", ref)
            continue
        try:
            if alvo["ativo"] or alvo.get("tocou"):
                # "tocou": desligado, mas fica no grupo do app (esmaecido) até o grupo acabar.
                await phone.async_command("criar", {"ref": ref, "vibrar": True, **alvo}, 30)
            else:
                await phone.async_command("desligar", {"ref": ref}, 30)
            enviados[ref] = alvo
            mudou = True
        except Exception as err:  # noqa: BLE001 - tenta de novo no próximo ciclo
            _LOGGER.warning("AlarmeHA agenda: não consegui ajustar %s: %s", ref, err)
    if getattr(phone, "limpar_pulados", None):
        phone.limpar_pulados(agora)
    if mudou:
        phone.agenda = {**migrar(phone.agenda), "_enviados": enviados}
        phone.hub.async_save()
    if adiar is not None:
        _agendar_resync(hass, phone, adiar, agora)
