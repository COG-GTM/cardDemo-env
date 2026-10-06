# Decision Register: xferfee Semantics the Event-Driven Target Can't Keep Exactly

Linear: [COG-1249](https://linear.app/cog-gtm/issue/COG-1249/decision-register-semantics-the-event-driven-target-cant-keep-exactly)
Status: **Awaiting customer sign-off**

The nightly chain `XFRDAILY` (`CBXFR01C` extract → `XFERFEE` post → `CBXFR03C`
reconcile) behaves in a few ways that a per-event Spring Boot service can't
copy exactly. This register records one decision per behaviour. Implementation
tickets must read it before picking a default.

Ground rules:

- The legacy COBOL, JCL and fixtures are **not changed**. Every legacy
  behaviour below stays the parity oracle (`make parity`).
- "Parity mode" means a switch in the target that reproduces the legacy
  behaviour bit-for-bit, so the parity gate can compare against COBOL
  recordings. "Target default" is what production runs once signed off.
- Recommendations are Devin's proposal. Nothing is decided until the
  sign-off table at the end is filled in.

## Summary

| # | Legacy behaviour | Recommended target default | Parity mode needed |
|---|------------------|----------------------------|--------------------|
| D1 | One bad transfer rolls back the whole night (single `COMMIT`) | Per-transfer transaction | Yes: batch-atomic mode |
| D2 | Missing fee rule or account aborts the run, RC 8 | Park the transfer in a DLQ, alert, keep processing | Yes: abort-on-first-error |
| D3 | Duplicate `TRAN_ID` fails the ledger insert and aborts | Idempotent no-op; alert if the payload differs | Yes: abort-on-duplicate |
| D4 | No funds, status or credit-limit checks (BR-13) | Keep the omission; new checks are a separate product change | No (same behaviour) |
| D5 | Report subtotals break on file order, not true per-book | Keep a legacy renderer; add a separate true per-book endpoint | Yes: legacy renderer |
| D6 | Empty day ends RC 4 with "NO FEE RECORDS" | Emit a warning metric and alert, not a silent success | Yes: RC 4 mapping |

## D1. Transaction atomicity

**Legacy.** `XFERFEE` updates balances and inserts ledger rows for every
transfer, then issues one `EXEC SQL COMMIT` after `3000-WRITE-MASTER`
(`cobol/XFERFEE.cbl`). Any error goes to `9999-ABEND-PROGRAM`, which sets
RC 8 and `STOP RUN`s without committing. The result: if transfer N fails,
transfers 1..N-1 are rolled back too, no `ACCTDATA.XFER` / `XFER.FEES`
generation is catalogued, and `STEP030` is skipped (`COND=(4,LT,STEP020)` in
`jcl/proc/XFERFEEP.prc`). Ops reruns the whole night after fixing the input.

**Why the target can't keep it.** An event-driven service processes each
transfer as it arrives. Holding one database transaction open across a whole
day's events is not practical, and one poison message would block all later
ones.

**Recommendation.**
- Target default: one transaction per transfer (ledger insert + both balance
  updates + outbox event commit together). A failed transfer does not affect
  the others.
- Parity mode `batch-atomic`: the replay harness wraps a whole run in a
  single transaction and rolls it all back on the first error, so parity
  fixtures with an abend still show zero committed rows.

**Impact.** Finance sees partial days in production where legacy showed all
or nothing. Daily reconciliation must report "posted N, parked M" instead of
a single pass/fail.

## D2. Missing fee rule or account

**Legacy.** `2100-GET-FEE-RULE` displays `NO FEE RULE FOR BOOK <book>` and
abends with RC 8 when `CTL_XFER_PARM` has no row for the book and date
(`SQLCODE = 100`). `2200-UPDATE-BALANCES` displays `ACCOUNT NOT FOUND
<src> / <tgt>` and abends with RC 8 when either account is missing from the
master. Combined with D1, the entire night is lost.

**Recommendation.**
- Target default: move the transfer to a dead-letter queue with the reason
  (`NO_FEE_RULE`, `SOURCE_ACCOUNT_NOT_FOUND`, `TARGET_ACCOUNT_NOT_FOUND`),
  raise an alert, and continue with the next transfer. Parked items are
  replayed after the rule or account is fixed.
- Parity mode: abort on the first error with the legacy reason text and
  RC 8, nothing committed.

**Decision needed.** Who owns the DLQ (ops or finance), the alert channel,
and the maximum age before a parked transfer escalates.

## D3. Duplicate transaction IDs

**Legacy.** `XFER_FEE_LEDGER.TRAN_ID` is the primary key
(`db2/ddl/XFER_FEE_LEDGER.sql`). A second transfer with the same ID, or a
rerun of a night that already committed, fails the insert, displays `LEDGER
INSERT FAILED <sqlcode>` and abends RC 8. With D1, nothing from that run is
committed.

**Why the target can't keep it.** Event delivery is at-least-once. Redelivery
of the same event is normal and must not stop the service.

**Recommendation.**
- Target default: treat a repeated `TRAN_ID` with an identical payload
  (date, accounts, book, amount) as an idempotent no-op. A repeated
  `TRAN_ID` with a **different** payload is parked in the DLQ and alerted
  (`DUPLICATE_TRAN_ID_CONFLICT`); it is never silently dropped or
  overwritten.
- Parity mode: abort on the duplicate with RC 8, nothing committed.

## D4. No funds, status or limit checks (BR-13)

**Legacy.** `XFERFEE` posts every selected transfer. It does not check
`ACCT-CURR-BAL`, `ACCT-ACTIVE-STATUS` or `ACCT-CREDIT-LIMIT` on either
account, so a transfer from an inactive account can drive its balance
negative.

**Recommendation.** Keep the omission in the migrated service so the target
is a like-for-like replacement. Adding funds, status or limit checks is a
product change with its own ticket and its own sign-off, after cutover.

**Impact.** No parity switch needed. Reviewers should not treat missing
checks in the Java code as a bug.

## D5. Reconciliation subtotals

**Legacy.** `CBXFR03C` prints a subtotal whenever `XFE-BOOK-ID` changes from
the previous record. The fee file is in extract order, not sorted by book,
so interleaved books (RETAIL, INSTL, RETAIL) give three subtotal lines with
RETAIL appearing twice. The grand total is correct. This is a
file-order "control break", not true per-book grouping.

**Recommendation.**
- Keep a legacy renderer that reproduces the report byte-for-byte from the
  day's fee records in posting order. Parity compares against it.
- Add a separate endpoint (or report) with true per-book totals. Downstream
  consumers move to it on their own schedule.

**Decision needed.** Whether any downstream consumer parses the legacy
report. If none, the legacy renderer can be retired after parity sign-off.

## D6. Empty day

**Legacy.** When no type-08 transfers exist, `CBXFR03C` displays `NO FEE
RECORDS` and ends RC 4. RC 4 is a warning: the step is not skipped and the
job is not failed, so an empty day is easy to miss.

**Recommendation.**
- Target default: emit a metric (`xferfee.daily.transfers = 0`) and a
  warning alert when a business day closes with no transfers, instead of a
  silent success.
- Parity mode: the batch replay maps "no fee records" to RC 4 and the legacy
  message.

**Decision needed.** Whether weekends and holidays should suppress the alert.

## Evidence

Each behaviour above was reproduced against the unmodified legacy chain
(GnuCOBOL + Open COBOL ESQL + PostgreSQL 15). The video and command output
are posted on the pull request for COG-1249.

## Sign-off

| # | Decision | Approved option | Approver | Date |
|---|----------|-----------------|----------|------|
| D1 | Transaction atomicity | | | |
| D2 | Missing rule / account | | | |
| D3 | Duplicate `TRAN_ID` | | | |
| D4 | No funds / status / limit checks | | | |
| D5 | Reconciliation subtotals | | | |
| D6 | Empty day | | | |
