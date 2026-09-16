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

## Genuine 3270 path (Hercules + MVS 3.8j + KICKS)

An optional `mvs` Compose profile boots a real MVS 3.8j (TK5) under Hercules
and runs the CardDemo sign-on and main menu on KICKS for TSO, a free CICS
look-alike. It is a feasibility spike, separate from the batch estate above:

```sh
make mvs-up        # build (downloads TK5 + KICKS, SHA256-verified), IPL
make mvs-install   # one-time KICKS install onto the mvs-dasd volume
make mvs-carddemo  # compile + run COSGN00C / COMEN01C, dump screens
make mvs-3270      # interactive c3270 on localhost:3270
```

What runs, what had to be rewritten and why, and what can never run there
(Db2, MQ, COBOL-85) is in `docs/KICKS-NOTES.md`.

## Reset

Remove local datasets, build output, work output, and the Compose database:

```sh
make reset
```

Use `make down` when only the containers should stop.
