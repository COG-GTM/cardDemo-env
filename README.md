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

## Java event-mode parity

The `java` Compose profile runs the Spring Boot 3 / Java 21 rewrite of the
transfer-fee chain as real processes on Kafka and PostgreSQL:
`transfer-intake-service` (CBXFR01C), `fee-schedule-service`,
`account-posting-service` (XFERFEE), `outbox-relay`, and
`reconciliation-service` (CBXFR03C). `parity-replay --mode=events` publishes
each fixture's DALYTRAN records to Kafka, waits for the services, and writes a
candidate that `tools/parity/compare.py` checks against the COBOL recording:

```sh
make java-up
make parity-java                 # every case under fixtures/xferfee
make parity-java CASE=half_cent  # one case
make java-down
```

Reports are written to `work/parity-java/<case>/report.md` and the summary to
`work/parity-java/summary.md`. Without registry access, pass locally cached
images, for example `KAFKA_IMAGE=... JAVA_BASE_IMAGE=... make java-up`. See
[Java event mode](docs/JAVA-EVENT-MODE.md) for the topology and posting modes.

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
