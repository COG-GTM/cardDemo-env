# xferfee Java port

Maven multi-module (Java 21, Spring Boot 3.3) port of the `XFRDAILY` transfer-fee chain.
Every change is gated by COBOL parity: `make parity-java` must report 0 diffs against the
recorded fixtures in `fixtures/xferfee/<case>/expected/`.

| Module | Replaces | Contract it implements | Ticket |
|---|---|---|---|
| `contracts` | copybooks / DSN layouts | (frozen shared types) | COG-1234 |
| `fee-policy` | `XFERFEE` fee compute | `FeePolicy` | COG-1235 |
| `fee-schedule-service` | `CTL_XFER_PARM` lookup | `FeeSchedule` | COG-1236 |
| `transfer-intake-service` | `CBXFR01C` (STEP010) | `TransferIntake` | COG-1237 |
| `account-posting-service` | `XFERFEE` posting (STEP020) | `AccountPosting` | COG-1238 |
| `reconciliation-service` | `CBXFR03C` (STEP030) | `Reconciliation` | COG-1239 |
| `legacy-adapter` | dataset I/O | Java copybook codec | COG-1240 |
| `parity-replay` | `XFERFEEP` proc / JCL | harness | COG-1234 |

## Frozen contracts (`com.carddemo.xferfee.contracts`)

Events `TransferRequested`, `TransferPosted`, `TransferRejected` (+ `RejectReason`);
`FeePolicy` (`FeeResult apply(BigDecimal amount, FeeRule rule)`), `FeeRule`, `FeeResult`,
`FeeSchedule`; records `Account`, `CardXref`, `DailyTransaction`, `LedgerEntry`, `StepReport`;
step SPIs `TransferIntake`, `AccountPosting`, `Reconciliation`.
Money is `BigDecimal` (JSON string), dates `LocalDate` (`yyyy-MM-dd` string), book ids trimmed.
Do not rename these types; additive changes only.

## How a module plugs into `parity-replay`

`parity-replay` does **not** component-scan other modules. Register your implementation as a
Spring Boot auto-configuration:

1. `@AutoConfiguration` class in your module that exposes a bean of your contract interface.
2. List it in `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
3. Keep infrastructure (DataSource, Flyway, web) out of the replay path: parity-replay runs with
   `xferfee.replay=true`, `spring.main.web-application-type=none` and no DataSource. Gate
   DB-backed beans with `@ConditionalOnProperty(name = "xferfee.replay", havingValue = "false", matchIfMissing = true)`
   and provide an in-memory variant for replay.

If a contract bean is absent, its step is skipped and writes nothing (diffs are expected).
To let downstream steps be exercised before upstream ones land, a missing `TransferIntake`
is stubbed with the recorded `XFER.EXTRACT` and a missing `AccountPosting` with the recorded
`XFER.FEES` (inputs only; the stubbed step's own outputs stay missing). Disable with
`--no-stub-upstream`.

## Build and run

```bash
make up && make build          # COBOL estate
make parity                    # COBOL self-parity (must be green)
make parity-java               # all cases
make parity-java CASE=default  # one case
make parity-java CASE=default ONLY=AWS.M2.CARDDEMO.XFER.FEES   # one DSN/table
make java-test                 # mvn verify
```

Outputs land in `work/parity-java/<case>/`: `input/` (fixture decoded to JSON-lines),
`out/` (raw Java output: `datasets/<DSN>.jsonl`, `db2_after/*.csv`, `sysout/*.txt`, `rc.json`),
`candidate/` (fixed-width datasets encoded by `tools/parity/java_candidate.py`) and `report.md`.
