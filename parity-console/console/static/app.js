"use strict";

const $ = (id) => document.getElementById(id);
const state = { rows: new Map(), total: 0, running: false, open: null };

function esc(value) {
  return String(value ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}

function get(side, key) {
  let value = side;
  for (const part of key.split(".")) value = value == null ? undefined : value[part];
  return value == null ? null : String(value);
}

function pair(row, keys, render, opts = {}) {
  const diffs = new Set(row.verdict.diffs);
  const isDiff = keys.some((k) => diffs.has(k));
  const c = render(row.cobol), j = render(row.java);
  return `<div class="pair${isDiff ? " diff" : ""}${opts.cls ? " " + opts.cls : ""}"
    title="${isDiff ? "COBOL and Java differ" : ""}"><span class="c">${c}</span><span class="j">${j}</span></div>`;
}

const fmt = {
  book: (s) => esc(s.book ?? "—"),
  rule: (s) => s.rule ? `${esc(s.rule.effDt)} · ${esc(trimPct(s.rule.pct))} · cap ${esc(s.rule.cap)}` : "—",
  fee: (s) => esc(s.fee ?? "—"),
  cap: (s) => esc(s.capApplied ?? "—"),
  src: (s) => s.source ? `#${s.source.id} <b>${esc(s.source.bal)}</b>` : "—",
  tgt: (s) => s.target ? `#${s.target.id} <b>${esc(s.target.bal)}</b>` : "—",
  ledger: (s) => s.ledger ? `${esc(s.ledger.TRAN_DT)} ${esc(s.ledger.SRC_ACCT_ID)}→${esc(s.ledger.TGT_ACCT_ID)} ${esc(s.ledger.FEE_AMT)} ${esc(s.ledger.CAP_APPLIED)}` : outcomeText(s),
};

function trimPct(p) {
  if (p == null) return "";
  return (parseFloat(p) * 100).toFixed(3).replace(/0+$/, "").replace(/\.$/, "") + "%";
}

function outcomeText(s) {
  if (!s.outcome) return "—";
  return s.outcome === "POSTED" ? "—" : `<span class="small">${esc(s.outcome)}</span>`;
}

function verdictCell(row) {
  if (row.verdict.ignored && row.verdict.match) {
    return `<span class="verdict ignored">IGNORED BY BOTH</span>`;
  }
  if (row.verdict.match) return `<span class="verdict match">MATCH</span>`;
  const labels = row.verdict.fields.filter((f) => !f.match).map((f) => f.label);
  return `<span class="verdict diff">DIFF</span><div class="difflist">${esc(labels.join(", "))}</div>`;
}

function rowHtml(row) {
  const t = row.tran;
  const typeCell = `${esc(t.typeCd)} <span class="small">${esc(t.typeLabel)}</span>`;
  if (row.verdict.ignored && row.verdict.match) {
    return `<td>${row.seq}</td><td><b>${esc(t.tranId)}</b><div class="small">${esc(t.origTs.slice(0, 10))}</div></td>
      <td>${typeCell}</td><td class="num">${esc(t.amount)}</td>
      <td colspan="7" class="ignored-cell">Not a transfer (TRAN-TYPE-CD ${esc(t.typeCd)}): COBOL CBXFR01C did not select it, Java skipped it — ignored by both, no fee, no balance change, no ledger row.</td>
      <td>${verdictCell(row)}</td>`;
  }
  return `<td>${row.seq}</td>
    <td><b>${esc(t.tranId)}</b><div class="small">${esc(t.origTs.slice(0, 10))}</div></td>
    <td>${typeCell}</td>
    <td class="num">${esc(t.amount)}</td>
    <td>${pair(row, ["outcome", "book"], fmt.book)}</td>
    <td>${pair(row, ["rule.effDt", "rule.pct", "rule.cap", "businessDate"], fmt.rule)}</td>
    <td class="num">${pair(row, ["fee", "amount"], fmt.fee)}</td>
    <td>${pair(row, ["capApplied"], fmt.cap)}</td>
    <td class="num">${pair(row, ["source.id", "source.bal", "source.cycDebit"], fmt.src)}</td>
    <td class="num">${pair(row, ["target.id", "target.bal", "target.cycCredit"], fmt.tgt)}</td>
    <td>${pair(row, row.verdict.fields.filter((f) => f.key.startsWith("ledger.")).map((f) => f.key), fmt.ledger)}</td>
    <td>${verdictCell(row)}</td>`;
}

function detailHtml(row) {
  const fields = row.verdict.fields.map((f) => `<tr class="${f.match ? "" : "bad"}"><td>${esc(f.label)}</td>
      <td>${esc(f.cobol ?? "—")}</td><td>${esc(f.java ?? "—")}</td><td>${f.match ? "=" : "≠"}</td></tr>`).join("");
  const c = row.cobol, j = row.java;
  const steps = Object.entries(c.steps || {}).map(([k, v]) => `${k} RC=${v}`).join("  ");
  const sysout = Object.entries(c.sysout || {}).map(([k, v]) => `${k}:\n${v}`).join("\n");
  return `<td colspan="12"><div class="detail-grid">
    <div><table><thead><tr><th>Field</th><th>COBOL</th><th>Java</th><th></th></tr></thead><tbody>${fields}</tbody></table></div>
    <div><div class="small"><span class="sys cobol">COBOL</span> ${esc(c.outcome)} — ${esc(c.reason)} — ${c.durationMs} ms</div>
      <div class="small">XFRDAILY ${esc(steps)} MAXCC=${esc(c.maxcc)}</div>
      <pre>${esc(sysout)}${c.recon ? "\n\nRECON.RPT:\n" + esc(c.recon) : ""}</pre></div>
    <div><div class="small"><span class="sys java">JAVA</span> ${esc(j.outcome)} — ${esc(j.reason)} — ${j.durationMs} ms</div>
      <div class="small">rounding ${esc(j.roundingMode)}</div>
      <pre>${esc(JSON.stringify({ rule: j.rule, fee: j.fee, capApplied: j.capApplied, source: j.source, target: j.target, ledger: j.ledger }, null, 2))}</pre></div>
  </div></td>`;
}

function upsertRow(row, pending) {
  let tr = state.rows.get(row.seq);
  if (!tr) {
    tr = document.createElement("tr");
    tr.dataset.seq = row.seq;
    tr.addEventListener("click", () => toggleDetail(row.seq));
    $("rows").prepend(tr);
    state.rows.set(row.seq, tr);
  }
  if (pending) {
    const t = row.tran;
    tr.className = "new";
    tr.innerHTML = `<td>${row.seq}</td><td><b>${esc(t.tranId)}</b></td><td>${esc(t.typeCd)} <span class="small">${esc(t.typeLabel)}</span></td>
      <td class="num">${esc(t.amount)}</td><td colspan="7" class="small">Running XFRDAILY on GnuCOBOL and POSTing to the Java engine…</td>
      <td><span class="verdict pending">RUNNING</span></td>`;
    return;
  }
  tr._row = row;
  tr.className = "new " + (row.verdict.ignored && row.verdict.match ? "rowignored" : row.verdict.match ? "rowmatch" : "rowdiff");
  tr.innerHTML = rowHtml(row);
  $("empty").classList.add("hidden");
}

function toggleDetail(seq) {
  const existing = document.querySelector("tr.detail");
  if (existing) existing.remove();
  if (state.open === seq) { state.open = null; return; }
  const tr = state.rows.get(seq);
  if (!tr || !tr._row) return;
  const detail = document.createElement("tr");
  detail.className = "detail";
  detail.innerHTML = detailHtml(tr._row);
  tr.after(detail);
  state.open = seq;
}

function setTotals(t) {
  $("tTx").textContent = t.transactions;
  $("tTxOf").textContent = `of ${t.total} in stream`;
  $("tXfer").textContent = t.transfers;
  $("tIgnored").textContent = `${t.ignored} non-transfer ignored by both`;
  $("tFeeC").textContent = t.cobolFees;
  $("tFeeJ").textContent = t.javaFees;
  $("tMatch").textContent = t.matches;
  $("tDiff").textContent = t.diffs;
  $("tFeeDiff").textContent = t.feeDiffs ? `${t.feeDiffs} on fee` : "";
  if (state.running) {
    banner(t.diffs ? "runfail" : "running", t.diffs ? `RUNNING — ${t.diffs} DIFF` : `RUNNING — ${t.matches}/${t.total} MATCH`);
  }
}

function banner(cls, text) {
  const b = $("verdictBanner");
  b.className = "banner " + cls;
  b.textContent = text;
}

function setRounding(mode) {
  const broken = mode === "HALF_EVEN";
  $("breakJava").checked = broken;
  $("roundingMode").textContent = mode;
  $("roundingMode").className = "pill" + (broken ? " broken" : "");
}

function resetView() {
  state.rows.clear();
  state.open = null;
  $("rows").innerHTML = "";
  $("final").classList.add("hidden");
  $("empty").classList.remove("hidden");
  setTotals({ transactions: 0, total: 0, transfers: 0, ignored: 0, cobolFees: "0.00", javaFees: "0.00", matches: 0, diffs: 0, feeDiffs: 0 });
}

function setRunning(running) {
  state.running = running;
  $("run").disabled = running;
  $("stop").disabled = !running;
  $("stream").disabled = running;
}

function finalHtml(s) {
  const t = s.totals, st = s.state, b = s.baseline || {};
  const pass = s.verdict === "PARITY";
  const items = [];
  items.push(`<li>Per transaction: <span class="${t.diffs ? "badt" : "good"}">${t.matches} MATCH / ${t.diffs} DIFF</span> across ${t.transactions} transactions (${t.transfers} transfers, ${t.ignored} ignored by both). Total fees COBOL ${t.cobolFees} vs Java ${t.javaFees}.</li>`);
  const accDiff = st.accountDiffs.length, ledDiff = st.ledgerDiffs.length;
  items.push(`<li>End-of-stream state: ${st.accounts} accounts and ${st.ledgerRows} COBOL / ${st.javaLedgerRows} Java ledger rows — <span class="${st.equal ? "good" : "badt"}">${st.equal ? "identical" : `${accDiff} account and ${ledDiff} ledger field differences`}</span>.</li>`);
  if (b.cobolLive) items.push(`<li>COBOL live micro-batches vs recorded batch baseline (<code>tools/parity/compare.py</code>): <span class="${b.cobolLive.pass ? "good" : "badt"}">${esc(b.cobolLive.verdict)}</span></li>`);
  if (b.java) items.push(`<li>Java output vs recorded COBOL baseline (<code>compare.py --candidate</code>): <span class="${b.java.pass ? "good" : "badt"}">${esc(b.java.verdict)}</span>${b.java.diffRows.length ? `<pre>${esc(b.java.diffRows.join("\n"))}</pre>` : ""}</li>`);
  if (b.naive) items.push(`<li><code>make parity-naive CASE=${esc(s.stream)}</code> (HALF_EVEN reference): ${esc(b.naive.verdict)} — Java's differences are <span class="${b.javaMatchesNaive ? "good" : "badt"}">${b.javaMatchesNaive ? "exactly the same rows/fields" : "not the same"}</span>.</li>`);
  if (s.roundings.length) items.push(`<li>Java rounding used during the run: ${esc(s.roundings.join(", "))}</li>`);
  return `<h3 class="${pass ? "good" : "badt"}">${pass ? "PARITY — Java matches COBOL on every transaction" : s.stopped ? "Run stopped" : "NO PARITY — Java differs from COBOL"}</h3><ul>${items.join("")}</ul>`;
}

function connect() {
  const es = new EventSource("/api/events");
  es.addEventListener("phase", (e) => {
    const d = JSON.parse(e.data);
    if (d.reset) resetView();
    $("phase").textContent = d.text;
  });
  es.addEventListener("start", (e) => {
    const d = JSON.parse(e.data);
    setRunning(true);
    state.total = d.total;
    $("streamDesc").innerHTML = `<b>${esc(d.stream)}</b> — ${esc(d.description)} · ${d.total} transactions · same 8 accounts, card xref and CTL_XFER_PARM loaded into both systems`;
    $("phase").textContent = "";
    $("stream").value = d.stream;
    setRounding(d.rounding);
    setTotals({ transactions: 0, total: d.total, transfers: 0, ignored: 0, cobolFees: "0.00", javaFees: "0.00", matches: 0, diffs: 0, feeDiffs: 0 });
  });
  es.addEventListener("pending", (e) => upsertRow(JSON.parse(e.data), true));
  es.addEventListener("row", (e) => {
    const row = JSON.parse(e.data);
    upsertRow(row, false);
    setTotals(row.totals);
  });
  es.addEventListener("mode", (e) => setRounding(JSON.parse(e.data).rounding));
  es.addEventListener("done", (e) => {
    const s = JSON.parse(e.data);
    setRunning(false);
    setTotals(s.totals);
    $("phase").textContent = "";
    const pass = s.verdict === "PARITY";
    banner(pass ? "pass" : "fail", pass ? `PARITY ✓ ${s.totals.matches}/${s.totals.transactions} MATCH` : s.stopped ? "STOPPED" : `NO PARITY ✗ ${s.totals.diffs} DIFF`);
    const f = $("final");
    f.className = "final " + (pass ? "pass" : "fail");
    f.innerHTML = finalHtml(s);
  });
  es.addEventListener("error", (e) => {
    if (!e.data) return;
    setRunning(false);
    banner("fail", "ERROR");
    $("phase").textContent = JSON.parse(e.data).text;
  });
}

async function post(path, body) {
  const response = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body || {}) });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || response.statusText);
  return data;
}

