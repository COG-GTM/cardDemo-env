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

## Java services and Java parity

The Java 21 / Spring Boot workspace lives in `java/` (`contracts`,
`account-posting-service`, `parity-replay`). It runs on the host and needs a JDK
21 and Maven; no estate container is required.

```sh
make java-test                    # unit tests
make parity-java                  # all cases
make parity-java CASE=half_cent   # one case
make parity-java ONLY=all         # every chain output, including ones not ported yet
```

`tools/parity/java_candidate.py` decodes the fixture inputs to JSON-lines, runs
`parity-replay` (H2, Flyway, `posting.mode=batch-atomic`), encodes the outputs
back to fixed-width datasets and calls `compare.py --only`. By default `ONLY`
limits the comparison to the outputs the ported services own:
`XFER.FEES`, `ACCTDATA.XFER`, `XFER_FEE_LEDGER`, `CTL_XFER_PARM` and `STEP020`.
Until transfer-intake is ported, posting reads the recorded `XFER.EXTRACT`.

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
