# `python/xferfee` — Python port of the `xferfee` batch chain

Port of job `XFRDAILY` / PROC `XFERFEEP` (COBOL `CBXFR01C` → `XFERFEE` → `CBXFR03C`).
Every rule is traced to `docs/specs/XFERFEE-BUSINESS-SPEC.md` (BR-1 … BR-18); the
BR numbers are cited in the module docstrings and inline comments.

Outputs are byte-compatible with the COBOL estate: fixed-length records, zoned
display numerics, COMP-3 packed decimals, the same GDG names under `datasets/`,
the same SYSOUT lines and step return codes — so `tools/parity/compare.py` runs
unchanged against the Python candidate tree.

## Run

```sh
make up                       # compose: Postgres 15 + estate (python3-psycopg2 is in the image)
make run-python CASE=default  # run one fixture case + comparator → work/parity/default/python-report.md
make parity-python            # all 7 cases
```

Direct: `python3 python/xferfee/run_chain.py --case <case> --db-reset --fresh
--datasets <dir> --joblog-dir <dir> --candidate <dir>`. Without `--case` the
runner uses whatever `AWS.M2.CARDDEMO.{DALYTRAN,CARDXREF,ACCTDATA}.PS` are in
the datasets directory (same as `tools/runjcl/runjcl.py`).

DB connection: `OCDB_NAME/OCDB_USER/OCDB_PASS` + `PGHOST/PGPORT` (falls back to
`PGDATABASE/PGUSER/PGPASSWORD`), i.e. the variables `docker-compose.yml` already
exports to the estate container.

## JCL step → Python module

| JCL (`XFERFEEP`) | PGM | Module | Business rules |
|---|---|---|---|
| `STEP010` | `CBXFR01C` | `extract.py` | BR-1 … BR-5 |
| `STEP020` | `XFERFEE` | `post_fees.py` | BR-6 … BR-14 |
| `STEP030` `COND=(4,LT,STEP020)` | `CBXFR03C` | `reconcile.py` | BR-15 … BR-17 |
| job `XFRDAILY` (`DEFGDGX` GDGs, JES messages, MAXCC) | — | `run_chain.py` | BR-18 |
| numeric storage (zoned / COMP-3 / `ROUNDED` / edited pictures) | — | `cobol_numeric.py` | BR-8, BR-16 |
| copybook parsing → offsets/lengths | — | `layouts.py` | data contracts §2 |

`run_chain.py` reproduces the runner semantics: `(+1)` allocates the next
generation `GnnnnV00` and catalogs it after the step; `(0)` reads the current
generation from the `.gdg` metadata; the chain stops after any non-zero RC;
`STEP030` is skipped when `4 < RC(STEP020)`. It writes the comparator candidate
tree (`datasets/`, `db2_after/*.csv`, `sysout/STEPnnn.txt`, `rc.json`).

## DD name → file / dataset

| Step | DD | Dataset | Layout | Python argument |
|---|---|---|---|---|
| STEP010 | `DALYTRAN` | `AWS.M2.CARDDEMO.DALYTRAN.PS` (350) | `CVTRA05Y` | `extract.run(dalytran=…)` |
| STEP010 | `XREFFILE` | `AWS.M2.CARDDEMO.CARDXREF.PS` (50) | `CVACT03Y` | `xreffile` |
| STEP010 | `ACCTFILE` | `AWS.M2.CARDDEMO.ACCTDATA.PS` (300) | `CVACT01Y` | `acctfile` |
| STEP010 | `XFEREXTR` | `AWS.M2.CARDDEMO.XFER.EXTRACT(+1)` (120) | `CVXFR01Y` | `xferextr` |
| STEP020 | `XFEREXTR` | `AWS.M2.CARDDEMO.XFER.EXTRACT(0)` | `CVXFR01Y` | `post_fees.run(xferextr=…)` |
| STEP020 | `ACCTFILE` | `AWS.M2.CARDDEMO.ACCTDATA.PS` | `CVACT01Y` | `acctfile` |
| STEP020 | `ACCTOUT` | `AWS.M2.CARDDEMO.ACCTDATA.XFER(+1)` (300) | `CVACT01Y` | `acctout` |
| STEP020 | `XFERFEE` | `AWS.M2.CARDDEMO.XFER.FEES(+1)` (100) | `CVXFR02Y` | `xferfee` |
| STEP030 | `XFERFEE` | `AWS.M2.CARDDEMO.XFER.FEES(0)` | `CVXFR02Y` | `reconcile.run(xferfee=…)` |
| STEP030 | `XFERRPT` | `AWS.M2.CARDDEMO.XFER.RECON.RPT(+1)` (133, LINE SEQUENTIAL) | report lines | `xferrpt` |
| all | `SYSOUT` | `work/…/joblog/STEPnnn.txt` | text | `StepResult.sysout` |

## Copybook field → record field

Layouts are parsed from `copybook/*.cpy` by `layouts.py` into `Layout`/`Field`
(`name, offset, length, kind ∈ {text, display, comp-3}, scale, signed`), so the
Python field names **are** the copybook names; access is through
`Record.text/number/raw(name)`. Mapping of the fields the logic touches:

