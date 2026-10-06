# XFRDAILY cut-over runbook

Phase 4 of the xferfee → Spring Boot migration: switch transfer-fee posting off
the mainframe chain and onto the Java services, without losing the ability to
switch back. The rollback procedure is in [ROLLBACK.md](ROLLBACK.md).

Chain being replaced (system of record until step C6):

```
XFRDAILY ─ PROC XFERFEEP
  STEP010 CBXFR01C  DALYTRAN.PS + CARDXREF.PS + ACCTDATA.PS → XFER.EXTRACT(+1)
  STEP020 XFERFEE   XFER.EXTRACT(0) + CTL_XFER_PARM → ACCTDATA.XFER(+1), XFER.FEES(+1), XFER_FEE_LEDGER
  STEP030 CBXFR03C  XFER.FEES(0) → XFER.RECON.RPT(+1)
```

## 1. Preconditions

Do not start cut-over until all of these are true. Each has an owning ticket.

| # | Precondition | Evidence | Ticket |
|---|---|---|---|
| P1 | Full event-mode parity: `make parity-java` reports 0 diffs for **all** cases (7 original + X1–X6), CI `parity-java` job required | PR summary + green CI run | COG-1251 (C1) |
| P2 | Shadow-run diff job runs nightly and writes `work/shadow/<date>/report.{md,json}` | 20 consecutive reports (see §2) | COG-1242 (P8) |
| P3 | legacy-adapter egress writes a valid `ACCTDATA.XFER(+1)` generation every day (byte round-trip tests green) | adapter JUnit + daily generation in the catalog | COG-1240 |
| P4 | Decision register signed (batch-atomic vs per-transfer, RC 8 handling, duplicate `TRAN_ID`, RC 4 on empty day) | signed register | COG-1249 |
| P5 | COBOL baseline still green on GnuCOBOL: `make up && make build && make parity` | `work/parity/report.md` | — |
| P6 | Rollback dry run passes on the Compose stack: `make cutover-rollback-dryrun` → `ROLLBACK DRY RUN: PASS` | `work/cutover/rollback-dryrun.log` attached to the change record | this ticket |

## 2. Shadow-run exit criterion

Cut-over may be scheduled only when the P8 shadow-run job has produced
**20 consecutive clean business days**.

A day is *clean* when **all** of the following hold for that day's report:

1. `compare.py` reports **0 diffs** on every `case.json` key: `XFER.EXTRACT`,
   `ACCTDATA.XFER`, `XFER.FEES`, `XFER.RECON.RPT`, `CTL_XFER_PARM`, `XFER_FEE_LEDGER`.
2. Both sides ended with the same return code (0, or 4 on an empty day).
3. The day's input was the real production `DALYTRAN.PS` (synthetic CI days do not count).
4. `ops/cutover/ledger_recon.sql` against that day's `ACCTDATA.XFER` generation
   returns `RECON_BREAKS=0`.

Rules:

- Any non-clean day resets the counter to 0. A day with no report (job did not run) also resets it.
- The 20 days must include one month-end and one `CTL_XFER_PARM` rate change
  (or a replayed rate-change day, cf. fixture `rate_change`), so the cap/rate paths are exercised.
- Diffs that the signed decision register (COG-1249) explicitly allows do not
  count as diffs only in per-transfer mode, and only when the report lists the decision ID.

20 business days ≈ one full statement cycle plus margin; Change Advisory can raise
(not lower) N at sign-off.

## 3. Sign-off

All five sign-offs are recorded on the change record before step C1.

| Role | Signs off on |
|---|---|
| Migration lead (engineering) | P1, P3, P5, P6 evidence; rollback dry-run log |
| Finance / ledger owner | 20-day shadow report pack; ledger reconciliation results; fee totals per book |
| Mainframe operations (batch scheduling) | XFRDAILY hold/release procedure; GDG retention (LIMIT 5) covers the rollback window |
| Change Advisory Board | Change window, rollback window length, go/no-go call |
| Product owner (card servicing) | Decision register (P4) and customer-visible behaviour |

## 4. Cut-over steps

Run after the last shadow day's XFRDAILY has completed (MAXCC ≤ 4) and before the next business day's transactions arrive.

