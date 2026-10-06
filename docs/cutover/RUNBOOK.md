# xferfee cut-over runbook (Phase 4, COG-1252)

Moves the system of record for daily transfer fees from the COBOL chain
`XFRDAILY → CBXFR01C → XFERFEE → CBXFR03C` to the Spring Boot services under `java/`, and
retires the chain. Rollback is in [ROLLBACK.md](ROLLBACK.md).

Depends on: COG-1234 (Java workspace, `make parity-java`), COG-1242 / P8 (shadow-run diff
job, `work/shadow/<date>/report.json`), COG-1251 / C1 (event-mode convergence), COG-1240
(legacy-adapter egress of `ACCTDATA.XFER(+1)` / `XFER.FEES(+1)`). Open questions are in the
decision register (COG-1249).

## 1. Roles and sign-off

| Role | Owns | Signs |
|---|---|---|
| Migration lead | This runbook, `make cutover-gate`, go/no-go call | G1, G2, G3, T0 go |
| Batch operations (Control-M) | Holding / releasing `XFRDAILY` and folder `DAILY-TransferFeePosting` | T0 step 3, rollback R4 |
| DBA (Db2 `CARDDEMO`) | `XFER_FEE_LEDGER`, `CTL_XFER_PARM` freeze, `ledger_recon.sql` runs | T0 step 5, rollback R2/R6 |
| Finance / GL controller | Fee totals and the reconciliation report readers | G1 sample review, T+1 sign-off, window close |
| xferfee product owner | Business acceptance; any behaviour change needs a COG-1249 decision | T0 go |
| Change advisory board | Change ticket for T0 and for the rollback window close | T0 go |

Named people per role are a decision for COG-1249 (see PR). Nobody signs their own gate:
the migration lead runs the gate, Finance and the product owner accept it.

## 2. Go / no-go gates

All three gates are evaluated by one command on the release candidate commit:

```sh
make cutover-gate CUTOVER_DAYS=20 CUTOVER_ARGS="--as-of <last completed business date>"
# GO = exit 0, writes work/cutover/readiness.{md,json}
```

`make cutover-gate` and `make cutover-test` run in a `maven:3.9-eclipse-temurin-21` container,
so they do not depend on the estate image having a JDK.

The gate is `java/cutover` (`CutoverGateApplication`). It reads only artefacts the other
tickets already produce, so it can be re-run by anyone.

### G1 — shadow-run exit criterion (P8 / COG-1242)

Met when **all** of the following hold over `work/shadow/<date>/report.json`:

1. The **N consecutive scheduled business days ending at `--as-of`** (default: the previous
   scheduled day; default `N = 20`) each have a shadow
   report with `status = PASS`. `XFRDAILY` is scheduled `DAYS="ALL"` in Control-M, so the
   default calendar is every calendar day (`--calendar WEEKDAYS` if the business agrees
   otherwise).
2. `PASS` means `shadow_run.py` exit 0: zero differences across datasets, `db2_after`, SYSOUT
   and `rc.json`, and zero mismatches in its fees / balances / ledger / report-totals
   sections.
3. A day with `FAIL`, `ERROR`, an unreadable report or **no report at all resets the count**,
   including the `--as-of` day itself, so a shadow job that stopped reporting cannot leave G1
   green on old history.
   An "explained" diff is still a diff: it is fixed in Java (or decided in COG-1249 and
   re-recorded by the owning ticket) and the count restarts.
4. The latest N-day window (not any older part of a longer streak) spans at least one
   fee-rate change: an `EFF_DT` in that day's frozen
   `CTL_XFER_PARM` snapshot that supersedes an earlier rule for the same book (BR-06/07). If no
   real rate change falls in the window, schedule one with Finance or replay a day with
   `shadow_run.py --rules <snapshot with a change>`.
5. The window includes the rejection paths that exist in production traffic (unmatched card
   RC 4, missing rule / account RC 8) whenever they occurred; their RCs are part of the diff.

### G2 — final parity replay (all cases)

On the exact commit to be deployed:

```sh
make up && make build
make cutover-final-replay      # make parity-java CASE=<each case>, then make parity
```

Every recorded case (`default`, `under_cap`, `at_cap`, `rate_change`, `zero_amount`,
`non_transfer`, `half_cent`, plus any fixture added by COG-1243..1248 — pass them with
`CUTOVER_CASES=`) must be `PASS` on both COBOL self-parity and the Java candidate. Expected
outputs are never edited to make Java match.

### G3 — rollback rehearsal

```sh
make cutover-rollback-dryrun   # work/cutover/rollback/{rollback.log,rollback.json}
```

