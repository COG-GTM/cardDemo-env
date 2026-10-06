# Java event mode

The `java` Compose profile replaces the JCL steps of `xferfee` with services
that communicate only through Kafka topics and a shared PostgreSQL database.

| COBOL step | Java process | Consumes | Produces |
|---|---|---|---|
| STEP010 `CBXFR01C` | `transfer-intake-service` | `xfer.daily-tran` | `TransferRequested`, `ExtractClosed` on `xfer.transfer-requested` |
| `CTL_XFER_PARM` lookup | `fee-schedule-service` | HTTP `GET /fee-rules/effective` | effective rule, 404 when none, 409 when ambiguous |
| STEP020 `XFERFEE` | `account-posting-service` | `xfer.transfer-requested` | `TransferPosted`, `TransferRejected`, `BatchPosted` via the outbox |
| — | `outbox-relay` | `xfer_java.outbox` | `xfer.transfer-posted` |
| STEP030 `CBXFR03C` | `reconciliation-service` | `xfer.transfer-posted` | reconciliation report |

Every service writes its SYSOUT lines and step return code to
`xfer_java.run_sysout` and `xfer_java.run_step`; `parity-replay` turns those
rows, the posted records, and the `CTL_XFER_PARM` / `XFER_FEE_LEDGER` tables
into the same candidate layout that `tools/parity/recorder.py` produces.

## Fidelity rules

- Money is `BigDecimal`; fees use `ROUND_HALF_UP` (`COMPUTE ... ROUNDED`) and
  the cap applies only when the fee is strictly greater than `FEE_CAP`.
- Output datasets are written with the CVXFR01Y, CVXFR02Y, and CVACT01Y
  layouts. Account fields keep their input bytes unless XFERFEE ran
  ADD/SUBTRACT on them; those are re-encoded the way GnuCOBOL does (plain
  digit when positive, `p`-`y` when negative), and FILLER is LOW-VALUES.
- The seven committed cases are byte-identical to the COBOL recording, not
  only field-equal under the comparator.

## Posting modes (decision register D1)

`XFER_POSTING_MODE` selects how `account-posting-service` commits:

| Mode | Behaviour | Parity status |
|---|---|---|
| `batch-atomic` (default) | buffers the extract and posts it in one transaction at `ExtractClosed`; any failure rolls back every posting, writes `XFERFEE: 9999-ABEND-PROGRAM`, and ends STEP020 with RC 8 so STEP030 does not run | parity mode used by `make parity-java` and CI |
| `per-transfer` | commits each transfer on its own; a failing transfer emits `TransferRejected` and STEP020 ends with RC 4 | target mode; identical to COBOL when no transfer fails |

Documented per-transfer differences from COBOL, only visible when a transfer
fails:

1. Transfers before and after the failing one are posted and ledgered instead
   of rolled back.
2. STEP020 ends with RC 4 rather than 8, and STEP030 reports the posted
   transfers.
3. The account master is written with the successful postings.

X1–X6 fixtures (COG-1243 to COG-1248) are picked up automatically by
`make parity-java` once they are committed under `fixtures/xferfee/`.
