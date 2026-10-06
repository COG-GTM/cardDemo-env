#!/bin/sh
# Creates the carddemo_live database used by the parity console:
#   public schema      -> CTL_XFER_PARM / XFER_FEE_LEDGER read and written by the real COBOL chain
#   java_engine schema -> owned by the Spring Boot engine (tables created by the app)
# The DDL and seed rows are the estate's own db2/ files, so both sides start from the
# same fee rules as the recorded fixtures.
set -eu
LIVE_DB=${LIVE_DB:-carddemo_live}

if ! psql -d carddemo -tAc "SELECT 1 FROM pg_database WHERE datname = '$LIVE_DB'" | grep -q 1; then
    createdb "$LIVE_DB"
fi
for table in CTL_XFER_PARM XFER_FEE_LEDGER; do
    exists=$(psql -d "$LIVE_DB" -tAc "SELECT to_regclass('public.$table') IS NOT NULL")
    if [ "$exists" != "t" ]; then
        psql -v ON_ERROR_STOP=1 -d "$LIVE_DB" -f "/db2/ddl/$table.sql"
    fi
done
psql -v ON_ERROR_STOP=1 -d "$LIVE_DB" -c "TRUNCATE CTL_XFER_PARM"
psql -v ON_ERROR_STOP=1 -d "$LIVE_DB" -f /db2/data/CTL_XFER_PARM.sql
psql -v ON_ERROR_STOP=1 -d "$LIVE_DB" -c "CREATE SCHEMA IF NOT EXISTS java_engine"
echo "live parity database $LIVE_DB ready"
