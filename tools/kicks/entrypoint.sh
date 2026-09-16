#!/bin/bash
# Boot MVS 3.8j TK5 under Hercules in daemon mode and keep the console log on
# stdout. SIGTERM runs the TK5 orderly shutdown script so the DASD volumes are
# left consistent.
set -u
cd /opt/mvs

herc_cmd() {
    curl -fsS -o /dev/null --get --data-urlencode "command=$1" \
        http://127.0.0.1:8038/cgi-bin/tasks/syslog || true
}

shutdown_mvs() {
    echo "*** SIGTERM: shutting MVS down ***"
    herc_cmd "script scripts/shutdown"
    for _ in $(seq 1 60); do
        kill -0 "$HERC_PID" 2>/dev/null || break
        sleep 1
    done
    kill -0 "$HERC_PID" 2>/dev/null && herc_cmd "quit"
    wait "$HERC_PID" 2>/dev/null
    exit 0
}

mkdir -p log
hercules -d -f conf/tk5.cnf 2>&1 | tee log/3033.log &
HERC_PID=$(pgrep -n -x hercules)
trap shutdown_mvs TERM INT
wait