Met only when `rollback.json` is `PASS` with a non-empty list of checks that all passed, was
rehearsed from a Java / legacy-adapter generation (`source_kind` `java`, or `explicit` for an
operator-supplied `--source`; the COBOL fixture stand-in does not count), finished within
`--rehearsal-max-age-days` (default 7 calendar days ≈ the runbook's 5 business days), and ran on
the release commit (`make cutover-gate` passes `--release-commit $(git rev-parse HEAD)`).
`--allow-fixture-rehearsal` exists for development only. The committed rehearsal log is
[evidence/rollback-dryrun.log](evidence/rollback-dryrun.log).

## 3. Cut-over steps

Business day `D` is the first day Java posts. Times are Control-M local; the chain normally
starts at 22:00.

| When | Step | Who |
|---|---|---|
| T-5 | G3 rehearsal on the release build; attach `rollback.log` to the change ticket | Migration lead |
| T-1 | Freeze `CTL_XFER_PARM` changes until T+1; confirm no rule takes effect on `D` unless rehearsed | DBA, Finance |
| T-1 | `make cutover-gate` → GO; go/no-go call with every role in §1 | Migration lead |
| T0-1 | `XFRDAILY` for `D-1` ended `MAXCC=0000`; record `ACCTDATA.XFER(0)` generation number, ledger row count and fee total (baseline for R2) | Batch ops, DBA |
| T0-2 | **Hold** (do not delete) `XFRDAILY` and folder `DAILY-TransferFeePosting` (`XFREXTR`, `XFERFEE`, `XFRRECON`) | Batch ops |
| T0-3 | Enable the Java services for `D` (batch trigger or event mode per COG-1251); legacy-adapter egress **on**: `ACCTDATA.XFER(+1)`, `XFER.FEES(+1)`, `XFER.RECON.RPT(+1)` in the legacy layout, ledger rows written to `XFER_FEE_LEDGER` with the same `TRAN_ID` key | Migration lead |
| T0-4 | Java run for `D` completes; RC per step recorded (0 / 4 / 8 semantics unchanged) | Migration lead |
| T0-5 | `ops/cutover/ledger_recon.sql` with `acct_before = ACCTDATA.XFER(-1)`, `acct_after = ACCTDATA.XFER(0)`, `fees = XFER.FEES(0)`: every row `ok = t` | DBA |
| T+1 | Finance compares `XFER.RECON.RPT(0)` grand total with the ledger fee total for `D`; sign-off | Finance |
| T+1..T+W | Daily T0-5 reconciliation. Any non-`t` row or any Java RC > 4 that the legacy chain would not have produced = rollback trigger | DBA, Migration lead |
| T+W | Rollback window closes (default `W = 10` business days); change ticket to delete the members in §4 | Migration lead, CAB |

The legacy report layout (`XFER.RECON.RPT`, 133-byte FBA) stays in production until every
reader has moved; reconciliation-service writes it through the legacy-adapter.

### Rollback triggers

- Any `ledger_recon.sql` check not `ok` after a Java day.
- Java RC differs from what the legacy chain would produce for the same input (e.g. RC 8 on a
  day the COBOL shadow would end RC 0).
- Finance cannot reconcile the report total with the ledger.
- Java run not complete by the legacy batch deadline.

Rollback is per business day (the legacy chain is all-or-nothing per run, BR-16). Follow
[ROLLBACK.md](ROLLBACK.md).

## 4. JCL members retired with the chain vs kept

Generated with `make cutover-deadcode` (`tools/cutover/retire_with_chain.py`, which runs the
`make deadcode` split and selects chain members by program, proc and GDG):

| Member | Last run | Days idle | Programs | `make deadcode` | Cut-over action | Why |
|---|---|---:|---|---|---|---|
| XFERFEE | 2024-08-08 | 768 | XFERFEE | MIGRATE | RETIRE WITH CHAIN (see Decisions needed) | Standalone XFERFEE posting job between XFREXTR and XFRRECON in the same Control-M folder; replaced by fee-policy + account-posting-service. Not in the ticket's list. |
| XFRDAILY | 2026-01-31 | 227 | XFERFEEP | MIGRATE | RETIRE WITH CHAIN (hold at T0, delete at window close) | Daily chain job; replaced by the Java services. Scheduler-held, not deleted, until the rollback window closes: it is the rollback path. |
| XFREXTR | 2026-07-27 | 50 | CBXFR01C | MIGRATE | RETIRE WITH CHAIN | Standalone CBXFR01C extract (Control-M DAILY-TransferFeePosting); replaced by transfer-intake-service. Not needed for rollback (XFRDAILY runs STEP010 itself). |
| XFRRECON | 2026-02-26 | 201 | CBXFR03C | MIGRATE | RETIRE WITH CHAIN | Standalone CBXFR03C report; replaced by reconciliation-service. Not needed for rollback (XFRDAILY runs STEP030 itself). |
| DEFGDGX | 2019-04-14 | 2711 | IDCAMS | RETIRE | KEEP until window close | Defines the chain GDG bases. Idle per SMF, but ACCTDATA.XFER/XFER.FEES must stay defined: the legacy-adapter keeps cataloguing (+1) and rollback reads (0). |
| XFRPURGE | 2019-07-19 | 2615 | IEFBR14 | RETIRE | RETIRE (already dead, independent of cut-over) | IEFBR14 purge of XFER.FEES(-1)/(-2); idle since 2019, GDG LIMIT rolls generations off. Not used by rollback. |
| `jcl/proc/XFERFEEP.prc` | — | — | — | not scanned | KEEP until window close | Procedure executed by XFRDAILY; procs are not in the dead-code inventory. |
| `ops/cutover/XFRRBACK.jcl` | — | — | — | not scanned | KEEP (new, rollback only) | Restores ACCTDATA.PS from ACCTDATA.XFER(0). Lives outside jcl/ so the dead-code split and chain graph are unchanged. |

- Retired with the chain: `XFRDAILY`, `XFREXTR`, `XFRRECON` (ticket scope) and `XFERFEE`.
- Kept through the rollback window: `XFRDAILY` (held), `XFERFEEP`, `DEFGDGX`, `XFRRBACK`.
- The other 46 inventory members are unaffected (24 RETIRE / 22 MIGRATE per `make deadcode`;
  totals `RETIRE 26 (50.0%) / MIGRATE 26 (50.0%)`).
- COBOL sources and copybooks (`CBXFR01C`, `XFERFEE`, `CBXFR03C`, `CVXFR0*Y`) and the
  recorded fixtures stay in the repo after window close: they are the parity oracle.
