# CardDemo Mainframe Estate

This repository contains the COBOL, copybook, JCL, PROC, Db2-style SQL, and
batch metadata used by the migration demonstration. The local runnable chain
is `xferfee`.

## Build

The estate uses Docker Compose, PostgreSQL, and Open COBOL ESQL:

```sh
make up
make build
```

Docker Hub may rate-limit the default `ubuntu:22.04` image. A compatible
cached image can be selected locally without changing repository
configuration:

```sh
ESTATE_BASE_IMAGE=mcr.microsoft.com/devcontainers/base:ubuntu-22.04 make up
```

See [runtime notes](docs/RUNTIME-NOTES.md) for the PostgreSQL and ocesql
fallback details.

## Run a chain

Run the default transfer-fee fixtures through the JCL runner:

```sh
make run CHAIN=xferfee
```

The joblog is written under `work/joblog/`.

## Record fixtures

Record one case or all seven deterministic cases:

```sh
make record CASE=half_cent
make record-all
```

Recorded datasets, SYSOUT, return codes, and database snapshots are committed
under `fixtures/xferfee/`.

## Run parity

Self-parity runs each chain against its committed recording:

```sh
make parity
```

The naive reference intentionally uses Java-style half-even fee rounding:

```sh
make parity-naive CASE=under_cap
make parity-naive CASE=half_cent
```

The non-tie cases pass; `half_cent` is expected to fail with fee and derived
ledger differences.

## fee-schedule-service (Java)

`java/fee-schedule-service` is the Spring Boot owner of the effective-dated
fee rules (BR-06), migrated from `CTL_XFER_PARM` into `fee_rule` via Flyway.
It needs JDK 21, Maven, and Docker on the host:

```sh
make fee-schedule-test                    # unit + Testcontainers PostgreSQL tests
make parity-fee-schedule CASE=rate_change # one case; omit CASE for all seven
```

`GET /fee-rules/effective?book=RETAIL&date=2024-06-15` returns the single
rule with `eff_dt <= date < exp_dt` (404 when none, 409 when legacy data
overlaps). `GET /fee-rules` with `Accept: text/csv` returns the table in the
fixture `db2_after/CTL_XFER_PARM.csv` layout. Set `fee-schedule.seed-csv` to
a fixture's `db2_before/CTL_XFER_PARM.csv` to replace all rules at startup.

## Dead code split

Generate deterministic SMF-shaped activity and classify every JCL member:

```sh
make deadcode
```

The CSV is `ops/smf/job_activity.csv`; the report lists idle days, referenced
programs, and RETIRE/MIGRATE verdicts.

## Chain graph

Generate the code-derived JCL/COBOL/data relationship graph:

```sh
make chain-graph
make chain-graph-check
```

The generated Markdown is `docs/chain-graph.md`.

## Reset

Remove local datasets, build output, work output, and the Compose database:

```sh
make reset
```

Use `make down` when only the containers should stop.
