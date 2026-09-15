# Runtime notes

The demo uses Open COBOL ESQL with PostgreSQL instead of IBM Db2 CE. GnuCOBOL
does not have an IBM Db2 precompiler path, while Db2 CE requires license
acceptance and adds an approximately 3 GB image that is too heavy for CI.

The COBOL source retains Db2-style `EXEC SQL` and the control/ledger DDL is
written in Db2-like uppercase SQL that also runs on PostgreSQL.

## Docker base image troubleshooting

The Compose file defaults to `ubuntu:22.04` and uses `pull_policy: missing` so
cached images are reused. If Docker Hub is rate-limited, use a locally cached
Ubuntu 22.04-compatible image for the estate build without changing the
repository configuration:

```sh
ESTATE_BASE_IMAGE=mcr.microsoft.com/devcontainers/base:ubuntu-22.04 make up
```
