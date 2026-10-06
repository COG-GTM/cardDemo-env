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

## Legacy adapter (Java)

`java/legacy-adapter` is the coexistence boundary between the COBOL estate and
Java services: a native copybook codec plus file ingress/egress. It needs JDK 21
and Maven.

```sh
make java-test                    # unit + fixture round-trip tests
make parity-codec                 # python and java codecs, all 7 cases
make parity-codec CODEC=java CASE=half_cent
```

- Codec: parses `copybook/*.cpy` (`PIC X`, zoned `PIC S9V9`, `COMP-3`,
  `OCCURS`). Decoding accepts mainframe overpunch (`{A-I}`/`}J-R`) and GnuCOBOL
  ASCII signs (`p-y`); FILLER and unchanged fields keep their original bytes, so
  every fixture dataset round-trips byte for byte.
- Ingress: `DALYTRAN.PS` to a `TransactionPublisher` (`card.transactions`),
  `ACCTDATA.PS` and `CARDXREF.PS` to a `ReferenceDataLoader`.
- Egress: new generation of `AWS.M2.CARDDEMO.ACCTDATA.XFER` in the runjcl GDG
  layout (`.GnnnnV00` plus `.gdg` catalog, LIMIT 5 SCRATCH).
- `codec_parity.py` decodes each recorded output, re-encodes it with
  `--codec=python` and `--codec=java`, runs `compare.py` on both candidates,
  and requires byte-identical output from the two codecs.

The CLI jar (`make java-build`) also exposes `decode`, `roundtrip`, `ingest`
and `egress`; run it without arguments for usage.

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