| Step | Action | Verify | Owner |
|---|---|---|---|
| C1 | Go/no-go call. Confirm §2 counter ≥ 20 and all §3 sign-offs. | Change record approved | CAB |
| C2 | Record cut-over point: last legacy `ACCTDATA.XFER(0)` generation number, and `SELECT LOCALTIMESTAMP` from Db2/Postgres as `CUTOVER_TS`. | Values written on the change record | Ops |
| C3 | Hold XFRDAILY in the scheduler (job HELD, not deleted). Leave XFREXTR / XFERFEE / XFRRECON members and the GDG bases in place. | Scheduler shows HELD; no XFRDAILY on the next night's plan | Ops |
| C4 | Enable Java posting for the next business day: intake reads `DALYTRAN.PS`, account-posting-service posts, legacy-adapter egress writes `ACCTDATA.XFER(+1)` and mirrors ledger rows to `XFER_FEE_LEDGER`. | First Java day ends with `ACCTDATA.XFER` generation = C2 + 1 | Migration lead |
| C5 | Day-1 check: run `ops/cutover/ledger_recon.sql` with `since_ts = CUTOVER_TS`, base = C2 generation, gen = `ACCTDATA.XFER(0)`. | `RECON_BREAKS=0`; recon report totals match the Java reconciliation-service totals per book | Finance |
| C6 | Rollback window: keep XFRDAILY HELD (not removed) for **10 business days**, running C5 daily (base = previous generation). Any break → [ROLLBACK.md](ROLLBACK.md). | 10 daily recon logs with 0 breaks | Finance + Ops |
| C7 | Close window: retire the chain members listed in §5, keep GDG bases until the last legacy generation ages out (LIMIT 5). | Members moved to retired library; change closed | Ops |

Rollback is possible during C4–C6. After C7 a fix-forward is required.

## 5. JCL members retired with the chain vs kept

Generated with `make cutover-retire` (wraps `make deadcode`; SMF as of 2026-09-15).
A member is part of the chain if it executes a chain program/PROC
(`CBXFR01C`, `XFERFEE`, `CBXFR03C`, `XFERFEEP`) or references a chain GDG
(`XFER.EXTRACT`, `ACCTDATA.XFER`, `XFER.FEES`, `XFER.RECON.RPT`).

| Job | SMF verdict | Chain link | Cut-over action |
|---|---|---|---|
| DEFGDGX | RETIRE | ACCTDATA.XFER, XFER.EXTRACT, XFER.FEES, XFER.RECON.RPT | RETIRE (already idle per SMF) |
| XFERFEE | MIGRATE | XFERFEE, ACCTDATA.XFER, XFER.EXTRACT, XFER.FEES | RETIRE WITH CHAIN (hold until rollback window closes) |
| XFRDAILY | MIGRATE | XFERFEEP | RETIRE WITH CHAIN (hold until rollback window closes) |
| XFREXTR | MIGRATE | CBXFR01C, XFER.EXTRACT | RETIRE WITH CHAIN (hold until rollback window closes) |
| XFRPURGE | RETIRE | XFER.FEES | RETIRE (already idle per SMF) |
| XFRRECON | MIGRATE | CBXFR03C, XFER.FEES, XFER.RECON.RPT | RETIRE WITH CHAIN (hold until rollback window closes) |

**Retired with the chain (6):** DEFGDGX, XFERFEE, XFRDAILY, XFREXTR, XFRPURGE, XFRRECON;
plus PROC `XFERFEEP`, programs `CBXFR01C`, `XFERFEE`, `CBXFR03C`, and the rollback job
`ops/cutover/XFRRBACK.jcl` once the window closes. `DEFGDGX` and `XFRPURGE` were already idle (`RETIRE`
in `make deadcode`); the GDG bases they manage stay cataloged until C7.

**Kept (46), not part of the chain, `make deadcode` verdict unchanged:** ACCTFILE, CARDFILE, CBADMCDJ,
CBEXPORT, CBIMPORT, CBPAUP0J, CLOSEFIL, COMBTRAN, CREADB2, CREASTMT, CUSTFILE, DALYREJS,
DBPAUTP0, DEFCUST, DEFGDGB, DEFGDGD, DISCGRP, DUSRSECJ, ESDSRRDS, FTPJCLS, INTCALC, INTRDRJ1,
INTRDRJ2, LOADPADB, MNTTRDB2, OPENFIL, POSTTRAN, PRTCATBL, READACCT, READCARD, READCUST,
READXREF, REPTFILE, TCATBALF, TRANBKP, TRANCATG, TRANEXTR, TRANFILE, TRANIDX, TRANREPT,
TRANTYPE, TXT2PDF1, UNLDGSAM, UNLDPADB, WAITSTEP, XREFFILE.

Shared inputs `DALYTRAN.PS`, `CARDXREF.PS`, `ACCTDATA.PS` and table `CTL_XFER_PARM` are **kept**:
other kept jobs (e.g. POSTTRAN, ACCTFILE, XREFFILE) and the Java services still read them.
