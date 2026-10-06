# Decision Register: xferfee Semantics the Event-Driven Target Can't Keep Exactly

Linear: [COG-1249](https://linear.app/cog-gtm/issue/COG-1249/decision-register-semantics-the-event-driven-target-cant-keep-exactly)
Status: **Awaiting customer sign-off**

The nightly chain `XFRDAILY` → proc `XFERFEEP` (`STEP010 CBXFR01C` extract →
`STEP020 XFERFEE` fee + posting → `STEP030 CBXFR03C` reconciliation report)
behaves in a few ways that a per-event Spring Boot service can't copy exactly.
This register records one decision per behaviour. Implementation tickets read
it before picking a default.

Ground rules:

- The legacy COBOL, JCL, copybooks and recorded fixtures are **not changed**.
  The legacy behaviour stays the parity oracle (`make parity`).
- **Parity mode** is a switch in the target that reproduces the legacy
  behaviour so `make parity-java` can compare against the COBOL recordings.
  **Target default** is what production runs once signed off.
- Recommendations are proposals. Nothing is decided until the sign-off table
  at the end is filled in. Until then, implementation tickets build the
  parity-mode behaviour and keep the target default behind a switch.

## Summary

| # | Legacy behaviour | Rules | Recommended target default | Parity mode needed |
|---|------------------|-------|----------------------------|--------------------|
| D1 | One bad transfer rolls back the whole night (single `COMMIT`) | BR-15 | One transaction per transfer, rejects parked | Yes: `batch-atomic` |
| D2 | Missing fee rule or account aborts the run, RC 8 | BR-07, BR-12 | Park the transfer (DLQ + `transfer.rejected`), alert, keep going | Yes: abort on first error |
| D3 | Duplicate `TRAN_ID` fails the ledger insert and aborts | BR-14 | Idempotent no-op; park + alert if the payload differs | Yes: abort on duplicate |
| D4 | No funds, status or credit-limit checks | BR-13 | Keep the omission; new checks are a later product change | No (same behaviour) |
| D5 | Report subtotals break on file order, not per book | BR-18 | Legacy renderer **and** a true per-book endpoint | Yes: legacy renderer |
| D6 | Empty day ends RC 4 with a header-only report | BR-19 | Warning metric + alert, not a silent success | Yes: RC 4 mapping |

Items found while verifying D1–D6 against the running chain (not in the
original ticket scope, but they also need a written answer):

| # | Legacy behaviour | Recommended | Parity mode needed |
|---|------------------|-------------|--------------------|
| D7 | Unresolved card/source account is skipped with RC 4 and posting continues (BR-05) | Keep the skip; also emit `transfer.rejected` so it is visible | Yes: RC 4 on `STEP010` |
| D8 | Abend messages carry a raw SQLCODE whose value depends on the database (`-403` here, `-803` on Db2) | Target emits reason codes; failure fixtures assert RC + reason, not SQLCODE digits | Partly |
| D9 | In-memory tables silently stop at 500 accounts / 500 cross-reference rows | Drop the limit | No (fixtures stay under 500) |

## D1. Transaction atomicity (BR-15)

**Legacy.** `XFERFEE` inserts a ledger row per transfer and updates balances
in memory, then issues one `EXEC SQL COMMIT` in `0000-MAIN`, after
`2000-POST-TRANSFERS` and `3000-WRITE-MASTER` (`cobol/XFERFEE.cbl`). Every
error path goes to `9999-ABEND-PROGRAM`, which sets RC 8 and `STOP RUN`s
without committing, so the connection closes and PostgreSQL/Db2 roll back.
If transfer N fails, transfers 1..N-1 are rolled back too. The new
`ACCTDATA.XFER(+1)` and `XFER.FEES(+1)` generations are not catalogued
(`DISP=(NEW,CATLG,DELETE)`), and `STEP030` does not run
(`COND=(4,LT,STEP020)` in `jcl/proc/XFERFEEP.prc`). Ops fixes the input and
reruns the whole night.

**Why the target can't keep it.** An event-driven service handles each
transfer as it arrives. Holding one database transaction open across a whole
business day is not practical, and one poison message would block every
later one.

**Recommendation.**
- Target default: one database transaction per transfer (`fee_ledger` insert
  + both `account` balance updates + outbox `transfer.posted` event). A failed
  transfer is parked (see D2) and does not affect the others.
- Optional switch "pause the book": stop consuming the affected book's
  partition until the reject is fixed, then replay. This is the nearest
  production equivalent of all-or-nothing.
- Parity mode `batch-atomic`: the replay harness wraps a whole fixture run in
  one transaction and rolls back on the first error, so failure fixtures
  still show zero committed rows, no new master, no fee file, no report.

**Impact.** Finance sees partial days in production where legacy showed all
or nothing. Daily reconciliation reports "posted N, parked M" instead of one
pass/fail.

## D2. Missing fee rule or account (BR-07, BR-12)

**Legacy.** In `2100-POST-ONE`, when the `CTL_XFER_PARM` lookup returns
`SQLCODE = 100` the program displays `XFERFEE: NO FEE RULE FOR BOOK <book>`
and abends RC 8; any other non-zero SQLCODE displays `XFERFEE: RULE LOOKUP
FAILED <sqlcode>` and abends RC 8. When `2200-FIND-ACCOUNTS` cannot find the
source **or** target account in the master, `2100-POST-ONE` displays
`XFERFEE: ACCOUNT NOT FOUND <src> / <tgt>` and abends RC 8. Combined with D1,
the whole night is lost.

**Recommendation.**
- Target default: reject the transfer with a reason (`NO_FEE_RULE`,
  `RULE_LOOKUP_FAILED`, `SOURCE_ACCOUNT_NOT_FOUND`,
  `TARGET_ACCOUNT_NOT_FOUND`), publish `transfer.rejected`, park it in the
  DLQ, alert, and continue. Parked transfers are replayed after the rule or
  account is fixed. The transfer's own date still selects the rule on replay
  (BR-06), not the replay date.
- Parity mode: abort on the first error with RC 8 and the legacy message
  text, nothing committed.

**Decisions needed.** DLQ owner (ops or finance), alert channel, and the
maximum age before a parked transfer escalates.

## D3. Duplicate transaction IDs (BR-14)

**Legacy.** `XFER_FEE_LEDGER.TRAN_ID` is the primary key
(`db2/ddl/XFER_FEE_LEDGER.sql`). A second transfer with the same ID in one
file, or a rerun of a night that already committed, fails the insert,
displays `XFERFEE: LEDGER INSERT FAILED <sqlcode>` and abends RC 8. With D1,
nothing from that run is committed, and the earlier night's rows stay as
they were.

**Why the target can't keep it.** Event delivery is at-least-once.
Redelivery of the same event is normal and must not stop the service.

**Recommendation.**
- Target default: a repeated `TRAN_ID` with an identical payload (date,
  source, target, book, amount) is an idempotent no-op. A repeated `TRAN_ID`
  with a **different** payload is rejected as `DUPLICATE_TRAN_ID_CONFLICT`,
  parked and alerted. It is never silently dropped or overwritten.
- Parity mode: abort on the duplicate with RC 8, nothing committed.

## D4. No funds, status or limit checks (BR-13)

**Legacy.** `1000-LOAD-MASTER` loads `ACCT-ACTIVE-STATUS`, `ACCT-CURR-BAL`,
`ACCT-CREDIT-LIMIT` and `ACCT-CASH-CREDIT-LIMIT`, but `2100-POST-ONE` never
tests them. Any selected transfer posts, even if it takes the source balance
negative or the account is inactive.

**Recommendation.** Keep the omission so the target is a like-for-like
replacement. Funds, status or limit checks are a product change with their
own ticket, sign-off and fixtures, after cut-over. Because there are no
checks, posting order cannot change an outcome (BR-17), so keying events by
source account is enough.

**Impact.** No parity switch. Reviewers should not treat missing checks in
the Java code as a bug.

## D5. Reconciliation subtotals (BR-18)

**Legacy.** `CBXFR03C 1000-RECORD` calls `2000-SUBTOTAL` whenever
`XFE-BOOK-ID` differs from the previous record. The fee file is in extract
order, not sorted by book, so interleaved books (RETAIL, INSTL, RETAIL)
print three subtotal lines with RETAIL appearing twice. The grand total is
correct. This is a file-order control break, not per-book grouping.

**Recommendation.**
- Keep a legacy renderer that reproduces the report byte-for-byte from the
  day's fee records in posting order. Parity compares against it.
- Add a true per-book endpoint backed by `daily_book_total`. Downstream
  consumers move to it on their own schedule.

**Decision needed.** Does any downstream consumer parse the legacy report?
If none, the legacy renderer can be retired after parity sign-off.

## D6. Empty day (BR-19)

**Legacy.** With no type-08 transfers, `CBXFR01C` and `XFERFEE` both end
RC 0 (`XFERFEE` still writes a full new master generation, BR-16), and
`CBXFR03C` writes the two header lines only, displays `CBXFR03C: NO FEE
RECORDS` and ends RC 4. The job ends `MAXCC=0004`, a warning that is easy to
miss.

**Recommendation.**
- Target default: when a business day closes with zero transfers, emit a
  metric (`xferfee.daily.transfers = 0`) and a warning alert, and record the
  day as `NO_FEES` instead of a silent success.
- Parity mode: the batch replay maps "no fee records" to RC 4 on `STEP030`,
  the header-only report and the legacy message.

**Decision needed.** Should weekends and holidays suppress the alert?

## D7. Unresolved card or source account in the extract (BR-05)

**Legacy.** `CBXFR01C 2100-WRITE-TRANSFER` displays `CBXFR01C: CARD NOT
FOUND <card>` (or `ACCOUNT NOT FOUND`) and skips the transfer. `0000-MAIN`
ends RC 4 when any were skipped. `STEP020` has no `COND`, so on z/OS posting
still runs for the remaining transfers.

**Recommendation.** Keep the skip, and make it visible: publish
`transfer.rejected` with `CARD_NOT_FOUND` / `SOURCE_ACCOUNT_NOT_FOUND` and
count it in reconciliation. Parity mode keeps RC 4 on `STEP010`.

**Note for the fixture ticket.** The local runner on `main`
(`tools/runjcl/runjcl.py`) stops the job after any non-zero RC, so today it
does **not** run `STEP020` after a `STEP010` RC 4. A BR-05 fixture recorded
with that runner would capture the runner's behaviour, not z/OS's. The
runner must keep running steps after a non-abend RC (and let `COND` decide)
before that fixture is recorded. The open BR-05 fixture branch
(`devin/cog-1243-fixture-unmatched_card-lock-br-05-behaviour-before-migration`)
already makes that runner change.

## D8. SQLCODE text in abend messages

**Legacy.** `LEDGER INSERT FAILED` and `RULE LOOKUP FAILED` print the raw
SQLCODE. On this estate (Open COBOL ESQL + PostgreSQL) a duplicate key
prints `-0000000403`; IBM Db2 would report `-803`. The parity harness
compares SYSOUT line by line, so a failure fixture would pin a value that is
an artifact of the database, not a business rule.

**Recommendation.** The target emits reason codes (D2, D3). Failure fixtures
should assert RC and the reason, with the SQLCODE digits treated as
non-business text.

**Decision needed.** Agree whether failure-path SYSOUT comparisons may mask
the SQLCODE value, or whether the Java parity mode must print the
PostgreSQL-specific number.

## D9. 500-row in-memory tables

**Legacy.** `CBXFR01C` and `XFERFEE` hold at most 500 cross-reference rows
and 500 accounts. Rows past 500 are silently ignored and would show up as
BR-05 skips or BR-12 abends.

**Recommendation.** Drop the limit; it is a capacity artifact, not a rule.
No parity switch, since fixtures stay well under 500 rows.

## Evidence

Each behaviour was reproduced against the unmodified chain (GnuCOBOL + Open
COBOL ESQL + PostgreSQL 15, `make up && make build`) using scratch inputs
built with the `tools/fixtures/gen_fixtures.py` helpers, outside
`fixtures/`. Recorded fixtures were not touched.

| Scenario | Result |
|----------|--------|
| 2 good transfers, then one dated before every `CTL_XFER_PARM` window (D1, D2) | `NO FEE RULE FOR BOOK RETAIL`, `STEP020` RC 8, `MAXCC=0008`, **0** ledger rows, no master/fee/report generation catalogued |
| Good transfer, then one to an account not in the master (D2) | `ACCOUNT NOT FOUND 00000000001 / 00000000099`, RC 8, 0 ledger rows |
| Same `TRAN_ID` twice in one file (D3) | `LEDGER INSERT FAILED -0000000403`, RC 8, 0 ledger rows |
| Rerun of a committed night without truncating the ledger (D3) | RC 8 on the first insert; the first run's 3 rows unchanged |
| $5,000 transfer from an account holding $250 (D4) | RC 0; source balance becomes -4,775.00 (amount + $25 capped fee) |
| RETAIL, INSTL, RETAIL in one night (D5) | Report prints `BOOK RETAIL` subtotal twice ($100.00/$1.50 and $200.00/$3.00); grand total $1,300.00 / $9.50 |
| Only a type-01 transaction (D6) | `STEP010`/`STEP020` RC 0, `CBXFR03C: NO FEE RECORDS`, `STEP030` RC 4, header-only report |
| Transfer with a card not in the cross-reference (D7) | `CARD NOT FOUND`, `STEP010` RC 4; local runner then stops (see D7 note) |

## Sign-off

| # | Decision | Approved option | Approver | Date |
|---|----------|-----------------|----------|------|
| D1 | Transaction atomicity | | | |
| D2 | Missing rule / account | | | |
| D3 | Duplicate `TRAN_ID` | | | |
| D4 | No funds / status / limit checks | | | |
| D5 | Reconciliation subtotals | | | |
| D6 | Empty day | | | |
| D7 | Unresolved card / source account | | | |
| D8 | SQLCODE text in abend messages | | | |
| D9 | 500-row in-memory tables | | | |
