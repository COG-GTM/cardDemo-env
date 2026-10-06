# Live parity console — COBOL `XFRDAILY` vs Spring Boot (Java 21)

Runs the **real** legacy transfer-fee chain on GnuCOBOL and a running Spring Boot
engine side by side, feeds both the same transaction stream from the same starting
state, and shows — transaction by transaction — whether Java matches COBOL.

```sh
make up && make build        # the estate, as usual
make parity-console          # db + java-live + console; open http://localhost:8090
make parity-console-check    # headless: 7 fixtures + generated stream, then break-Java on half_cent
make parity-console-down
```

On the Devin VM put `/usr/bin` first (`PATH=/usr/bin:$PATH make ...`, `~/emsdk/docker` shadows
docker). If Maven Central rate-limits the image build (HTTP 429), set a mirror:
`MAVEN_MIRROR_URL=https://maven-central.storage-download.googleapis.com/maven2/ make parity-console`.
Ports (bound to 127.0.0.1 — the APIs are unauthenticated): `PARITY_CONSOLE_PORT` (8090),
`JAVA_LIVE_PORT` (8091).

## What runs

```
                       same DALYTRAN record (fixture replay or generated stream)
                     ┌───────────────────────────┴───────────────────────────┐
  console/cobol_driver.py                                     console/java_client.py
  1-record DALYTRAN.PS → tools/runjcl Runner                  POST /api/engine/transactions
  jcl/XFRDAILY.jcl → PROC XFERFEEP                            java-live (Spring Boot 3, Java 21)
   STEP010 CBXFR01C → STEP020 XFERFEE (embedded SQL)          TransferFeeEngine → JdbcTransferFeeEngine
   → STEP030 CBXFR03C   (compiled loadlib/*.so, cobcrun)       BigDecimal only, schema java_engine
  reads XFER.FEES(+1), XFER_FEE_LEDGER row, ACCTDATA.XFER(+1)  returns rule, fee, cap flag, balances, ledger row
  ACCTDATA.XFER(+1) becomes the next ACCTDATA.PS
                     └───────────────────────────┬───────────────────────────┘
                      console/verdict.py: field-by-field MATCH / DIFF → SSE → browser
```

| Piece | Where |
|---|---|
| Spring Boot engine (Maven, Java 21) | `java-live/` — `TransferFeeEngine` is the seam to swap in the COG-1235…1239 services |
| COBOL micro-batch driver | `console/cobol_driver.py` — executes the compiled chain, never reimplements it |
| Feeder / run loop | `console/session.py` (`ParitySession`), streams in `console/streams.py` |
| Comparison | `console/verdict.py` (per transaction), `console/candidate.py` (end of stream, `tools/parity/compare.py`) |
| Web UI + API | `console/server.py` (stdlib HTTP + Server-Sent Events), `console/static/` |
| Compose overlay | `compose.yaml` — `live-db-init`, `java-live`, `parity-console` |

