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

## Shadow run (legacy vs candidate)

`make shadow` feeds one daily input (`DALYTRAN.PS`, `CARDXREF.PS`,
`ACCTDATA.PS`) plus a `CTL_XFER_PARM` rule snapshot to the COBOL chain and to a
candidate implementation, then diffs the outputs with `compare.py`:

```sh
make shadow                                 # synthetic day, 250 txns, Java candidate
make shadow COUNT=200 CANDIDATE=legacy      # COBOL self-shadow (must PASS)
make shadow CASE=half_cent CANDIDATE=naive  # half-even drift (must FAIL)
make shadow DATE=2024-06-30 SEED=7 SHADOW_ARGS="--rules rules.csv"
```

Both sides read the same staged copy under `work/shadow/<date>/input/` and
`db2_before/`; input and rule sha256 digests are recorded and re-checked after
the run. Each run writes `report.md`, `report.json` (per-transfer fee, account
balances, `XFER_FEE_LEDGER`, reconciliation report totals) and the full
`compare.md` field diff to `work/shadow/<date>/`. The exit code is 0 for no
differences, 1 for any difference, and 2 when a side fails to run.

Candidates: `java` runs `java/parity-replay/target/parity-replay.jar` (from the
Java workspace, COG-1234); `legacy` re-runs the COBOL chain; `naive` runs
`tools/parity/naive_ref.py`; `cmd` runs any `--candidate-cmd` template, e.g. a
client for live services. Templates can use `{day}`, `{input}`, `{db2_before}`,
`{rules}`, `{candidate}` and `{date}`. The candidate must write
`datasets/`, `db2_after/`, `sysout/` and `rc.json` under `{candidate}`.
`--legacy-dir` diffs against pre-recorded COBOL outputs instead of re-running
the chain.

Synthetic days come from `tools/fixtures/gen_fixtures.py --synthetic COUNT
--date YYYY-MM-DD --seed N --out DIR`. The seed defaults to the date, so a day
always regenerates the same way. `.github/workflows/shadow-nightly.yml` runs a
250-transaction synthetic day every night and publishes the report.

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
