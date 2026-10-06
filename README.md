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

## Java parity and shadow run

The Java port lives under `java/` (JDK 21 + Maven on the host, see
`java/README.md`). `make parity-java [CASE=x]` replays recorded cases through
`java/shadow-run` and diffs the candidate with `compare.py`
(`work/parity-java/<case>/report.md`, summary in `work/parity-java/summary.md`).

`make shadow` is migration phase 3: it stages one day's `DALYTRAN.PS`,
`CARDXREF.PS`, `ACCTDATA.PS` and a `CTL_XFER_PARM` snapshot under
`work/shadow/<date>/`, runs `XFRDAILY` in the estate and the Java chain on the
identical files, and writes `report.md` / `report.json` (per-transfer fee,
balances, ledger, report totals). Exit 0 = identical, 1 = differences,
2 = a leg did not finish.

```sh
make shadow                                              # synthetic day of 250, today
make shadow SHADOW_ARGS="--synthetic 300 --date 2024-06-15"
make shadow SHADOW_ARGS="--case default --date 2024-06-20"
make shadow SHADOW_ARGS="--input-dir /estate/... --rules ctl.csv --date 2024-07-01"
```

The `shadow-nightly` workflow runs it every night on a generated day.

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
