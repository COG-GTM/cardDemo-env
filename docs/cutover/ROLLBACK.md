# XFRDAILY cut-over rollback

Use during the rollback window (RUNBOOK §4, steps C4–C6) when Java posting must
be abandoned: recon break, Java outage that misses the batch window, or a
CAB/Finance call. Goal: the mainframe chain takes over again from the latest
state that the Java side handed back through legacy-adapter, with no fee posted
twice and none lost.

Rehearse with `make cutover-rollback-dryrun` (see §4). It runs the same steps on the Compose stack.

## 1. Decide and freeze

| Step | Action | Owner |
|---|---|---|
| R0 | Rollback called on the change record (Migration lead + Finance + Ops on the bridge). Note the failing business day **D**. | CAB |
| R1 | Stop Java posting for D (pause intake consumer and the posting service). Let legacy-adapter finish writing the generation for the last **completed** day; if day D is half-posted, that day is replayed by XFRDAILY in R6, not restored. | Migration lead |
| R2 | Confirm the ledger mirror is current: every Java-posted transfer up to the last completed day has a row in `XFER_FEE_LEDGER` (`POSTED_TS > CUTOVER_TS`). | Migration lead |

## 2. Restore from the latest legacy-adapter generation

| Step | Action | Verify |
|---|---|---|
| R3 | Identify `AWS.M2.CARDDEMO.ACCTDATA.XFER(0)`: the most recent generation written by legacy-adapter egress (`LISTCAT ENT(AWS.M2.CARDDEMO.ACCTDATA.XFER) ALL`). It must be newer than the C2 cut-over generation. Note its generation number. | Generation number, record count, LRECL 300 on the change record |
| R4 | **Ledger reconciliation** (query in §3) with `ACCT_BASE` = the `ACCTDATA.PS` backup at `CUTOVER_TS` (or the previous good generation), `ACCT_GEN` = `ACCTDATA.XFER(0)`, `FEES_GEN` = `XFER.FEES(0)`, `since_ts` = `CUTOVER_TS`. | `RECON_BREAKS=0`. **Any break: stop.** Do not restore; Finance decides which side is right. |
| R5 | Back up the current `ACCTDATA.PS` (IDCAMS REPRO to `AWS.M2.CARDDEMO.ACCTDATA.PS.PRERBK`). Submit [`ops/cutover/XFRRBACK.jcl`](../../ops/cutover/XFRRBACK.jcl): IDCAMS `REPRO` from `ACCTDATA.XFER(0)` into `ACCTDATA.PS`. | XFRRBACK MAXCC=0000; record count of `ACCTDATA.PS` equals the generation |

## 3. Re-enable XFRDAILY

| Step | Action | Verify |
|---|---|---|
| R6 | Release XFRDAILY in the scheduler (it was HELD at C3, never deleted). First run processes day D's `DALYTRAN.PS`; transfers already in the ledger for D must be removed from the input first (duplicate `TRAN_ID` makes XFERFEE abend RC 8 on the ledger `PRIMARY KEY`). | XFRDAILY MAXCC ≤ 4, new `ACCTDATA.XFER(+1)`, `XFER.FEES(+1)`, `XFER.RECON.RPT(+1)` cataloged |
| R7 | Run the reconciliation again: base = generation restored in R5, gen = new `ACCTDATA.XFER(0)`, `since_ts` = timestamp taken just before R6. | `RECON_BREAKS=0`; XFRRECON report totals equal the ledger window totals per book |
| R8 | Keep Java posting disabled; reset the shadow-run exit counter (RUNBOOK §2) to 0; open an incident for the root cause. | Change record closed as *rolled back* |

## 4. Ledger reconciliation query

[`ops/cutover/ledger_recon.sql`](../../ops/cutover/ledger_recon.sql). It checks
that the ledger rows posted since `since_ts` fully explain the difference
between the pre-window account master and the generation being restored, using
the posting rule of `XFERFEE` (2100-PROCESS-TRANSFER):

- source account: `CURR_BAL -= TRAN_AMT + FEE_AMT`, `CYC_DEBIT += TRAN_AMT + FEE_AMT`
- target account: `CURR_BAL += TRAN_AMT`, `CYC_CREDIT += TRAN_AMT`

