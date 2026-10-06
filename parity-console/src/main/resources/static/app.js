const $ = (id) => document.getElementById(id);
const body = document.querySelector('#feed tbody');
let source = null;

const FIELDS = ['fee', 'cap', 'rule', 'srcBalanceAfter', 'tgtBalanceAfter'];

async function loadCases() {
  const cases = await (await fetch('api/cases')).json();
  const select = $('case');
  const featured = document.createElement('optgroup');
  featured.label = 'COG-1250 demo cases';
  const rest = document.createElement('optgroup');
  rest.label = 'Other parity fixtures';
  for (const c of cases) {
    const opt = new Option(`${c.name} (${c.feedSize} txns)`, c.name);
    (c.featured ? featured : rest).appendChild(opt);
  }
  select.append(featured, rest);
  const wanted = new URLSearchParams(location.search).get('case');
  if (wanted) select.value = wanted;
}

function text(v) { return v === null || v === undefined ? '—' : v; }

function el(tag, cls, content) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (content !== undefined) node.textContent = content;
  return node;
}

function setTotals(t) {
  $('tCount').textContent = t.count;
  $('tMatch').textContent = t.match;
  $('tDiff').textContent = t.diff;
  $('tCobol').textContent = t.cobolFees;
  $('tJava').textContent = t.javaFees;
  $('tDelta').textContent = Number(t.feeDelta) === 0 ? 'Δ 0.00' : `Δ ${t.feeDelta} vs COBOL`;
}

function resetView() {
  body.innerHTML = '';
  setTotals({ count: 0, match: 0, diff: 0, cobolFees: '0.00', javaFees: '0.00', feeDelta: '0.00' });
  const v = $('tVerdict');
  v.className = 'tile verdict';
  v.querySelector('.value').textContent = '…';
  $('empty').style.display = 'none';
}

function pendingRow(p) {
  const tr = el('tr', 'pending');
  tr.id = `pending-${p.seq}`;
  tr.appendChild(el('td', '', p.seq));
  const id = el('td', 'left');
  id.append(el('span', 'tranid', p.tranId), el('span', 'desc', p.description));
  tr.appendChild(id);
  tr.appendChild(el('td', '', p.amount));
  const wait = el('td', 'left', 'running XFRDAILY on GnuCOBOL…');
  wait.colSpan = 12;
  tr.appendChild(wait);
  body.appendChild(tr);
  tr.scrollIntoView({ block: 'nearest' });
}

