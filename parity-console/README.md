# parity-console — GnuCOBOL chain vs running Java, transaction by transaction

Customer-facing live parity demo for the CardDemo daily transfer-fee chain (COG-1250).

Every record of a fixture's daily-transaction feed goes through **both** sides:

| Side | What actually runs |
| --- | --- |
| **Legacy** | The real `XFRDAILY` JCL (`CBXFR01C` → `XFERFEE` → `CBXFR03C`) on GnuCOBOL inside the estate container. Fees are looked up from `CTL_XFER_PARM` and posted to `XFER_FEE_LEDGER` in Postgres through Open-COBOL-ESQL. There's one JCL run per transaction, and each run uses the account master the previous run wrote. ([`cobol/feed.py`](cobol/feed.py)) |
| **Java** | A Spring Boot 3 / Java 21 engine for the same rules (BR extract + fee + posting), running in this process. ([`XferFeeEngine`](src/main/java/com/carddemo/parity/engine/XferFeeEngine.java)) |

The web UI puts each transaction's results side by side: fee, cap flag, rule used (rate + effective date), source and target balance after posting, and the ledger row. Each transaction gets a **MATCH / DIFF** verdict, and the page keeps running totals.

**Break it** switches the Java side from COBOL `ROUNDED` (`RoundingMode.HALF_UP`) to `BigDecimal` `HALF_EVEN`. Run `half_cent` to watch the console catch real one-cent diffs. On `at_cap` the cap hides the difference, so it still matches.

## Run it

```sh
make up && make build          # estate + Postgres, compile the COBOL chain
make parity-console            # builds the jar, serves http://localhost:8080
```

Open http://localhost:8080, pick a case (`default`, `half_cent`, `rate_change`, `at_cap`, …) and press **Run feed**. Click a row to see both ledger rows, the GnuCOBOL SYSOUT and the step return codes.

Configuration lives in `application.properties` or can be passed as `--parity.*=` arguments:

| Property | Default |
| --- | --- |
| `parity.cobol-command` | `docker compose exec -T estate python3 parity-console/cobol/feed.py` |
| `parity.repo-root` | auto-detected: the current directory, or its parent if started from `parity-console/` |
| `server.port` / `PORT` | `8080` |

## Batch parity gate (`make parity-java`)

The same engine also writes a full candidate directory (datasets, `db2_after/`, SYSOUT, `rc.json`). `tools/parity/compare.py --candidate` diffs it against the COBOL-recorded expected outputs:

```sh
make parity                            # COBOL baseline must be green first
make parity-java                       # all xferfee cases
make parity-java CASE=half_cent        # one case
make parity-java CASE=half_cent BREAK=1   # proves the gate catches HALF_EVEN (expected FAIL)
```

Reports are written to `work/parity/<case>/java-report.md`.

## Layout

```
parity-console/
  cobol/feed.py                        per-transaction XFRDAILY runner (inside the estate container)
  src/main/java/com/carddemo/parity/
    records/                           copybook record codecs (CVTRA05Y, CVACT01Y, CVACT03Y, zoned/COMP-3)
    engine/                            CBXFR01C + XFERFEE rules, CTL_XFER_PARM lookup, rounding policy
    candidate/                         compare.py candidate writer + CBXFR03C recon report
    console/                           SSE run service, GnuCOBOL process bridge, field comparison, REST
  src/main/resources/static/           single-page console UI
```

When the P1–P5 services land, replace `XferFeeEngine` in `ParityRunService` with calls to those services. The COBOL side and the comparison stay as they are.
