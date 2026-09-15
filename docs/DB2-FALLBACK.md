# Db2 runtime fallback

The demo uses Open COBOL ESQL with PostgreSQL instead of IBM Db2 CE. GnuCOBOL
does not have an IBM Db2 precompiler path, while Db2 CE requires license
acceptance and adds an approximately 3 GB image that is too heavy for CI.

The COBOL source retains Db2-style `EXEC SQL` and the control/ledger DDL is
written in Db2-like uppercase SQL that also runs on PostgreSQL.