function txnRow(r) {
  const pending = $(`pending-${r.seq}`);
  const fields = Object.fromEntries(r.fields.map((f) => [f.name, f]));
  const posted = fields.selected && (fields.selected.cobol === 'posted' || fields.selected.java === 'posted');
  const tr = el('tr', 'txn new');
  tr.dataset.verdict = r.verdict;
  tr.dataset.tranId = r.tranId;
  tr.appendChild(el('td', '', r.seq));
  const id = el('td', 'left');
  id.append(el('span', 'tranid', r.tranId),
    el('span', 'desc', posted ? `${r.book} · acct ${r.srcAcctId ?? '?'} → ${r.tgtAcctId ?? '?'}` : (r.skipReason || r.description)));
  tr.appendChild(id);
  tr.appendChild(el('td', '', r.amount));
  if (!posted) {
    const skip = el('td', 'left', `skipped by CBXFR01C on both sides (${r.skipReason || 'not selected'})`);
    skip.colSpan = 11;
    tr.appendChild(skip);
  } else {
    for (const name of FIELDS) {
      const f = fields[name] || {};
      tr.appendChild(el('td', `cobol ${f.match === false ? 'diff' : ''}`, text(f.cobol)));
      tr.appendChild(el('td', `java ${f.match === false ? 'diff' : ''}`, text(f.java)));
    }
    const ledger = fields.ledger || {};
    const cell = el('td', `ledger ${ledger.match === false ? 'diff' : ''}`, ledger.match === false ? 'rows differ ▸' : 'identical');
    cell.title = `COBOL: ${ledger.cobol}\nJava : ${ledger.java}`;
    tr.appendChild(cell);
  }
  const verdict = el('td');
  const badge = el('span', `badge ${posted ? r.verdict : (r.verdict === 'MATCH' ? 'SKIP' : 'DIFF')}`,
    posted ? r.verdict : (r.verdict === 'MATCH' ? 'MATCH·skip' : 'DIFF'));
  verdict.appendChild(badge);
  tr.appendChild(verdict);

  const detail = el('tr', 'detail');
  detail.style.display = 'none';
  const td = el('td');
  td.colSpan = 15;
  const grid = el('div', 'detail-grid');
  const block = (title, content) => {
    const d = el('div');
    d.append(el('h4', '', title), el('pre', '', content));
    return d;
  };
  grid.append(
    block('XFER_FEE_LEDGER row · COBOL (Postgres, via embedded SQL)', text(fields.ledger && fields.ledger.cobol)),
    block('XFER_FEE_LEDGER row · Java', text(fields.ledger && fields.ledger.java)),
    block(`GnuCOBOL SYSOUT · RC ${JSON.stringify(r.cobolRc)} · ${r.cobolMs} ms`,
      Object.entries(r.cobolSysout || {}).map(([k, v]) => `${k}\n  ${v.join('\n  ')}`).join('\n') + (r.cobolError ? `\n\nERROR\n${r.cobolError}` : '')),
    block(`Java field comparison · ${r.javaMicros} µs`,
      r.fields.map((f) => `${f.match ? '  ' : '✗ '}${f.name.padEnd(16)} COBOL=${text(f.cobol)}  Java=${text(f.java)}`).join('\n')),
  );
  td.appendChild(grid);
  detail.appendChild(td);
  tr.addEventListener('click', () => { detail.style.display = detail.style.display === 'none' ? '' : 'none'; });

  if (pending) {
    pending.replaceWith(tr);
  } else {
    body.appendChild(tr);
  }
  tr.after(detail);
  setTotals(r.totals);
}

function finish(t, error) {
  const v = $('tVerdict');
  if (error) {
    v.className = 'tile verdict fail';
    v.querySelector('.value').textContent = 'ERROR';
    $('status').textContent = error;
    $('status').className = 'status error';
  } else {
    const pass = t.diff === 0;
    v.className = `tile verdict ${pass ? 'pass' : 'fail'}`;
    v.querySelector('.value').textContent = pass ? 'PARITY' : `${t.diff} DIFF`;
    $('status').textContent = `done · ${t.count} transactions`;
    $('status').className = 'status';
  }
  $('run').disabled = false;
  if (source) source.close();
  source = null;
}

function run() {
  if (source) source.close();
  resetView();
  const breakIt = $('breakIt').checked;
  const params = new URLSearchParams({ case: $('case').value, breakIt, delayMs: $('delay').value });
  $('run').disabled = true;
  $('status').textContent = 'starting GnuCOBOL feed…';
  $('status').className = 'status live';
  $('javaRounding').textContent = breakIt ? 'HALF_EVEN (break it)' : 'HALF_UP (COBOL ROUNDED)';
  let last = { count: 0, match: 0, diff: 0 };
  source = new EventSource(`api/run?${params}`);
  source.addEventListener('start', (e) => {
    const s = JSON.parse(e.data);
    $('cobolEngine').textContent = s.cobolEngine || 'GnuCOBOL';
    $('tFeed').textContent = `of ${s.feedSize} in feed`;
    $('status').textContent = `streaming ${s.case}`;
  });
  source.addEventListener('pending', (e) => pendingRow(JSON.parse(e.data)));
  source.addEventListener('txn', (e) => { const r = JSON.parse(e.data); last = r.totals; txnRow(r); });
  source.addEventListener('done', (e) => finish(JSON.parse(e.data)));
  source.addEventListener('failed', (e) => finish(last, JSON.parse(e.data).message));
  source.onerror = () => { if (source) finish(last, 'stream closed'); };
}

$('run').addEventListener('click', run);
loadCases();
