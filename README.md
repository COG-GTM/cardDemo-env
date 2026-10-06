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

## Java parity

The Spring Boot rewrite lives in `java/` (Java 21, Maven multi-module). Frozen
events, records and stage ports are in `java/contracts`; each service module
implements one port. Replay a case through whichever stages exist and compare
it to the COBOL recording:

```sh
make parity-java CASE=default
```

`tools/parity/java_candidate.py` decodes the fixture inputs to JSON-lines, runs
`parity-replay`, and encodes its output into the candidate layout. A stage with
no bean stops the chain, so an unfinished rewrite reports missing records and
RC differences rather than crashing. CI runs this job allowed-to-fail and
publishes the report to the job summary.

Until the upstream stages exist, `make parity-java` stops before STEP030. To
check reconciliation (`CBXFR03C`) on its own, `make parity-java-recon [CASE=..]`
feeds each case's recorded STEP020 outputs (`XFER.FEES`, STEP020 RC) to
`reconciliation-service` and diffs a candidate that is the COBOL recording with
every STEP030 artifact replaced by Java output.

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
