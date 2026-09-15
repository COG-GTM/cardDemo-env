# Runtime notes

The demo uses Open COBOL ESQL with PostgreSQL instead of IBM Db2 CE. GnuCOBOL
does not have an IBM Db2 precompiler path, while Db2 CE requires license
acceptance and adds an approximately 3 GB image that is too heavy for CI.

The COBOL source retains Db2-style `EXEC SQL` and the control/ledger DDL is
written in Db2-like uppercase SQL that also runs on PostgreSQL.

## GnuCOBOL rounding limitation

The runtime uses GnuCOBOL 3.1.2 from the Ubuntu 22.04 package. Its
`COMPUTE ... ROUNDED` implementation truncates intermediate results to one
more decimal place before rounding. For example, the IBM Enterprise COBOL
result for `COMPUTE F ROUNDED = 0.0525` is `0.06`, but GnuCOBOL 3.1.2
produces `0.05`; `5.00 * 0.005` produces `0.025` and rounds to `0.03`.

GnuCOBOL 3.2 was built from source and tested with the same reproduction, but
it retained this behavior. XFERFEE therefore uses the `2150-ROUND-FEE`
paragraph to emulate IBM half-cent rounding: it computes a six-decimal raw
fee, adds or subtracts `0.005`, moves the adjusted value into the two-decimal
fee field, and corrects the exact half-cent remainder. This is an explicit
workaround for the runtime limitation, not a generic ceiling.

## Docker base image troubleshooting

The Compose file defaults to `ubuntu:22.04` and uses `pull_policy: missing` so
cached images are reused. If Docker Hub is rate-limited, use a locally cached
Ubuntu 22.04-compatible image for the estate build without changing the
repository configuration:

```sh
ESTATE_BASE_IMAGE=mcr.microsoft.com/devcontainers/base:ubuntu-22.04 make up
```