async function init() {
  const config = await (await fetch("/api/config")).json();
  const select = $("stream");
  for (const s of config.streams) {
    const option = document.createElement("option");
    option.value = s.id;
    option.textContent = s.recorded ? `${s.id} (fixture)` : `${s.id} (longer stream)`;
    option.title = s.description;
    select.append(option);
  }
  const syncCount = () => {
    $("countWrap").classList.toggle("hidden", select.value !== config.generated);
    const s = config.streams.find((x) => x.id === select.value);
    if (!state.running) $("streamDesc").textContent = s ? s.description : "";
  };
  select.addEventListener("change", syncCount);
  syncCount();
  if (config.java && config.java.rounding) {
    setRounding(config.java.rounding);
    $("javaInfo").textContent = `(${config.java.engine}, Java ${config.java.java})`;
  }
  $("run").addEventListener("click", async () => {
    try {
      await post("/api/run", { stream: select.value, paceMs: Number($("pace").value), count: Number($("count").value) });
    } catch (err) { alert(err.message); }
  });
  $("stop").addEventListener("click", () => post("/api/stop"));
  $("breakJava").addEventListener("change", async (e) => {
    const data = await post("/api/break-java", { on: e.target.checked });
    setRounding(data.rounding);
  });
  connect();
}

init();
