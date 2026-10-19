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

  set narrow(v) { this._narrow = v; }

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
        select, input[type=date] { font-size: 16px; padding: 6px; border-radius: 6px; border: 1px solid var(--divider-color); background: var(--card-background-color); color: inherit; }
      </style>
      <h1>Despertador</h1>
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
        ${alarmes.length ? "" : `<p class="muted">Nenhum alarme ainda.</p>`}
        ${alarmes.map((al) => `
          <div class="alarme ${al.ativo ? "" : "off"}">
            <div><div class="hora">${pad(al.hora)}:${pad(al.minuto)}</div>
              <div class="muted">${esc(al.rotulo || "")} ${al.rotulo ? "· " : ""}${diasTexto(al.dias)}${al.ref ? "" : " · criado no celular"}</div></div>
            <div>${al.ref ? `
              <button class="btn mini sec" data-cel="${esc(c.id)}" data-op="${al.ativo ? "desligar" : "ligar"}" data-ref="${esc(al.ref)}" ${this._ocupado ? "disabled" : ""}>${al.ativo ? "Desligar" : "Ligar"}</button>
              <button class="btn mini sec" data-editar="${esc(c.id)}" data-ref="${esc(al.ref)}">Editar</button>
              <button class="btn mini sec" data-cel="${esc(c.id)}" data-op="apagar" data-ref="${esc(al.ref)}" ${this._ocupado ? "disabled" : ""}>Apagar</button>` : ""}
            </div>
          </div>`).join("")}
        ${f ? this._formulario(f) : `<button class="btn" data-novo="${esc(c.id)}">Novo alarme</button>
          <button class="btn sec" data-cel="${esc(c.id)}" data-op="listar" ${this._ocupado ? "disabled" : ""}>Atualizar</button>
          <button class="btn sec" data-agenda="${esc(c.id)}">Acordar pela agenda</button>
          <button class="btn sec" data-remover="${esc(c.id)}">Desvincular</button>`}
        ${this._agenda && this._agenda.celular === c.id ? this._cartaoAgenda(this._agenda) : ""}
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

  _cartaoAgenda(a) {
    if (!a.config) return `<div class="novo">${a.erro ? `<div class="erro">${esc(a.erro)}</div>` : `<p class="muted">Lendo a agenda…</p>`}</div>`;
    const c = a.config;
    const h = Math.floor(c.antecedencia / 60), m = c.antecedencia % 60;
    const dia = (iso) => new Date(iso + "T12:00").toLocaleDateString("pt-BR", { weekday: "short", day: "2-digit", month: "2-digit" });
    return `
      <form class="novo" data-agenda-form>
        <h2>Acordar pela agenda</h2>
        <p class="muted">Por enquanto é só a prévia: confira os horários abaixo antes de ligar de verdade.</p>
        ${a.erro ? `<div class="erro">${esc(a.erro)}</div>` : ""}
        <div class="linha"><label>Agenda</label>
          <select name="calendario"><option value="">Nenhuma</option>${a.calendarios.map((k) => `<option value="${esc(k.entity_id)}" ${k.entity_id === c.calendario ? "selected" : ""}>${esc(k.nome)}</option>`).join("")}</select></div>
        ${a.calendarios.length ? "" : `<p class="muted">Nenhuma agenda no HA ainda. Conecte o link iCal pela integração Remote Calendar.</p>`}
        <div class="linha"><label>Acordar antes do 1º compromisso</label>
          <input type="text" inputmode="numeric" name="ant_h" size="2" value="${h}"> h <input type="text" inputmode="numeric" name="ant_m" size="2" value="${pad(m)}"> min</div>
        <div class="linha"><label>Compromissos que contam: das</label>
          <input type="text" inputmode="numeric" name="janela_inicio" size="5" value="${esc(c.janela_inicio)}"> às <input type="text" inputmode="numeric" name="janela_fim" size="5" value="${esc(c.janela_fim)}"></div>
        <div class="linha"><label>Nunca antes de</label> <input type="text" inputmode="numeric" name="minimo" size="5" value="${esc(c.minimo)}"></div>
        <div class="linha"><label>Dia sem compromisso</label>
          <input type="text" inputmode="numeric" name="sem_compromisso" size="5" placeholder="não toca" value="${esc(c.sem_compromisso || "")}">
          <span class="muted">vazio = não toca</span></div>
        <div class="linha"><label>Semanas alternadas: começa uma semana de trabalho em</label>
          <input type="date" name="escala_inicio" value="${esc(c.escala_inicio || "")}"> <span class="muted">vazio = toda semana</span></div>
        <div class="acoes"><button class="btn sec" type="button" data-agenda-fechar>Fechar</button><button class="btn" type="submit">Salvar e recalcular</button></div>
        ${a.salvo ? `<p class="ok">✓ Salvo</p>` : ""}
        <h2>Próximos ${a.previa.length} dias</h2>
        ${a.previa.map((d) => `<div class="alarme ${d.horario ? "" : "off"}"><div><b>${dia(d.dia)}</b> <span class="hora">${d.horario || "—"}</span><div class="muted">${esc(d.motivo)}</div></div></div>`).join("")}
      </form>`;
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
    on("[data-agenda-fechar]", () => { this._agenda = null; this._render(); });
    const af = root.querySelector("[data-agenda-form]");
    if (af) af.addEventListener("submit", (ev) => {
      ev.preventDefault();
      const fd = new FormData(af);
      const hm = (v) => { const t = String(v || "").trim(); if (!t) return null; const [hh, mm = "0"] = t.split(/[:h]/); return `${pad(Math.min(Number(hh) || 0, 23))}:${pad(Math.min(Number(mm) || 0, 59))}`; };
      this._abrirAgenda(this._agenda.celular, {
        calendario: fd.get("calendario") || null,
        antecedencia: (Number(fd.get("ant_h")) || 0) * 60 + (Number(fd.get("ant_m")) || 0),
        janela_inicio: hm(fd.get("janela_inicio")) || "04:00",
        janela_fim: hm(fd.get("janela_fim")) || "12:00",
        minimo: hm(fd.get("minimo")) || "05:00",
        sem_compromisso: hm(fd.get("sem_compromisso")),
        escala_inicio: fd.get("escala_inicio") || null,
      });
    });
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