| Copybook | Field | PIC / USAGE | Python access | Used by |
|---|---|---|---|---|
| CVTRA05Y | `TRAN-TYPE-CD` | X(02) | `rec.raw("TRAN-TYPE-CD")` | BR-1 |
| CVTRA05Y | `TRAN-CARD-NUM` | X(16) | `raw` | BR-2 |
| CVTRA05Y | `TRAN-AMT` | S9(09)V99 | `raw` (copied byte-for-byte) | BR-4 |
| CVTRA05Y | `TRAN-DESC` | X(100) → `(14:11)` | `raw("TRAN-DESC")[13:24]` | BR-4 |
| CVTRA05Y | `TRAN-ORIG-TS` | X(26) → `(1:10)` | `raw(...)[:10]` | BR-4 |
| CVACT03Y | `XREF-CARD-NUM`, `XREF-ACCT-ID` | X(16), 9(11) | `raw` | BR-2 |
| CVACT01Y | `ACCT-ID` | 9(11) | `number("ACCT-ID")` | BR-3, BR-11 |
| CVACT01Y | `ACCT-GROUP-ID` | X(10) | `raw` → `XFR-BOOK-ID` | BR-3 |
| CVACT01Y | `ACCT-CURR-BAL` | S9(10)V99 | `number/set_number` | BR-12 |
| CVACT01Y | `ACCT-CURR-CYC-CREDIT` | S9(10)V99 | `number/set_number` | BR-12 |
| CVACT01Y | `ACCT-CURR-CYC-DEBIT` | S9(10)V99 | `number/set_number` | BR-12 |
| CVXFR01Y | `XFR-TRAN-ID/DT/SRC-ACCT-ID/TGT-ACCT-ID/BOOK-ID/TRAN-AMT` | X(16) X(10) 9(11) 9(11) X(10) S9(09)V99 | `Record(CVXFR01Y)` | BR-4, BR-7 … BR-13 |
| CVXFR02Y | `XFE-TRAN-AMT`, `XFE-FEE-AMT` | S9(09)V99 COMP-3 | `set_number` → `packed_encode` | BR-13, BR-15 |
| CVXFR02Y | `XFE-FEE-PCT` | S9V9(6) COMP-3 | `set_number` | BR-13 |
| CVXFR02Y | `XFE-CAP-APPLIED` | X(01) `Y`/`N` | `set_text` | BR-9 |
| CVXFR02Y | `XFE-RULE-EFF-DT` | X(10) | `set_text` | BR-13 |
| CVXFR02Y | `XFE-BOOK-ID` | X(10) | `text` | BR-15 subtotal break |

Working-storage numerics: `WS-FEE-AMT PIC S9(09)V99` ← `rounded_cents()`
(`Decimal.quantize(ROUND_HALF_UP)`, BR-8); `WS-TOT-FEE` / report totals ←
`display_signed()` and `edit_z8_9_99_minus()` (BR-14, BR-16).

## SQL → query

| COBOL (`XFERFEE.cbl`) | Python (`post_fees.py`) |
|---|---|
| `SELECT FEE_PCT, FEE_CAP, EFF_DT INTO :WS-FEE-PCT, :WS-FEE-CAP, :WS-RULE-EFF-DT FROM CTL_XFER_PARM WHERE BOOK_ID = :WS-XFR-BOOK-ID AND EFF_DT <= CAST(:WS-XFR-TRAN-DT AS DATE) AND EXP_DT > CAST(:WS-XFR-TRAN-DT AS DATE)` | `SQL_SELECT_RULE` with `%(book_id)s`, `%(tran_dt)s`; `fetchall()` — 0 rows → `SQLCODE +100` path (RC 8), >1 rows → `-811` path (RC 8) (BR-7) |
| `INSERT INTO XFER_FEE_LEDGER (TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, BOOK_ID, TRAN_AMT, FEE_AMT, CAP_APPLIED) VALUES (…)` | `SQL_INSERT_LEDGER`, one execute per posted transfer (BR-13) |
| `EXEC SQL COMMIT` at end of job | `conn.commit()` once after the master rewrite; `rollback()` on abend (BR-14) |
| `SELECT … FOR UPDATE` | not present in COBOL — none |

`run_chain.py --db-reset` truncates `XFER_FEE_LEDGER` (same as the JCL runner)
and dumps `CTL_XFER_PARM` / `XFER_FEE_LEDGER` to `db2_after/*.csv` in the
recorder's format after the run.

## Verification

`make parity-python` runs the 7 recorded cases (`default`, `under_cap`, `at_cap`,
`rate_change`, `zero_amount`, `non_transfer`, `half_cent`) and compares
datasets, table dumps, SYSOUT and RCs with `tools/parity/compare.py`.

All recorded cases end MAXCC=0, so `make test-python` (`test_failure_paths.py`)
derives failing inputs from the `default` fixture to cover the RC 4 paths
(unmatched card BR-5, empty fee file BR-17), the RC 8 abends (no fee rule BR-7,
unknown account BR-11, rule-lookup DB error) with ledger rollback, GDG catalog
state, `rc.json` and candidate output preservation, and the `S9(09)V99`
accumulator truncation (`fit_picture`).
