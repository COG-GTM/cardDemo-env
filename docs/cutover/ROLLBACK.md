# xferfee rollback: re-enable XFRDAILY (COG-1252)

Use during the rollback window after cut-over ([RUNBOOK.md](RUNBOOK.md) §3) when a rollback
trigger fires. Rollback is at a business-day boundary: the last Java day is either fully
kept (its `ACCTDATA.XFER` generation becomes the legacy master) or, if it did not complete,
the generation before it is used and that day is re-run by `XFRDAILY`.

Rehearsed end to end on the compose stack by `make cutover-rollback-dryrun`
(`tools/cutover/rollback_dryrun.py`); latest log: [evidence/rollback-dryrun.log](evidence/rollback-dryrun.log).

## Why it is safe

- The legacy account master is never updated in place (BR-16); `XFRDAILY` reads
  `ACCTDATA.PS` and writes a full new `ACCTDATA.XFER(+1)`. During the window the
  legacy-adapter keeps writing that same GDG in the CVACT01Y layout, so the last generation
  it catalogued is a valid legacy master.
- `XFER_FEE_LEDGER.TRAN_ID` is the primary key. Java rows are written to the same table, so a
  transfer already posted by Java cannot be posted again by `XFRDAILY`; XFERFEE would abend
  the run (BR-14) rather than double-post. `ledger_recon.sql` check `incoming_already_in_ledger`
  catches this before the run instead of at abend time.

## Steps

| # | Step | Command / artefact | Who |
|---|---|---|---|
| R1 | Stop the Java services from posting further days; legacy-adapter egress for the last completed day must be catalogued | `ACCTDATA.XFER(0)`, `XFER.FEES(0)` | Migration lead |
| R2 | Pre-flight: generation integrity (LRECL 300, decodes, unique `ACCT-ID`, same account set as the master frozen at T0) and the ledger reconciliation | `ops/cutover/ledger_recon.sql` with `acct_before = ACCTDATA.XFER(-1)` (or the T0 master for the first Java day), `acct_after = ACCTDATA.XFER(0)`, `fees = XFER.FEES(0)`, `incoming = type-08 TRAN_IDs of the next DALYTRAN` | DBA |
| R3 | Restore the legacy master from the last adapter generation | `ops/cutover/XFRRBACK.jcl` (IDCAMS `REPRO ACCTDATA.XFER(0) → ACCTDATA.PS`); verify byte-identical | Batch ops |
| R4 | Release `XFRDAILY` in Control-M for the next business day (keep `DAILY-TransferFeePosting` held) | Control-M | Batch ops |
| R5 | `XFRDAILY` runs; expect the usual RCs (0, or 4 for unmatched cards / empty day) | job log `$HASP395 ... MAXCC` | Batch ops |
| R6 | Post-run reconciliation on the first legacy day | `ledger_recon.sql` with `acct_before = ACCTDATA.PS` (restored), `acct_after = ACCTDATA.XFER(0)`, `fees = XFER.FEES(0)`; recon report grand total = fee total | DBA, Finance |
| R7 | Record the incident in COG-1249 with the diff that triggered it; shadow mode resumes before the next cut-over attempt and G1 restarts from zero | PR / decision register | Migration lead |

If R2 has any row with `ok = f`, **stop**: do not run R3. Common causes:

| Failing check | Meaning | Action |
|---|---|---|
| `fees_rows_in_ledger`, `fees_fields_match_ledger`, `fee_total` | adapter `XFER.FEES` and ledger disagree | Rebuild the generation for that day from the ledger, or roll back one more day |
| `ledger_rows_not_in_fees_file` | ledger has rows for that date the fees file does not explain (partial or repeated Java run) | Investigate before restoring; never delete ledger rows without Finance |
| `accounts_not_explained_by_ledger`, `balance_total_delta_equals_minus_fees` | generation balances do not equal previous master + ledger legs | Do not restore this generation |
| `account_set_unchanged`, `ledger_legs_unknown_account` | generation is not a full master (BR-16) | Do not restore this generation |
| `incoming_already_in_ledger` | the next DALYTRAN re-sends transfers Java already posted | Remove those records from the input with the business, or the run will abend (BR-14) |

## Ledger reconciliation query

`ops/cutover/ledger_recon.sql` loads four CSVs into temp tables and returns one row per
check (`check|expected|actual|ok`). The dry-run builds the CSVs from the datasets with
`tools/parity/copybook.py`; in production the DBA can export them the same way or use the
equivalent unloads.

```sh
psql -v ON_ERROR_STOP=1 -At -F'|' -v load=load.sql -f ops/cutover/ledger_recon.sql
```

`load.sql` holds four `\copy` lines (psql does not expand variables inside `\copy`); see the
header of `ledger_recon.sql`. Checks:

| Check | Proves |
|---|---|
| `fees_rows_in_ledger` | every `XFER.FEES` record has a ledger row |
| `fees_fields_match_ledger` | date, accounts, book, amount, fee and cap flag agree |
| `ledger_rows_not_in_fees_file` | no extra ledger rows in the run's date range |
| `fee_total` | fee totals agree |
| `account_set_unchanged` | generation has exactly the accounts of the previous master |
| `ledger_legs_unknown_account` | every ledger leg hits a known account |
| `accounts_not_explained_by_ledger` | per account: Δbalance = credits − debits, Δcycle credit = target legs, Δcycle debit = source legs + fee (BR-11/12) |
| `balance_total_delta_equals_minus_fees` | the run moved money only by the fees |
| `ledger_duplicate_tran_ids` | key integrity of the whole ledger |
| `incoming_already_in_ledger` | the next DALYTRAN cannot double-post |

The dry-run also runs two negative drills that must make the query fail (re-sending the Java
day's DALYTRAN; a generation off by 0.01 on one account).
