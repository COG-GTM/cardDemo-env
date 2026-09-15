# Third-party notices

## AWS CardDemo

This estate is based on the upstream AWS CardDemo repository:

* URL: https://github.com/aws-samples/aws-mainframe-modernization-carddemo
* Pinned upstream commit: `59cc6c2fd7ebd7ef7925cad552a01a4b8b6e4d5e`
* License: Apache-2.0

Vendored upstream material includes the original COBOL programs and
copybooks, BMS maps, JCL and procedures, scheduler exports, assembler and
macro members, sample jobs, diagrams, catalog/control members, and CardDemo
fixtures.

## Open COBOL ESQL

The container builds and installs Open COBOL ESQL from
https://github.com/opensourcecobol/Open-COBOL-ESQL, pinned to release tag
`v1.4`. It is licensed under Apache-2.0.

The Docker build runs `autoreconf -fiv` before configuring the pinned source;
this is required when building the release checkout in the Ubuntu 22.04 /
GnuCOBOL 3.1.2 environment. ESQL batch modules are linked with
`--no-as-needed` so GnuCOBOL's dynamic CALL resolution can load
`libocesql.so` at runtime.

## Local modifications

* Upstream members are reorganized into the root estate layout.
* Phase 1 adds the transfer-fee COBOL chain, copybooks, JCL, fixtures,
  tooling, and Postgres-compatible Db2-style SQL.
