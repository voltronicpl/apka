// Sekurak – karta w lewym menu HA: czujniki (dodaj / edytuj / usuń) i czuwanie alarmu.
const esc = (v) =>
  String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const STATUS = {
  brak: ["Brak encji czuwania – czujniki Alarmu działają zawsze", "neutral"],
  rozbrojone: ["Rozbrojone – czujniki Alarmu nie dzwonią", "neutral"],
  wyjscie: ["Czas na wyjście – zaraz uzbroi", "warn"],
  uzbrojone: ["Uzbrojone – czujniki Alarmu czuwają", "ok"],
};

class SekurakPanel extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._data = null;
    this._entryId = null;
    this._form = null; // {id?, entity_id, name, typ, invert}
    this._err = "";
    this._built = false;
  }

  set hass(h) {
    const first = !this._hass;
    this._hass = h;
    if (!this._built) this._build();
    const mb = this.shadowRoot.querySelector("ha-menu-button");
    if (mb) { mb.hass = h; mb.narrow = this.narrow; }
    if (first) this._load();
    else this._liveStates();
  }
  get hass() { return this._hass; }

  set narrow(n) {
    this._narrow = n;
    const mb = this.shadowRoot && this.shadowRoot.querySelector("ha-menu-button");
    if (mb) mb.narrow = n;
  }
  get narrow() { return this._narrow; }

  connectedCallback() {
    this._timer = setInterval(() => this._load(), 5000);
    this._tick = setInterval(() => this._autoStatus(), 1000);
  }
  disconnectedCallback() {
    clearInterval(this._timer);
    clearInterval(this._tick);
  }

  async _load() {
    if (!this._hass) return;
    try {
      this._data = await this._hass.callWS({ type: "sekurak/get" });
      const ids = this._data.entries.map((e) => e.entry_id);
      if (!ids.includes(this._entryId)) this._entryId = ids[0] || null;
      this._err = "";
    } catch (e) {
      this._err = e.message || String(e);
    }
    this._render();
  }

  get _entry() {
    return this._data && this._data.entries.find((e) => e.entry_id === this._entryId);
  }

  _build() {
    this._built = true;
    this.shadowRoot.innerHTML = `
      <style>
        :host { display:block; background: var(--primary-background-color); color: var(--primary-text-color); min-height:100vh; font-family: var(--paper-font-body1_-_font-family, Roboto, sans-serif); }
        .top { display:flex; align-items:center; gap:8px; height:56px; padding:0 12px; background: var(--app-header-background-color, var(--primary-color, #03a9f4)); color: var(--app-header-text-color, #fff); font-size:20px; }
        .wrap { max-width:1000px; margin:0 auto; padding:16px; }
        .card { background: var(--card-background-color, #fff); border-radius: var(--ha-card-border-radius, 12px); box-shadow: var(--ha-card-box-shadow, 0 1px 3px rgba(0,0,0,.2)); padding:16px; margin-bottom:16px; }
        h2 { margin:0 0 12px; font-size:18px; font-weight:500; }
        table { width:100%; border-collapse:collapse; }
        th, td { text-align:left; padding:8px 6px; border-bottom:1px solid var(--divider-color, #ddd); vertical-align:middle; }
        th { font-weight:500; color: var(--secondary-text-color); font-size:13px; }
        .ent { color: var(--secondary-text-color); font-size:12px; }
        .st { display:inline-block; padding:2px 8px; border-radius:10px; font-size:12px; background: var(--secondary-background-color, #eee); }
        .st.alarm { background: var(--error-color, #db4437); color:#fff; }
        .badge { display:inline-block; padding:4px 10px; border-radius:12px; font-size:14px; margin-bottom:12px; }
        .badge.ok { background: var(--success-color, #43a047); color:#fff; }
        .badge.warn { background: var(--warning-color, #ffa600); color:#000; }
        .badge.neutral { background: var(--secondary-background-color, #eee); }
        button { font: inherit; cursor:pointer; border:none; border-radius:8px; padding:8px 14px; background: var(--primary-color, #03a9f4); color: var(--text-primary-color, #fff); }
        button.flat { background:transparent; color: var(--primary-color, #03a9f4); padding:6px 8px; }
        button.del { background:transparent; color: var(--error-color, #db4437); padding:6px 8px; }
        label { display:block; font-size:13px; color: var(--secondary-text-color); margin:10px 0 4px; }
        input[type=text], input[type=number], input[type=password], select { width:100%; box-sizing:border-box; padding:10px; border-radius:8px; border:1px solid var(--divider-color, #ccc); background: var(--card-background-color, #fff); color: var(--primary-text-color); font: inherit; }
        .row { display:flex; gap:12px; flex-wrap:wrap; }
        .row > div { flex:1 1 220px; }
        .chk { display:flex; align-items:center; gap:8px; margin-top:12px; color: var(--primary-text-color); font-size:14px; }
        .actions { display:flex; gap:8px; margin-top:16px; justify-content:flex-end; }
        .err { color: var(--error-color, #db4437); margin:8px 0; }
        .hint { color: var(--secondary-text-color); font-size:13px; margin-top:8px; }
        .empty { color: var(--secondary-text-color); padding:12px 0; }
        .chips { display:flex; flex-wrap:wrap; gap:6px; margin-bottom:6px; }
        .chip { display:inline-flex; align-items:center; gap:4px; padding:4px 4px 4px 10px; border-radius:14px; background: var(--secondary-background-color, #eee); font-size:13px; }
        .chip button { background:transparent; color: var(--secondary-text-color); padding:0 6px; font-size:16px; line-height:1; }
        .addrow { display:flex; gap:8px; }
        .addrow input { flex:1; }
        @media (max-width: 600px) { .hide-m { display:none; } }
      </style>
      <div class="top"><ha-menu-button></ha-menu-button><span>Sekurak</span></div>
      <div class="wrap">
        <div id="err" class="err"></div>
        <div id="picker"></div>
        <div class="card" id="arm"></div>
        <div class="card" id="auto"></div>
        <div class="card" id="form" hidden></div>
        <div class="card">
          <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
            <h2 style="margin:0">Czujniki</h2>
            <button id="add">+ Dodaj czujnik</button>
          </div>
          <div id="list"></div>
        </div>
      </div>
      <datalist id="dl-trig"></datalist>
      <datalist id="dl-arm"></datalist>
      <datalist id="dl-act"></datalist>
      <datalist id="dl-pres"></datalist>`;
    const r = this.shadowRoot;
    r.getElementById("add").addEventListener("click", () => this._openForm(null));
  }

  _render() {
    const r = this.shadowRoot;
    if (!r || !this._built) return;
    r.getElementById("err").textContent = this._err;
    const d = this._data;
    if (!d) return;
    if (!d.entries.length) {
      r.getElementById("arm").innerHTML = `<div class="empty">Najpierw dodaj integrację Sekurak (Ustawienia → Urządzenia i usługi).</div>`;
      r.getElementById("list").innerHTML = "";
      r.getElementById("picker").innerHTML = "";
      return;
    }
    // wybór telefonu, gdy jest kilka konfiguracji
    const pk = r.getElementById("picker");
    if (d.entries.length > 1) {
      if (!pk.querySelector("select")) {
        pk.innerHTML = `<div class="card"><label>Telefon</label><select></select></div>`;
        pk.querySelector("select").addEventListener("change", (ev) => {
          this._entryId = ev.target.value;
          this._form = null;
          this._renderArm(true);
          this._renderAuto(true);
          this._render();
        });
      }
      const sel = pk.querySelector("select");
      sel.innerHTML = d.entries
        .map((e) => `<option value="${esc(e.entry_id)}" ${e.entry_id === this._entryId ? "selected" : ""}>${esc(e.title)}</option>`)
        .join("");
    } else pk.innerHTML = "";
    this._fillDatalists();
    this._renderArm(false);
    this._renderAuto(false);
    this._renderList();
  }

  _fillDatalists() {
    const d = this._data, h = this._hass;
    if (!d || !h) return;
    const opts = (domains) =>
      Object.keys(h.states)
        .filter((id) => domains.includes(id.split(".")[0]))
        .sort()
        .map((id) => `<option value="${esc(id)}">${esc(h.states[id].attributes.friendly_name || id)}</option>`)
        .join("");
    const key = Object.keys(h.states).length;
    if (this._dlKey === key) return;
    this._dlKey = key;
    this.shadowRoot.getElementById("dl-trig").innerHTML = opts(d.domeny);
    this.shadowRoot.getElementById("dl-arm").innerHTML = opts(d.domeny_czuwania);
    this.shadowRoot.getElementById("dl-act").innerHTML = opts(d.domeny_akcji || []);
    this.shadowRoot.getElementById("dl-pres").innerHTML = opts(d.domeny_obecnosci || []);
  }

  _renderArm(force) {
    const e = this._entry;
    const box = this.shadowRoot.getElementById("arm");
    if (!e) return;
    const [txt, cls] = STATUS[e.status] || [e.status, "neutral"];
    // formularz rysujemy raz (żeby odświeżanie nie kasowało wpisywanego tekstu), status zawsze
    if (force || box.dataset.entry !== e.entry_id) {
      box.dataset.entry = e.entry_id;
      box.innerHTML = `
        <h2>Czuwanie alarmu</h2>
        <div id="badge" class="badge"></div>
        <div class="row">
          <div><label>Encja czuwania (uzbrojony alarm)</label>
            <input type="text" id="arm-ent" list="dl-arm" placeholder="np. alarm_control_panel.ropam" value="${esc(e.czuwanie)}"></div>
          <div><label>Czas na wyjście (s)</label>
            <input type="number" id="arm-delay" min="0" max="600" value="${esc(e.czas_wyjscia)}"></div>
        </div>
        <div class="hint">Czujniki typu Alarm dzwonią tylko na uzbrojonym, po czasie na wyjście. Puste pole = dzwonią zawsze. Zalanie i reszta budzą zawsze.</div>
        <label>Przy włamaniu włącz (każdy czujnik typu Alarm)</label>
        ${this._chipsHtml("arm-act")}
        <div class="hint">Np. syrena, skrypt, scena, przełącznik. Po wyciszeniu na fonie lub wyłączeniu alarmu w HA syrena/przełączniki same się gaszą.</div>
        <div class="actions"><button id="arm-save">Zapisz czuwanie</button></div>`;
      box.querySelector("#arm-save").addEventListener("click", () => this._saveArm());
      this._bindChips(box, "arm-act", e.akcje_alarmu || []);
    }
    const b = box.querySelector("#badge");
    b.className = `badge ${cls}`;
    b.textContent = txt + (e.aktywne && e.aktywne.length ? ` · teraz dzwoni: ${e.aktywne.map((t) => this._data.typy[t] || t).join(", ")}` : "");
  }

  _renderAuto(force) {
    const e = this._entry;
    const box = this.shadowRoot.getElementById("auto");
    if (!e) return;
    if (force || box.dataset.entry !== e.entry_id) {
      box.dataset.entry = e.entry_id;
      box.innerHTML = `
        <h2>Auto-uzbrojenie</h2>
        <label class="chk"><input type="checkbox" id="au-on" ${e.auto_uzbrojenie ? "checked" : ""}> Uzbrój alarm, gdy wszystkie wybrane telefony wyjdą z domu</label>
        <div id="au-warn" class="err"></div>
        <label>Telefony / osoby</label>
        ${this._chipsHtml("au-tel", "dl-pres", "np. person.misiek albo device_tracker.fon")}
        <div class="row">
          <div><label>Opóźnienie po wyjściu ostatniego (s)</label>
            <input type="number" id="au-delay" min="0" max="3600" value="${esc(e.auto_opoznienie)}"></div>
          <div><label>Kod centrali (tylko jeśli wymaga)</label>
            <input type="password" id="au-kod" autocomplete="new-password" placeholder="${e.kod_ustawiony ? "ustawiony – puste = bez zmian" : "brak"}"></div>
        </div>
        <div id="au-status" class="hint"></div>
        <div class="hint">Rozbrajasz ręcznie – powrót do domu nie rozbraja (kradziony fon nie otworzy chaty). Uzbraja tylko w chwili, gdy wyjdzie ostatni.</div>
        <div class="actions">${e.kod_ustawiony ? `<button class="flat" id="au-kod-del">Usuń kod</button>` : ""}<button id="au-save">Zapisz auto-uzbrojenie</button></div>`;
      this._bindChips(box, "au-tel", e.telefony || []);
      box.querySelector("#au-save").addEventListener("click", () => this._saveAuto(false));
      const kd = box.querySelector("#au-kod-del");
      if (kd) kd.addEventListener("click", () => this._saveAuto(true));
    }
    box.querySelector("#au-warn").textContent = !e.czuwanie
      ? "Najpierw ustaw encję czuwania wyżej – to ją Sekurak uzbraja."
      : !e.da_sie_uzbroic
      ? `Encji ${e.czuwanie} nie da się uzbroić z HA (działa tylko centrala alarmu, input_boolean albo switch).`
      : "";
    this._autoStatus();
  }

  _autoStatus() {
    const e = this._entry;
    const el = this.shadowRoot.getElementById("au-status");
    if (!e || !el || !this._hass) return;
    const who = (e.telefony || []).map((id) => {
      const st = this._hass.states[id];
      const nm = (st && st.attributes.friendly_name) || id;
      const s = st ? st.state : "brak";
      return `${nm}: ${s === "home" ? "w domu" : s === "not_home" ? "poza domem" : s}`;
    });
    let t = who.length ? who.join(" · ") : "Brak wybranych telefonów.";
    if (e.auto_at) {
      const left = Math.max(0, Math.round(e.auto_at - Date.now() / 1000));
      t += ` — uzbrajam za ${left} s`;
    }
    el.textContent = t;
  }

  async _saveAuto(delCode) {
    const box = this.shadowRoot.getElementById("auto");
    const kod = box.querySelector("#au-kod").value;
    try {
      await this._hass.callWS({
        type: "sekurak/settings",
        entry_id: this._entryId,
        auto_uzbrojenie: box.querySelector("#au-on").checked,
        telefony: this._chipVals(box, "au-tel"),
        auto_opoznienie: parseInt(box.querySelector("#au-delay").value || "0", 10) || 0,
        kod: delCode ? "" : kod ? kod : null,
      });
      this._err = "";
      setTimeout(async () => { await this._load(); this._renderAuto(true); this._render(); }, 600);
    } catch (err) {
      this._err = err.message || String(err);
      this._render();
    }
  }

  _stateCell(s) {
    const st = this._hass && this._hass.states[s.entity_id];
    const state = st ? st.state : "brak encji";
    const alarm = st ? this._isAlarm(state, s.invert) : false;
    return `<span class="st ${alarm ? "alarm" : ""}">${esc(state)}</span>`;
  }

  _isAlarm(state, invert) {
    return invert ? ["off", "closed", "disarmed"].includes(state) : ["triggered", "on", "open"].includes(state);
  }

  _renderList() {
    const e = this._entry;
    const box = this.shadowRoot.getElementById("list");
    if (!e) return;
    if (!e.czujniki.length) {
      box.innerHTML = `<div class="empty">Brak czujników. Kliknij „+ Dodaj czujnik”.</div>`;
      return;
    }
    box.innerHTML = `<table>
      <tr><th>Nazwa</th><th>Typ</th><th class="hide-m">Odwrócony</th><th>Stan</th><th></th></tr>
      ${e.czujniki
        .map(
          (s) => `<tr>
          <td>${esc(s.name)}<div class="ent">${esc(s.entity_id)}</div>${(s.akcje || []).length ? `<div class="ent">włącza: ${esc(s.akcje.join(", "))}</div>` : ""}</td>
          <td>${esc(this._data.typy[s.typ] || s.typ)}</td>
          <td class="hide-m">${s.invert ? "tak" : "nie"}</td>
          <td data-state="${esc(s.id)}">${this._stateCell(s)}</td>
          <td style="white-space:nowrap;text-align:right">
            <button class="flat" data-edit="${esc(s.id)}">Edytuj</button>
            <button class="del" data-del="${esc(s.id)}">Usuń</button>
          </td></tr>`
        )
        .join("")}
    </table>`;
    box.querySelectorAll("[data-edit]").forEach((b) =>
      b.addEventListener("click", () => this._openForm(e.czujniki.find((s) => s.id === b.dataset.edit)))
    );
    box.querySelectorAll("[data-del]").forEach((b) =>
      b.addEventListener("click", () => this._delete(e.czujniki.find((s) => s.id === b.dataset.del)))
    );
  }

  _liveStates() {
    const e = this._entry;
    if (!e) return;
    this._fillDatalists();
    this._autoStatus();
    for (const s of e.czujniki) {
      const td = this.shadowRoot.querySelector(`[data-state="${CSS.escape(s.id)}"]`);
      if (td) td.innerHTML = this._stateCell(s);
    }
  }

  _chipsHtml(id, list = "dl-act", ph = "np. siren.syrena albo script.wlamanie") {
    return `<div id="${id}"><div class="chips"></div>
      <div class="addrow"><input type="text" list="${list}" placeholder="${esc(ph)}"><button class="flat" type="button">Dodaj</button></div>
      <div class="err" data-err></div></div>`;
  }

  _bindChips(root, id, vals) {
    const box = root.querySelector("#" + id);
    box._vals = [...vals];
    const inp = box.querySelector("input"), err = box.querySelector("[data-err]");
    const draw = () => {
      box.querySelector(".chips").innerHTML = box._vals
        .map((v, i) => {
          const st = this._hass.states[v];
          const nm = st && st.attributes.friendly_name ? `${st.attributes.friendly_name} (${v})` : v;
          return `<span class="chip">${esc(nm)}<button type="button" data-rm="${i}">×</button></span>`;
        })
        .join("") || `<span class="ent">(nic)</span>`;
      box.querySelectorAll("[data-rm]").forEach((b) =>
        b.addEventListener("click", () => { box._vals.splice(+b.dataset.rm, 1); draw(); })
      );
    };
    const add = () => {
      const v = inp.value.trim();
      if (!v) return;
      if (!this._hass.states[v]) { err.textContent = "Nie ma takiej encji – wybierz z listy."; return; }
      err.textContent = "";
      if (!box._vals.includes(v)) box._vals.push(v);
      inp.value = "";
      draw();
    };
    box.querySelector(".addrow button").addEventListener("click", add);
    inp.addEventListener("change", add);
    inp.addEventListener("keydown", (ev) => { if (ev.key === "Enter") add(); });
    draw();
  }

  _chipVals(root, id) {
    const box = root.querySelector("#" + id);
    const inp = box.querySelector("input");
    const v = inp.value.trim();
    if (v && this._hass.states[v] && !box._vals.includes(v)) box._vals.push(v);
    return box._vals;
  }

  _openForm(s) {
    const d = this._data;
    if (!d || !this._entry) return;
    this._form = s ? { ...s } : { id: null, entity_id: "", name: "", typ: "alarm", invert: false, akcje: [] };
    const f = this._form;
    const box = this.shadowRoot.getElementById("form");
    box.hidden = false;
    box.innerHTML = `
      <h2>${s ? "Edytuj czujnik" : "Dodaj czujnik"}</h2>
      <div class="row">
        <div><label>Encja</label><input type="text" id="f-ent" list="dl-trig" placeholder="zacznij pisać, np. binary_sensor.drzwi" value="${esc(f.entity_id)}"></div>
        <div><label>Nazwa (pokaże się na telefonie)</label><input type="text" id="f-name" placeholder="np. Drzwi wejściowe" value="${esc(f.name)}"></div>
      </div>
      <div class="row">
        <div><label>Co ma odpalić</label><select id="f-typ">${Object.entries(d.typy)
          .map(([k, v]) => `<option value="${esc(k)}" ${k === f.typ ? "selected" : ""}>${esc(v)}</option>`)
          .join("")}</select></div>
      </div>
      <label class="chk"><input type="checkbox" id="f-inv" ${f.invert ? "checked" : ""}> Odwrócony sygnał (alarm przy off / zamknięciu)</label>
      <label>Ten czujnik dodatkowo włącza (opcjonalnie)</label>
      ${this._chipsHtml("f-act")}
      <div class="hint">Własne encje tego czujnika, odpalane razem z alarmem. Przy typie Alarm dochodzą do wspólnej listy z czuwania.</div>
      <div id="f-err" class="err"></div>
      <div class="actions"><button class="flat" id="f-cancel">Anuluj</button><button id="f-save">Zapisz</button></div>`;
    const ent = box.querySelector("#f-ent"), name = box.querySelector("#f-name");
    ent.addEventListener("change", () => {
      const st = this._hass.states[ent.value.trim()];
      if (st && !name.value.trim()) name.value = st.attributes.friendly_name || "";
    });
    box.querySelector("#f-cancel").addEventListener("click", () => this._closeForm());
    box.querySelector("#f-save").addEventListener("click", () => this._saveForm());
    this._bindChips(box, "f-act", f.akcje || []);
    box.scrollIntoView({ behavior: "smooth", block: "start" });
    ent.focus();
  }

  _closeForm() {
    this._form = null;
    const box = this.shadowRoot.getElementById("form");
    box.hidden = true;
    box.innerHTML = "";
  }

  async _saveForm() {
    const box = this.shadowRoot.getElementById("form");
    const ent = box.querySelector("#f-ent").value.trim();
    const errEl = box.querySelector("#f-err");
    if (!this._hass.states[ent]) {
      errEl.textContent = "Nie ma takiej encji – wybierz z listy.";
      return;
    }
    try {
      await this._hass.callWS({
        type: "sekurak/sensor/save",
        entry_id: this._entryId,
        sensor_id: this._form.id || null,
        entity_id: ent,
        name: box.querySelector("#f-name").value,
        typ: box.querySelector("#f-typ").value,
        invert: box.querySelector("#f-inv").checked,
        akcje: this._chipVals(box, "f-act"),
      });
      this._closeForm();
      setTimeout(() => this._load(), 600);
    } catch (e) {
      errEl.textContent = e.message || String(e);
    }
  }

  async _delete(s) {
    if (!s || !confirm(`Usunąć czujnik „${s.name}”?`)) return;
    try {
      await this._hass.callWS({ type: "sekurak/sensor/delete", entry_id: this._entryId, sensor_id: s.id });
      setTimeout(() => this._load(), 600);
    } catch (e) {
      this._err = e.message || String(e);
      this._render();
    }
  }

  async _saveArm() {
    const box = this.shadowRoot.getElementById("arm");
    const ent = box.querySelector("#arm-ent").value.trim();
    if (ent && !this._hass.states[ent]) {
      this._err = "Nie ma takiej encji czuwania – wybierz z listy albo zostaw puste.";
      this._render();
      return;
    }
    try {
      await this._hass.callWS({
        type: "sekurak/settings",
        entry_id: this._entryId,
        czuwanie: ent,
        czas_wyjscia: parseInt(box.querySelector("#arm-delay").value || "0", 10) || 0,
        akcje_alarmu: this._chipVals(box, "arm-act"),
      });
      this._err = "";
      setTimeout(async () => { await this._load(); this._renderArm(true); this._render(); }, 600);
    } catch (e) {
      this._err = e.message || String(e);
      this._render();
    }
  }
}

if (!customElements.get("sekurak-panel")) customElements.define("sekurak-panel", SekurakPanel);