and that `XFER.FEES(0)` matches the ledger window row for row (`TRAN_ID`, date,
accounts, book, amount, fee, cap flag). Break types:

| CHECK_NAME | Meaning |
|---|---|
| `ACCOUNT` | balance / cycle credit / cycle debit delta on an account ≠ what the ledger says, or the account is missing from one of the masters |
| `LEDGER_ACCT` | ledger posts to an account that is in neither master |
| `FEES_VS_LEDGER` | transfer in `XFER.FEES(0)` but not in the ledger window, or vice versa, or a field differs |

Inputs are three temp tables (`ACCT_BASE`, `ACCT_GEN`, `FEES_GEN`) loaded from
unloads of the data sets and the psql variable `since_ts`. On the mainframe,
unload the two account masters and the fee generation (DFSORT/IDCAMS PRINT →
CSV) into the same columns. The dry-run driver shows the exact load script.

Core of the account check:

```sql
WITH win AS (SELECT * FROM XFER_FEE_LEDGER WHERE POSTED_TS > CAST(:'since_ts' AS TIMESTAMP)),
expected AS (
  SELECT ACCT_ID, SUM(D_BAL) D_BAL, SUM(D_CREDIT) D_CREDIT, SUM(D_DEBIT) D_DEBIT
    FROM (SELECT SRC_ACCT_ID ACCT_ID, -(TRAN_AMT+FEE_AMT) D_BAL, 0 D_CREDIT, TRAN_AMT+FEE_AMT D_DEBIT FROM win
          UNION ALL
          SELECT TGT_ACCT_ID, TRAN_AMT, TRAN_AMT, 0 FROM win) m
   GROUP BY ACCT_ID)
SELECT COALESCE(g.ACCT_ID, b.ACCT_ID) AS ACCT_ID
  FROM ACCT_GEN g
  FULL JOIN ACCT_BASE b ON b.ACCT_ID = g.ACCT_ID
  LEFT JOIN expected e ON e.ACCT_ID = COALESCE(g.ACCT_ID, b.ACCT_ID)
 WHERE g.ACCT_ID IS NULL OR b.ACCT_ID IS NULL
    OR g.CURR_BAL   - b.CURR_BAL   <> COALESCE(e.D_BAL, 0)
    OR g.CYC_CREDIT - b.CYC_CREDIT <> COALESCE(e.D_CREDIT, 0)
    OR g.CYC_DEBIT  - b.CYC_DEBIT  <> COALESCE(e.D_DEBIT, 0);
```

## 5. Dry run on the Compose stack

```sh
make up && make build
make cutover-rollback-dryrun        # log: work/cutover/rollback-dryrun.log
```

`tools/cutover/rollback_dryrun.py` works in `work/cutover/datasets` (separate
from `datasets/`) and truncates `XFER_FEE_LEDGER` at the start, like `make run`.

| Step | What it does |
|---|---|
| A1 | Load fixture `default` as coexistence day N, define the GDG bases (DEFGDGX) |
| A2 | Day-N posting → `ACCTDATA.XFER.G0001V00` + ledger rows. **Stand-in:** until COG-1240 lands, the COBOL chain writes this generation. `--adapter-generation <file>` swaps in a real legacy-adapter file. |
| B1 | Locate `ACCTDATA.XFER(0)`, check LRECL 300 and that every record decodes with `CVACT01Y` |
| B2 | Ledger reconciliation, pre-restore → must be `RECON_BREAKS=0` |
| B2a | Self-test: the same query against a copy of the generation with one record dropped must report a break |
| B3 | Back up `ACCTDATA.PS` |
| B4 | Submit `XFRRBACK` → `ACCTDATA.PS` byte-identical to `ACCTDATA.XFER(0)` |
| C1 | Re-enable XFRDAILY on day N+1 (2024-07-01, 2 new transfers + 1 non-transfer) → `G0002V00` |
| C2 | Ledger reconciliation for day N+1 → must be `RECON_BREAKS=0` |

The run ends with `ROLLBACK DRY RUN: PASS`, or `FAIL (<steps>)` and a non-zero exit code.
