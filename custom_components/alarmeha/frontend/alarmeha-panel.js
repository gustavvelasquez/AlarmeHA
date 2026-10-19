// Painel "Despertador" do AlarmeHA: baixar o app, ativar o celular e gerenciar os alarmes.
const DIAS = [["Seg", 1], ["Ter", 2], ["Qua", 4], ["Qui", 8], ["Sex", 16], ["Sáb", 32], ["Dom", 64]];

const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const pad = (n) => String(n).padStart(2, "0");

function diasTexto(d) {
  if (d === -1) return "Hoje";
  if (d === -2) return "Amanhã";
  if (d === 127) return "Todos os dias";
  if (d === 31) return "Dias úteis";
  if (d === 96) return "Fim de semana";
  return DIAS.filter(([, b]) => d & b).map(([n]) => n).join(", ") || "Uma vez";
}

function slug(text) {
  const base = text.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "").slice(0, 40);
  return `${base || "alarme"}_${Math.random().toString(36).slice(2, 6)}`;
}

class AlarmehaPanel extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._status = null;
    this._ativacao = null;
    this._erro = null;
    this._ocupado = false;
    this._form = null;
  }

  set hass(hass) {
    const first = !this._hass;
    this._hass = hass;
    if (first) this._carregar();
  }

  set narrow(v) { const mudou = this._narrow !== v; this._narrow = v; if (mudou && this.shadowRoot && this.shadowRoot.innerHTML && !this._form && !this._agenda) this._render(); }

  connectedCallback() {
    this._timer = setInterval(() => this._carregar(true), 30000);
  }

  disconnectedCallback() { clearInterval(this._timer); clearInterval(this._poll); }

  async _ws(msg, timeoutMs = 15000) {
    // Nunca fica girando para sempre: sem resposta em 15 s vira erro visível.
    return Promise.race([
      this._hass.connection.sendMessagePromise(msg),
      new Promise((_, rej) => setTimeout(() => rej(new Error("O Home Assistant não respondeu. Verifique a conexão e tente de novo.")), timeoutMs)),
    ]);
  }

  // Modo ativação: o painel foi aberto pelo app (?ativar=1) e mostra só a ativação.
  _veioDoApp() { return new URLSearchParams(window.location.search).get("ativar") === "1"; }

  _sairDaAtivacao() {
    const url = new URL(window.location.href);
    url.searchParams.delete("ativar");
    history.replaceState(null, "", url.pathname + url.search + url.hash);
    this._ativ = null;
    clearInterval(this._poll);
    this._render();
  }

  async _carregar(silencioso = false) {
    try {
      this._status = await this._ws({ type: "alarmeha/status" });
      if (!silencioso) this._erro = null;
    } catch (e) {
      if (!silencioso) this._erro = e.message || String(e);
    }
    if (this._status && this._veioDoApp() && !this._ativ) {
      this._ativ = { estado: "gerando" };
      this._render();
      return this._ativar();
    }
    this._verificarAtivado();
    if (!this._form && !this._agenda) this._render();
  }

  _verificarAtivado() {
    const a = this._ativ;
    if (!a || a.estado !== "codigo" || !this._status) return;
    const ativado = this._status.celulares.find((c) => c.meu && c.ativado_em && c.ativado_em >= a.dados.criado_em);
    if (ativado) {
      this._ativ = { estado: "ativado", celular: ativado };
      clearInterval(this._poll);
    }
  }

  async _ativar(companionId) {
    const s = this._status;
    if (s && !s.companion.length) {
      this._ativ = { estado: "sem_companion" };
      return this._render();
    }
    this._ativ = { estado: "gerando" };
    this._render();
    try {
      const msg = { type: "alarmeha/ativar", origem: window.location.origin };
      if (companionId) msg.companion_entry_id = companionId;
      this._ativ = { estado: "codigo", dados: await this._ws(msg) };
      clearInterval(this._poll);
      this._poll = setInterval(() => this._carregar(true), 3000);
    } catch (e) {
      this._ativ = e.code === "choose_companion"
        ? { estado: "escolher" }
        : { estado: "erro", msg: e.message || String(e) };
    }
    this._render();
  }

  async _comando(celular, op, dados) {
    this._ocupado = true;
    this._erro = null;
    this._render();
    try {
      await this._ws({ type: "alarmeha/comando", celular, op, dados, timeout: 20 });
    } catch (e) {
      this._erro = `Não foi possível falar com o celular: ${e.message || e}`;
    }
    this._ocupado = false;
    this._form = null;
    await this._carregar(true);
  }

  async _remover(celular) {
    if (!confirm("Desvincular este celular do Home Assistant? Os alarmes continuam no celular.")) return;
    try { await this._ws({ type: "alarmeha/remover", celular }); } catch (e) { this._erro = e.message; }
    this._carregar();
  }

  _render() {
    const s = this._status;
    const meus = s ? s.celulares.filter((c) => c.meu) : [];
    const outros = s ? s.celulares.filter((c) => !c.meu) : [];
    this.shadowRoot.innerHTML = `
      <style>
        :host { display: block; padding: 16px; max-width: 760px; margin: 0 auto; color: var(--primary-text-color); font-family: var(--paper-font-body1_-_font-family, sans-serif); }
        .card { background: var(--card-background-color, #fff); border-radius: 12px; padding: 16px; margin-bottom: 16px; box-shadow: var(--ha-card-box-shadow, 0 1px 3px rgba(0,0,0,.2)); }
        h1 { font-size: 24px; margin: 8px 0 16px; } h2 { font-size: 18px; margin: 0 0 8px; }
        .muted { color: var(--secondary-text-color); font-size: 14px; }
        .btn { display: inline-block; background: var(--primary-color); color: var(--text-primary-color, #fff); border: 0; border-radius: 8px; padding: 10px 16px; font-size: 15px; cursor: pointer; text-decoration: none; margin: 4px 4px 4px 0; }
        .btn.sec { background: transparent; color: var(--primary-color); border: 1px solid var(--primary-color); }
        .btn.grande { display: block; text-align: center; font-size: 20px; padding: 18px; margin: 8px 0; }
        .ok { font-size: 20px; color: var(--success-color, #43a047); font-weight: 500; }
        .destaque { border: 2px solid var(--primary-color); }
        .btn.mini { padding: 4px 10px; font-size: 13px; }
        .btn[disabled] { opacity: .5; }
        .erro { background: var(--error-color, #db4437); color: #fff; padding: 10px; border-radius: 8px; margin-bottom: 16px; }
        .alarme { display: flex; align-items: center; justify-content: space-between; border-top: 1px solid var(--divider-color); padding: 10px 0; gap: 8px; flex-wrap: wrap; }
        .hora { font-size: 26px; font-weight: 500; } .off { opacity: .45; }
        code { background: var(--secondary-background-color); padding: 2px 6px; border-radius: 4px; word-break: break-all; }
        input[type=text], input[type=time] { font-size: 16px; padding: 6px; border-radius: 6px; border: 1px solid var(--divider-color); background: var(--card-background-color); color: inherit; }
        label { margin-right: 8px; white-space: nowrap; } .linha { margin: 10px 0; }
        details { margin-top: 8px; }
        .novo { border-top: 1px solid var(--divider-color); margin-top: 8px; padding-top: 12px; }
        .relogio { display: flex; justify-content: center; align-items: center; gap: 4px; }
        .giro { display: flex; flex-direction: column; align-items: center; }
        .dig { width: 2.2ch; font-size: 56px; text-align: center; border: 0; background: transparent; color: var(--primary-color); font-weight: 500; padding: 0; }
        .dig:focus { outline: 2px solid var(--primary-color); border-radius: 8px; }
        .dois { font-size: 48px; color: var(--primary-color); }
        .seta { background: transparent; border: 0; color: var(--secondary-text-color); font-size: 22px; padding: 6px 20px; cursor: pointer; }
        .dias { display: flex; justify-content: center; gap: 6px; margin-top: 12px; }
        .dia input { display: none; }
        .dia span { display: inline-flex; width: 38px; height: 38px; border-radius: 50%; align-items: center; justify-content: center; border: 1px solid var(--divider-color); cursor: pointer; font-weight: 500; }
        .dia input:checked + span { background: var(--primary-color); color: var(--text-primary-color, #fff); border-color: var(--primary-color); }
        .centro { text-align: center; }
        .rot { width: 100%; box-sizing: border-box; font-size: 16px; padding: 10px; border-radius: 8px; border: 1px solid var(--divider-color); background: var(--card-background-color); color: inherit; margin: 8px 0; }
        .acoes { display: flex; justify-content: flex-end; }
        .abas { display: flex; gap: 0; margin: 4px 0 12px; border-bottom: 1px solid var(--divider-color); }
        .aba { flex: 1; background: transparent; border: 0; border-bottom: 3px solid transparent; color: var(--secondary-text-color); font-size: 16px; padding: 10px; cursor: pointer; }
        .aba.sel { color: var(--primary-color); border-bottom-color: var(--primary-color); font-weight: 500; }
        .item { display: flex; flex-direction: column; align-items: flex-start; position: relative; width: 100%; text-align: left; background: var(--secondary-background-color); color: inherit; border: 0; border-radius: 10px; padding: 12px 32px 12px 12px; margin: 6px 0; font-size: 16px; cursor: pointer; gap: 2px; }
        .vai { position: absolute; right: 12px; top: 50%; transform: translateY(-50%); font-size: 22px; color: var(--secondary-text-color); }
        .sub { margin-top: 18px; }
        .hora.mini { font-size: 18px; }
        .aviso { background: var(--success-color, #43a047); color: #fff; padding: 8px 12px; border-radius: 8px; margin-bottom: 8px; }
        .chave { display: flex; align-items: center; gap: 10px; font-size: 16px; white-space: normal; margin: 8px 0; }
        .chave input { width: 22px; height: 22px; }
        .chave span { display: none; }
        .rodape { position: sticky; bottom: 0; display: flex; justify-content: flex-end; gap: 8px; background: var(--card-background-color, #fff); padding: 10px 0; margin-top: 12px; border-top: 1px solid var(--divider-color); }
        .x { background: transparent; border: 0; color: var(--secondary-text-color); font-size: 18px; cursor: pointer; padding: 4px 10px; }
        .perigo { color: var(--error-color, #db4437); border-color: var(--error-color, #db4437); }
        .topo { display: flex; align-items: center; gap: 4px; } .topo h1 { margin: 8px 0; }
        .menu { background: transparent; border: 0; color: inherit; font-size: 26px; padding: 4px 12px 4px 0; cursor: pointer; }
        .selo { display: inline-block; background: var(--primary-color); color: var(--text-primary-color, #fff); border-radius: 10px; padding: 0 8px; font-size: 12px; }
        .tipo { border: 1px solid var(--divider-color); border-radius: 10px; padding: 10px; margin: 8px 0; }
        .al { margin: 6px 0; }
        select, input[type=date] { font-size: 16px; padding: 6px; border-radius: 6px; border: 1px solid var(--divider-color); background: var(--card-background-color); color: inherit; }
      </style>
      <div class="topo">${this._narrow ? `<button class="menu" data-menu title="Menu">☰</button>` : ""}<h1>Despertador</h1></div>
      ${this._erro ? `<div class="erro">${esc(this._erro)}</div>` : ""}
      ${!s ? `<div class="card">Carregando…</div>` : this._veioDoApp() ? this._cartaoAtivacao(s) : `
        ${meus.map((c) => this._celular(c)).join("")}
        ${meus.length ? `<div class="card"><details><summary>Instalar o app em outro celular</summary>${this._passos(s, true)}</details></div>` : this._passos(s)}
        ${outros.length ? `<h2>Outros celulares (admin)</h2>${outros.map((c) => this._celular(c)).join("")}` : ""}
      `}`;
    this._ligar();
  }

  _cartaoAtivacao(s) {
    const a = this._ativ || { estado: "gerando" };
    let corpo;
    if (a.estado === "escolher") {
      corpo = `<p>Você tem mais de um celular com o app Home Assistant. Qual é este?</p>` +
        s.companion.map((d) => `<button class="btn sec" data-companion="${esc(d.entry_id)}">${esc(d.nome)}</button>`).join("");
    } else if (a.estado === "codigo") {
      corpo = `
        <p>Toque no botão e confirme no AlarmeHA.</p>
        <a class="btn grande" href="${esc(a.dados.intent_link)}">Confirmar ativação</a>
        <p class="muted">Esta tela muda sozinha quando o celular for ativado. O código vale ${Math.round(a.dados.expira_em / 60)} minutos.</p>
        <details><summary class="muted">O AlarmeHA não abriu?</summary>
          <p class="muted">No AlarmeHA, toque em <b>Não abriu? Ativar manualmente</b> → <b>Colar configuração</b> e cole:</p>
          <p><code>${esc(a.dados.texto_pareamento)}</code> <button class="btn mini sec" data-copiar>Copiar</button></p>
        </details>`;
    } else if (a.estado === "ativado") {
      corpo = `
        <p class="ok">✓ Celular ativado${a.celular.aparelho ? `: ${esc(a.celular.aparelho)}` : ""}.</p>
        <p class="muted">Pode voltar para o AlarmeHA.</p>
        <button class="btn" data-sair>Ver meus alarmes aqui</button>`;
    } else if (a.estado === "erro") {
      corpo = `<div class="erro">${esc(a.msg)}</div><button class="btn" data-ativar>Tentar de novo</button>`;
    } else if (a.estado === "sem_companion") {
      corpo = `<p>Não achei o app Home Assistant com a sua conta neste HA. Abra este painel pelo app Home Assistant do celular que vai despertar, logado com a sua conta.</p>
        <button class="btn" data-ativar>Tentar de novo</button>`;
    } else {
      corpo = `<p class="muted">Gerando o código…</p>`;
    }
    return `<div class="card destaque"><h2>Ativar este celular</h2>${corpo}</div>`;
  }

  _passos(s, compacto = false) {
    return `
      <div class="${compacto ? "" : "card"}">
        <h2>Baixar e instalar o AlarmeHA</h2>
        <p class="muted">Toque abaixo no celular que vai despertar. Se o Android pedir, permita instalar apps desta fonte; se o Play Protect avisar, toque em <i>Instalar mesmo assim</i>.</p>
        <a class="btn" href="${esc(s.apk_url)}" download="AlarmeHA.apk">Baixar e instalar</a>
        <p class="muted">Quando o download terminar, toque em <b>Abrir</b> (ou na notificação do download) para instalar.</p>
        <p><b>Depois, abra o app AlarmeHA</b> e toque em <b>Ativar</b>. Ele traz você de volta aqui para confirmar.</p>
        <details><summary class="muted">O download não funcionou?</summary>
          <p class="muted">Abra a <a href="${esc(s.releases_url)}" target="_blank" rel="noopener">página da última versão no GitHub</a> e baixe o arquivo <b>AlarmeHA.apk</b>. No Chrome, se aparecer "o arquivo pode ser perigoso", toque em <i>Baixar mesmo assim</i>.</p>
        </details>
      </div>`;
  }

  _celular(c) {
    const alarmes = [...c.alarmes].sort((x, y) => x.hora * 60 + x.minuto - (y.hora * 60 + y.minuto));
    const f = this._form && this._form.celular === c.id ? this._form : null;
    return `
      <div class="card">
        <h2>${esc(c.pessoa)}${c.aparelho ? ` <span class="muted">· ${esc(c.aparelho)}</span>` : ""}</h2>
        <p class="muted">${c.conectado ? "Conectado" : "Sem contato recente"}${c.proximo_alarme ? ` · próximo alarme ${new Date(c.proximo_alarme).toLocaleString()}` : ""}</p>
        <div class="abas"><button class="aba ${this._agenda && this._agenda.celular === c.id ? "" : "sel"}" data-aba-alarmes="${esc(c.id)}">Alarmes</button><button class="aba ${this._agenda && this._agenda.celular === c.id ? "sel" : ""}" data-agenda="${esc(c.id)}">Agenda</button></div>
        ${this._agenda && this._agenda.celular === c.id ? this._cartaoAgenda(this._agenda) : `
        ${alarmes.length ? "" : `<p class="muted">Nenhum alarme ainda.</p>`}
        ${alarmes.map((al) => `
          <div class="alarme ${al.ativo ? "" : "off"}">
            <div><div class="hora">${pad(al.hora)}:${pad(al.minuto)}</div>
              <div class="muted">${esc(al.rotulo || "")} ${al.rotulo ? "· " : ""}${diasTexto(al.dias)}${al.ref ? "" : " · criado no celular"}${(al.ref || "").startsWith("ag_") ? ` <span class="selo">pela agenda</span>` : ""}</div></div>
            <div>${(al.ref || "").startsWith("ag_") ? `<button class="btn mini sec" data-agenda="${esc(c.id)}">Mudar na Agenda</button>` : al.ref ? `
              <button class="btn mini sec" data-cel="${esc(c.id)}" data-op="${al.ativo ? "desligar" : "ligar"}" data-ref="${esc(al.ref)}" ${this._ocupado ? "disabled" : ""}>${al.ativo ? "Desligar" : "Ligar"}</button>
              <button class="btn mini sec" data-editar="${esc(c.id)}" data-ref="${esc(al.ref)}">Editar</button>
              <button class="btn mini sec" data-cel="${esc(c.id)}" data-op="apagar" data-ref="${esc(al.ref)}" ${this._ocupado ? "disabled" : ""}>Apagar</button>` : ""}
            </div>
          </div>`).join("")}
        ${f ? this._formulario(f) : `<button class="btn" data-novo="${esc(c.id)}">Novo alarme</button>
          <button class="btn sec" data-cel="${esc(c.id)}" data-op="listar" ${this._ocupado ? "disabled" : ""}>Atualizar</button>
          <button class="btn sec" data-remover="${esc(c.id)}">Desvincular</button>`}`}
      </div>`;
  }

  async _abrirAgenda(celular, salvar) {
    this._agenda = { celular, carregando: true, ...(this._agenda && this._agenda.celular === celular ? this._agenda : {}) };
    this._render();
    try {
      const msg = { type: "alarmeha/agenda", celular };
      if (salvar) msg.salvar = salvar;
      this._agenda = { celular, ...(await this._ws(msg, 30000)), salvo: !!salvar };
    } catch (e) {
      this._agenda = { celular, erro: e.message || String(e) };
    }
    this._render();
  }

  _resumoTipo(t) {
    const fmt = (m) => (m === 0 ? "na hora" : `${m >= 60 ? `${Math.floor(m / 60)}h` : ""}${m % 60 ? `${pad(m % 60)}min` : ""} antes`.replace(/^(\d+h)(\d+min)/, "$1$2"));
    const as = t.alarmes.map((x) => x.antes).sort((x, y) => y - x);
    const quando = as.length === 1 ? fmt(as[0]) : `de ${fmt(as[0])} até ${as[as.length - 1] === 0 ? "a hora" : fmt(as[as.length - 1])}`;
    const como = t.id === "padrao" ? "qualquer outro evento" : [...(t.palavras || []).map((w) => `"${w}"`), ...(t.agendas || []).map((k) => (this._agenda.calendarios.find((x) => x.entity_id === k) || { nome: k }).nome)].join(", ") || "sem regra: não reconhece nada";
    return `${as.length} alarme${as.length > 1 ? "s" : ""}, ${quando} · ${esc(como)}`;
  }

  _cartaoAgenda(a) {
    if (!a.config) return a.erro ? `<div class="erro">${esc(a.erro)}</div>` : `<p class="muted">Lendo a agenda…</p>`;
    if (a.rasc) return this._editorAgenda(a);
    const c = a.config;
    const dia = (iso) => new Date(iso + "T12:00").toLocaleDateString("pt-BR", { weekday: "short", day: "2-digit", month: "2-digit" });
    return `
      ${a.erro ? `<div class="erro">${esc(a.erro)}</div>` : ""}
      ${a.salvo ? `<div class="aviso">✓ Salvo</div>` : ""}
      ${a.calendarios.length ? "" : `<p class="muted">Nenhuma agenda conectada ao HA ainda. Você já pode criar os tipos; a prévia passa a mostrar seus compromissos quando a agenda for conectada.</p>`}
      <label class="chave"><input type="checkbox" data-ligado ${c.ligado ? "checked" : ""}><span></span> Ajustar meus alarmes pela agenda</label>
      <h2 class="sub">Tipos de evento</h2>
      ${c.tipos.map((t, i) => `<button class="item" data-tipo-abrir="${i}"><b>${esc(t.nome)}</b><span class="muted">${this._resumoTipo(t)}</span><span class="vai">›</span></button>`).join("")}
      <button class="btn sec" data-tipo-add>+ Novo tipo</button>
      <button class="item" data-ajustes><b>Ajustes</b><span class="muted">Dia sem compromisso: ${c.sem_compromisso ? esc(c.sem_compromisso) : "não toca"} · ${c.escala_inicio ? "semanas alternadas" : "toda semana"}</span><span class="vai">›</span></button>
      <h2 class="sub">Próximos ${a.previa.length} dias</h2>
      ${a.previa.map((d) => `<div class="alarme ${d.horarios.length ? "" : "off"}"><div><b>${dia(d.dia)}</b> <span class="hora mini">${d.horarios.join(" · ") || "—"}</span><div class="muted">${esc(d.motivo)}</div></div></div>`).join("")}`;
  }

  _editorAgenda(a) {
    const r = a.rasc;
    const rodape = `<div class="rodape"><button class="btn sec" type="button" data-rasc-cancelar>Cancelar</button><button class="btn" type="submit">Salvar</button></div>`;
    if (r.ajustes) return `
      <form data-rasc-form>
        <h2 class="sub">Ajustes</h2>
        <div class="linha"><b>Dia sem compromisso</b><br><input type="text" inputmode="numeric" size="6" data-k="sem_compromisso" placeholder="não toca" value="${esc(r.sem_compromisso || "")}"> <span class="muted">vazio = não toca; ou um horário, ex.: 08:00</span></div>
        <div class="linha"><b>Semanas alternadas</b><br><span class="muted">Se você trabalha semana sim, semana não, informe o primeiro dia de uma semana de trabalho. Vazio = toda semana.</span><br><input type="date" data-k="escala_inicio" value="${esc(r.escala_inicio || "")}"></div>
        ${rodape}
      </form>`;
    const t = r.tipo, cal = a.calendarios;
    return `
      <form data-rasc-form>
        <h2 class="sub">${r.novo ? "Novo tipo" : "Editar tipo"}</h2>
        ${t.id === "padrao" ? `<p><b>Padrão</b></p><p class="muted">Vale para todo evento que não for de outro tipo.</p>` : `
        <div class="linha"><b>Nome</b><input class="rot" type="text" data-k="nome" value="${esc(t.nome)}" placeholder="ex.: Plantão"></div>
        <div class="linha"><b>Como reconhecer</b><br><span class="muted">Palavras no título do evento (separe por vírgula)</span><input class="rot" type="text" data-k="palavras" value="${esc((t.palavras || []).join(", "))}" placeholder="ex.: plantão, turno">
          ${cal.length ? `<span class="muted">ou todos os eventos destas agendas</span><br>${cal.map((k) => `<label><input type="checkbox" data-k="agenda" value="${esc(k.entity_id)}" ${(t.agendas || []).includes(k.entity_id) ? "checked" : ""}> ${esc(k.nome)}</label>`).join("<br>")}` : ""}</div>`}
        <div class="linha"><b>Alarmes</b> <span class="muted">quanto tempo antes do evento</span>
          ${t.alarmes.map((al, ai) => `<div class="al" data-al="${ai}">
            <input type="text" inputmode="numeric" size="2" data-k="h" value="${Math.floor(al.antes / 60)}"> h
            <input type="text" inputmode="numeric" size="2" data-k="m" value="${pad(al.antes % 60)}"> min
            ${t.alarmes.length > 1 ? `<button type="button" class="x" data-al-rm="${ai}" title="Remover">✕</button>` : ""}</div>`).join("")}
          <button type="button" class="btn mini sec" data-al-add>+ Alarme</button></div>
        <div class="linha"><b>Nunca antes de</b> <span class="muted">(opcional)</span><br><input type="text" inputmode="numeric" size="6" data-k="minimo" placeholder="ex.: 05:00" value="${esc(t.minimo || "")}"></div>
        ${t.id === "padrao" || r.novo ? "" : `<button type="button" class="btn mini sec perigo" data-tipo-rm>Apagar este tipo</button>`}
        ${rodape}
      </form>`;
  }

  // Lê o editor aberto de volta para this._agenda.rasc.
  _lerAgenda() {
    const root = this.shadowRoot, r = this._agenda.rasc;
    const hm = (v) => { const t = String(v || "").trim(); if (!t) return null; const [hh, mm = "0"] = t.split(/[:h]/); return `${pad(Math.min(Number(hh) || 0, 23))}:${pad(Math.min(Number(mm) || 0, 59))}`; };
    const v = (k) => { const el = root.querySelector(`[data-rasc-form] [data-k=${k}]`); return el ? el.value : null; };
    if (r.ajustes) { r.sem_compromisso = hm(v("sem_compromisso")); r.escala_inicio = v("escala_inicio") || null; return r; }
    const t = r.tipo;
    if (v("nome") !== null) t.nome = v("nome").trim();
    if (v("palavras") !== null) t.palavras = v("palavras").split(",").map((x) => x.trim()).filter(Boolean);
    t.agendas = [...root.querySelectorAll("[data-rasc-form] [data-k=agenda]:checked")].map((x) => x.value);
    t.alarmes = [...root.querySelectorAll("[data-al]")].map((x) => ({ antes: (Number(x.querySelector("[data-k=h]").value) || 0) * 60 + (Number(x.querySelector("[data-k=m]").value) || 0) }));
    t.minimo = hm(v("minimo"));
    return r;
  }

  _formulario(f) {
    // Igual ao app: hora grande com setas e teclado numérico, dias em bolinhas.
    const campo = (nome, v, max) => `
      <div class="giro">
        <button type="button" class="seta" data-passo="${nome}" data-d="1" data-max="${max}">▲</button>
        <input class="dig" type="text" inputmode="numeric" pattern="[0-9]*" maxlength="2" name="${nome}" value="${pad(v)}" data-max="${max}">
        <button type="button" class="seta" data-passo="${nome}" data-d="-1" data-max="${max}">▼</button>
      </div>`;
    return `
      <form class="novo" data-form>
        <div class="relogio">${campo("h", f.hora, 23)}<span class="dois">:</span>${campo("m", f.minuto, 59)}</div>
        <div class="dias">${DIAS.map(([n, b]) => `<label class="dia"><input type="checkbox" name="dia" value="${b}" ${f.dias > 0 && f.dias & b ? "checked" : ""}><span>${n[0]}</span></label>`).join("")}</div>
        <p class="muted centro">Sem dias marcados, toca uma vez.</p>
        <input class="rot" type="text" name="rotulo" maxlength="64" placeholder="Rótulo (opcional)" value="${esc(f.rotulo)}">
        <label class="linha"><input type="checkbox" name="vibrar" ${f.vibrar ? "checked" : ""}> Vibrar</label>
        <div class="acoes"><button class="btn sec" type="button" data-cancelar>Cancelar</button>
          <button class="btn" type="submit" ${this._ocupado ? "disabled" : ""}>${this._ocupado ? "Enviando…" : "Salvar"}</button></div>
      </form>`;
  }

  _ligar() {
    const root = this.shadowRoot;
    const on = (sel, fn) => root.querySelectorAll(sel).forEach((el) => el.addEventListener("click", (ev) => fn(el, ev)));
    on("[data-ativar]", async () => { await this._carregar(true); this._ativar(); });
    on("[data-companion]", (el) => this._ativar(el.dataset.companion));
    on("[data-sair]", () => this._sairDaAtivacao());
    on("[data-copiar]", () => navigator.clipboard && navigator.clipboard.writeText(this._ativ.dados.texto_pareamento));
    on("[data-op]", (el) => {
      if (el.dataset.op === "apagar" && !confirm("Apagar este alarme do celular?")) return;
      this._comando(el.dataset.cel, el.dataset.op, el.dataset.ref ? { ref: el.dataset.ref } : {});
    });
    on("[data-remover]", (el) => this._remover(el.dataset.remover));
    on("[data-novo]", (el) => { this._form = { celular: el.dataset.novo, hora: 7, minuto: 0, dias: 31, rotulo: "", vibrar: true }; this._render(); });
    on("[data-editar]", (el) => {
      const cel = this._status.celulares.find((c) => c.id === el.dataset.editar);
      const al = cel && cel.alarmes.find((a) => a.ref === el.dataset.ref);
      if (al) { this._form = { celular: cel.id, ...al }; this._render(); }
    });
    on("[data-cancelar]", () => { this._form = null; this._render(); });
    const ajusta = (inp, d) => {
      const max = Number(inp.dataset.max) + 1;
      inp.value = pad(((Number(inp.value) || 0) + d + max) % max);
    };
    on("[data-passo]", (el) => ajusta(root.querySelector(`input[name=${el.dataset.passo}]`), Number(el.dataset.d)));
    root.querySelectorAll(".dig").forEach((inp) => {
      inp.addEventListener("focus", () => inp.select());
      inp.addEventListener("input", () => { inp.value = inp.value.replace(/\D/g, ""); });
      inp.addEventListener("blur", () => { inp.value = pad(Math.min(Number(inp.value) || 0, Number(inp.dataset.max))); });
    });
    on("[data-agenda]", (el) => this._abrirAgenda(el.dataset.agenda));
    on("[data-menu]", () => this.dispatchEvent(new Event("hass-toggle-menu", { bubbles: true, composed: true })));
    on("[data-aba-alarmes]", () => { this._agenda = null; this._render(); });
    if (this._agenda && this._agenda.config) {
      const A = this._agenda, c = A.config;
      const salvarTudo = (mudanca) => this._abrirAgenda(A.celular, { ligado: c.ligado, sem_compromisso: c.sem_compromisso, escala_inicio: c.escala_inicio, tipos: c.tipos, ...mudanca });
      on("[data-ligado]", (el) => salvarTudo({ ligado: el.checked }), "change");
      on("[data-tipo-abrir]", (el) => { const i = Number(el.dataset.tipoAbrir); A.rasc = { i, tipo: JSON.parse(JSON.stringify(c.tipos[i])) }; A.salvo = false; this._render(); });
      on("[data-tipo-add]", () => { A.rasc = { novo: true, tipo: { nome: "", agendas: [], palavras: [], alarmes: [{ antes: 0 }], minimo: null } }; A.salvo = false; this._render(); });
      on("[data-ajustes]", () => { A.rasc = { ajustes: true, sem_compromisso: c.sem_compromisso, escala_inicio: c.escala_inicio }; A.salvo = false; this._render(); });
      on("[data-rasc-cancelar]", () => { A.rasc = null; this._render(); });
      on("[data-al-add]", () => { const r = this._lerAgenda(); const ult = r.tipo.alarmes[r.tipo.alarmes.length - 1]; r.tipo.alarmes.push({ antes: ult ? ult.antes + 15 : 0 }); this._render(); });
      on("[data-al-rm]", (el) => { const r = this._lerAgenda(); r.tipo.alarmes.splice(Number(el.dataset.alRm), 1); this._render(); });
      on("[data-tipo-rm]", () => { if (!confirm("Apagar este tipo? Os alarmes dele no celular serão desligados.")) return; const tipos = c.tipos.filter((_, i) => i !== A.rasc.i); A.rasc = null; salvarTudo({ tipos }); });
      const rf = root.querySelector("[data-rasc-form]");
      if (rf) rf.addEventListener("submit", (ev) => {
        ev.preventDefault();
        const r = this._lerAgenda();
        if (r.ajustes) { A.rasc = null; return salvarTudo({ sem_compromisso: r.sem_compromisso, escala_inicio: r.escala_inicio }); }
        if (!r.tipo.nome) { alert("Dê um nome ao tipo."); return; }
        const tipos = [...c.tipos];
        if (r.novo) tipos.push(r.tipo); else tipos[r.i] = r.tipo;
        A.rasc = null;
        salvarTudo({ tipos });
      });
    }
    const form = root.querySelector("[data-form]");
    if (form) form.addEventListener("submit", (ev) => {
      ev.preventDefault();
      const fd = new FormData(form);
      const h = Math.min(Number(fd.get("h")) || 0, 23), m = Math.min(Number(fd.get("m")) || 0, 59);
      const dias = fd.getAll("dia").reduce((acc, v) => acc + Number(v), 0);
      const f = this._form;
      const rotulo = String(fd.get("rotulo") || "").trim();
      const dados = { ref: f.ref || slug(rotulo), hora: h, minuto: m, dias: dias || -1, rotulo, vibrar: fd.get("vibrar") === "on", ativo: true };
      if (!dias) {
        // Uma vez: hoje se ainda não passou, senão amanhã.
        const agora = new Date();
        dados.dias = h * 60 + m > agora.getHours() * 60 + agora.getMinutes() ? -1 : -2;
      }
      this._comando(f.celular, f.ref ? "editar" : "criar", dados);
    });
  }
}

// A tag leva a versão (?v= do módulo), igual ao webcomponent_name registrado pela integração.
const versao = new URL(import.meta.url).searchParams.get("v") || "dev";
const tag = `alarmeha-panel-${versao.replace(/[^a-z0-9]/gi, "-")}`;
if (!customElements.get(tag)) customElements.define(tag, AlarmehaPanel);