**Isolation.** Everything runs against a separate `carddemo_live` database
(`db/init-live-db.sh` applies the estate's own `db2/ddl` + `db2/data` seed): the COBOL
writes `public.XFER_FEE_LEDGER` there, Java owns schema `java_engine`. The estate's
`carddemo` database and `fixtures/` are never written. COBOL datasets live under
`work/parity-console/cobol/`.

**Same starting state.** For each run the driver copies the stream's `ACCTDATA.PS` /
`CARDXREF.PS` into a fresh dataset root and truncates the live ledger; the Java engine is
reset with the same accounts and xref decoded from those files, and the same
`CTL_XFER_PARM` rows the COBOL reads.

## Streams

* The seven recorded fixtures (`default`, `under_cap`, `at_cap`, `rate_change`,
  `zero_amount`, `non_transfer`, `half_cent`) are replayed from
  `fixtures/xferfee/<case>/input/DALYTRAN.PS`.
* `generated` builds a longer DALYTRAN with `tools/fixtures/gen_fixtures.py`'s record
  builders over the fixture accounts: a seeded random mix (count 5–500 and seed selectable,
  reproducible) drawn from transfers in both books, both sides of the June 15 RETAIL rate
  change, RETAIL and INSTL cap hits, even half-cent ties, zero amounts and non-transfers.
  Coverage of every scenario is likely for the default 60 / seed 1250 but not guaranteed for
  an arbitrary count and seed — the fixtures are the deterministic cases.
* Pace: as fast as COBOL runs, 0.4 s, 0.9 s or 2 s per transaction.

## Reading the screen

* One row per transaction; each cell shows **C** (COBOL) on top and **J** (Java) below:
  book, rule used (EFF_DT · pct · cap), fee, cap flag, source/target balance after,
  ledger row. Cells that differ are outlined red and the verdict lists the fields.
* Non-transfers show as **IGNORED BY BOTH** (CBXFR01C did not select them, Java skipped them).
* Click a row for every compared field (incl. cycle credit/debit and each ledger column),
  the COBOL step return codes, SYSOUT and recon report, and the raw Java result.
* Totals: transactions, transfers, fees per side, MATCH, DIFF.
* At the end of a stream the console also checks:
  1. end-of-stream state — every account and ledger row, COBOL vs Java;
  2. for fixture streams, Java's output packaged in the recorder layout and judged by
     `tools/parity/compare.py --candidate` against the recorded COBOL baseline (the same
     check `make parity` uses), plus the live COBOL micro-batches against that baseline;
  3. if Java ran with HALF_EVEN, `make parity-naive`'s reference for the same case, and
     whether Java's differences are exactly the same rows and fields.
* **Break Java** switches the engine to `RoundingMode.HALF_EVEN` (`PUT /api/engine/rounding`)
  — `half_cent` then shows a fee DIFF on all six rows, identical to
  `make parity-naive CASE=half_cent`. Toggle back and re-run: all MATCH.

## Java engine API

| Method | Path | |
|---|---|---|
| GET | `/api/engine/info` | engine class, Java version, rounding |
| POST | `/api/engine/reset` | `{accounts, xref, rules}` seed |
| POST | `/api/engine/transactions` | one DALYTRAN record → `TransferResult` (POSTED / IGNORED / SKIPPED / REJECTED) |
| GET | `/api/engine/state` | rules, accounts, ledger |
| GET/PUT | `/api/engine/rounding` | `{"mode": "HALF_UP" \| "HALF_EVEN"}` |

`cd java-live && mvn test` runs the unit tests (HALF_UP vs HALF_EVEN on the half_cent
amounts, cap after rounding, equal-to-cap not flagged, zero amount, `TRAN-DESC(14:11)`).

## Findings surfaced while building this

* **Zoned-decimal sign encoding.** `gen_fixtures.zoned()` writes the sign as an
  EBCDIC-style overpunch (`{`, `A`–`I`), but GnuCOBOL on ASCII reads a trailing `A`–`I` as
  digit 0 — an amount of 32.79 reaches XFERFEE as 32.70. Every committed fixture amount
  ends in 0 cents, so recorded baselines are unaffected; the generated stream keeps amounts
  in whole dimes for the same reason. A Java port that reads these files must reproduce
  (or the estate must fix) this before real data flows.
* **Negative balances.** GnuCOBOL writes negative display numerics with an ASCII sign
  (`p`–`y` = −0…−9); `tools/parity/copybook.py` decodes those as digit 0. The console
  normalizes the sign byte before decoding (`common.ascii_sign_to_overpunch`); fixtures
  never go negative, so `make parity` is unaffected.
* Micro-batching means a transaction-only-non-transfer batch ends STEP030 with RC 4
  ("no fee records"); the console folds per-transaction RCs back to the batch equivalent
  before comparing with the recorded `rc.json`.
