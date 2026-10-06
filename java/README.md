# xferfee Java workspace

Maven multi-module port of the `XFRDAILY` transfer-fee chain (Java 21,
Spring Boot 3). Every module is gated by Java↔COBOL parity against the
recorded fixtures in `fixtures/xferfee/<case>/`.

| Module | Package | Owns |
|---|---|---|
| `contracts` | `com.carddemo.xferfee.contracts` | Frozen events, `FeePolicy`, replay SPI |
| `fee-policy` | `…feepolicy` | `FeePolicy` implementation (BR-07…BR-10) |
| `fee-schedule-service` | `…feeschedule` | Effective-dated `FeeRule` lookup (BR-06) |
| `transfer-intake-service` | `…transferintake` | STEP010 / CBXFR01C (BR-01…BR-05) |
| `account-posting-service` | `…accountposting` | STEP020 / XFERFEE posting (BR-11…BR-17) |
| `reconciliation-service` | `…reconciliation` | STEP030 / CBXFR03C report (BR-18, BR-19) |
| `legacy-adapter` | `…legacyadapter` | Native copybook codec, file ingress/egress |
| `parity-replay` | `…replay` | In-process replay CLI used by `make parity-java` |

## Frozen contracts

`contracts` is the shared API every other module codes against; rename or
reshape these only through a dedicated ticket:

- Events: `TransferRequested`, `TransferPosted`, `TransferRejected`
- `FeePolicy` — `FeeResult apply(BigDecimal amount, FeeRule rule)`
- Records: `FeeRule`, `FeeResult`, `Account`
- Replay SPI: `replay.ReplayStage`, `replay.ReplayContext`

Money is `BigDecimal` and serializes to JSON as strings.

## Joining the replay

A module joins the parity loop by registering a Spring bean that implements
`ReplayStage` in its own package (`parity-replay` component-scans
`com.carddemo.xferfee` and already depends on every module):

```java
@Component
class IntakeStage implements ReplayStage {
    public String step() { return "STEP010"; }
    public int order() { return 10; }
    public int run(ReplayContext ctx) {
        var transactions = ctx.input("AWS.M2.CARDDEMO.DALYTRAN.PS");
        ctx.writeDataset("AWS.M2.CARDDEMO.XFER.EXTRACT", List.of(/* CVXFR01Y fields */));
        ctx.sysout("STEP010", "CBXFR01C: RECORDS READ 000000004");
        return 0;
    }
}
```

`ReplayContext` records are copybook field name → string value. Inputs are
the fixture's `input/*.PS` decoded with `tools/parity/copybook.py`; DB2
tables start from `db2_before/*.csv` (columns upper case) and are dumped to
`db2_after/` after the last stage. Unimplemented stages simply write nothing.

## Parity loop

```sh
make up && make build && make parity      # COBOL baseline must be green
make parity-java                          # all cases
make parity-java CASE=half_cent           # one case
make parity-java CASE=half_cent ONLY=XFER_FEE_LEDGER,RC
```

`parity-java` builds the workspace inside the `estate` container, then
`tools/parity/java_candidate.py` decodes inputs to JSON-lines, runs
`parity-replay --case <name> --out <dir>`, and encodes the JSON-lines
(`jsonl/<DSN>.jsonl`) back to fixed-width datasets so `compare.py
--candidate` diffs them unchanged. Reports land in `work/parity-java/`.
`ONLY` accepts DSNs, DB2 tables, step names, `SYSOUT`, or `RC`.

Fix Java on any diff; never edit expected outputs.
