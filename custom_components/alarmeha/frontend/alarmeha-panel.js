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

  disconnectedCallback() { clearInterval(this._timer); }

  async _ws(msg) { return this._hass.connection.sendMessagePromise(msg); }

  async _carregar(silencioso = false) {
    try {
      this._status = await this._ws({ type: "alarmeha/status" });
      if (!silencioso) this._erro = null;
    } catch (e) {
      this._erro = e.message || String(e);
    }
    if (!this._form) this._render();
  }

  async _ativar(companionId) {
    this._erro = null;
    try {
      const msg = { type: "alarmeha/ativar" };
      if (companionId) msg.companion_entry_id = companionId;
      this._ativacao = await this._ws(msg);
    } catch (e) {
      if (e.code === "choose_companion") this._ativacao = { escolher: true };
      else this._erro = e.message || String(e);
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
        .btn.mini { padding: 4px 10px; font-size: 13px; }
        .btn[disabled] { opacity: .5; }
        .erro { background: var(--error-color, #db4437); color: #fff; padding: 10px; border-radius: 8px; margin-bottom: 16px; }
        .alarme { display: flex; align-items: center; justify-content: space-between; border-top: 1px solid var(--divider-color); padding: 10px 0; gap: 8px; flex-wrap: wrap; }
        .hora { font-size: 26px; font-weight: 500; } .off { opacity: .45; }
        code { background: var(--secondary-background-color); padding: 2px 6px; border-radius: 4px; word-break: break-all; }
        input[type=text], input[type=time] { font-size: 16px; padding: 6px; border-radius: 6px; border: 1px solid var(--divider-color); background: var(--card-background-color); color: inherit; }
        label { margin-right: 8px; white-space: nowrap; } .linha { margin: 10px 0; }
        details { margin-top: 8px; }
      </style>
      <h1>Despertador</h1>
      ${this._erro ? `<div class="erro">${esc(this._erro)}</div>` : ""}
      ${!s ? `<div class="card">Carregando…</div>` : `
        ${meus.length ? meus.map((c) => this._celular(c)).join("") : this._passos(s)}
        ${meus.length ? `<div class="card"><details><summary>Instalar ou ativar outro celular</summary>${this._passos(s, true)}</details></div>` : ""}
        ${outros.length ? `<h2>Outros celulares (admin)</h2>${outros.map((c) => this._celular(c)).join("")}` : ""}
      `}`;
    this._ligar();
  }

  _passos(s, compacto = false) {
    const a = this._ativacao;
    let passo2;
    if (a && a.escolher) {
      passo2 = `<p>Você tem mais de um celular com o Companion. Qual deles?</p>` +
        s.companion.map((d) => `<button class="btn sec" data-companion="${esc(d.entry_id)}">${esc(d.nome)}</button>`).join("");
    } else if (a && a.codigo) {
      passo2 = `
        <p>Toque no botão abaixo <b>neste celular</b> e confirme no AlarmeHA:</p>
        <a class="btn" href="${esc(a.intent_link)}">Abrir o AlarmeHA e ativar</a>
        <p class="muted">O código vale por ${Math.round(a.expira_em / 60)} minutos e só pode ser usado uma vez.</p>
        <details><summary class="muted">Não abriu?</summary>
          <p class="muted">No AlarmeHA, vá em Configurações → Home Assistant → Avançado → <b>Colar configuração</b> e cole:</p>
          <p><code>${esc(a.texto_pareamento)}</code> <button class="btn mini sec" data-copiar>Copiar</button></p>
        </details>
        <button class="btn sec" data-recarregar>Já ativei</button>`;
    } else if (!s.companion.length) {
      passo2 = `<p class="muted">Abra este painel pelo app <b>Home Assistant Companion</b> no celular que vai despertar, entrando com a sua conta.</p>`;
    } else {
      passo2 = `<button class="btn" data-ativar>Ativar</button>`;
    }
    return `
      <div class="${compacto ? "" : "card"}">
        <h2>1. Baixe o app</h2>
        <p class="muted">No celular, baixe e instale o AlarmeHA (permita instalar de fontes desconhecidas se o Android pedir).</p>
        <a class="btn sec" href="${esc(s.apk_url)}" target="_blank" rel="noopener">Baixar AlarmeHA.apk</a>
        <h2 style="margin-top:16px">2. Ative</h2>
        ${passo2}
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
          <button class="btn sec" data-remover="${esc(c.id)}">Desvincular</button>`}
      </div>`;
  }

  _formulario(f) {
    return `
      <form class="linha" data-form>
        <div class="linha"><input type="time" name="hora" required value="${pad(f.hora)}:${pad(f.minuto)}">
          <input type="text" name="rotulo" maxlength="64" placeholder="Nome (opcional)" value="${esc(f.rotulo)}"></div>
        <div class="linha">${DIAS.map(([n, b]) => `<label><input type="checkbox" name="dia" value="${b}" ${f.dias > 0 && f.dias & b ? "checked" : ""}>${n}</label>`).join("")}</div>
        <div class="linha"><label><input type="checkbox" name="vibrar" ${f.vibrar ? "checked" : ""}>Vibrar</label>
          <span class="muted">Sem dias marcados = toca uma vez.</span></div>
        <button class="btn" type="submit" ${this._ocupado ? "disabled" : ""}>${this._ocupado ? "Enviando…" : "Salvar"}</button>
        <button class="btn sec" type="button" data-cancelar>Cancelar</button>
      </form>`;
  }

  _ligar() {
    const root = this.shadowRoot;
    const on = (sel, fn) => root.querySelectorAll(sel).forEach((el) => el.addEventListener("click", (ev) => fn(el, ev)));
    on("[data-ativar]", () => this._ativar());
    on("[data-companion]", (el) => this._ativar(el.dataset.companion));
    on("[data-recarregar]", () => { this._ativacao = null; this._carregar(); });
    on("[data-copiar]", () => navigator.clipboard && navigator.clipboard.writeText(this._ativacao.texto_pareamento));
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
    const form = root.querySelector("[data-form]");
    if (form) form.addEventListener("submit", (ev) => {
      ev.preventDefault();
      const fd = new FormData(form);
      const [h, m] = String(fd.get("hora")).split(":").map(Number);
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

customElements.define("alarmeha-panel", AlarmehaPanel);
