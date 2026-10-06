#!/usr/bin/env python3
"""Parity for fee-schedule-service (BR-06) against COBOL-recorded fixtures.

For each case the Java service is started against a throwaway PostgreSQL,
seeded from ``db2_before/CTL_XFER_PARM.csv``, and checked two ways:

* every record in the recorded ``XFER.FEES`` dataset is looked up through
  ``GET /fee-rules/effective`` and must return the same FEE-PCT and
  RULE-EFF-DT that XFERFEE selected;
* ``GET /fee-rules`` (text/csv) must be byte-equal to
  ``expected/db2_after/CTL_XFER_PARM.csv``.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from decimal import Decimal
from pathlib import Path

from compare import CHAIN_ROOT, CASES, ROOT, dataset_file
from copybook import decode_record, parse_copybook, record_length


FEES_DSN = "AWS.M2.CARDDEMO.XFER.FEES"
FEES_COPYBOOK = "CVXFR02Y"
JAVA_ROOT = ROOT / "java"
JAR = JAVA_ROOT / "fee-schedule-service" / "target" / "fee-schedule-service.jar"
PG_IMAGE = "postgres:15"
PG_USER = PG_PASSWORD = PG_DB = "feeschedule"


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def build_jar() -> None:
    subprocess.run(
        ["mvn", "-q", "-f", str(JAVA_ROOT / "pom.xml"), "-pl",
         "fee-schedule-service", "-am", "package", "-DskipTests"],
        check=True,
    )


def start_postgres() -> tuple[str, int]:
    port = free_port()
    name = f"fee-schedule-parity-{os.getpid()}"
    subprocess.run(
        ["docker", "run", "-d", "--rm", "--name", name,
         "-e", f"POSTGRES_USER={PG_USER}", "-e", f"POSTGRES_PASSWORD={PG_PASSWORD}",
         "-e", f"POSTGRES_DB={PG_DB}", "-p", f"127.0.0.1:{port}:5432", PG_IMAGE],
        check=True, stdout=subprocess.DEVNULL,
    )
    for _ in range(60):
        ready = subprocess.run(
            ["docker", "exec", name, "pg_isready", "-h", "127.0.0.1",
             "-U", PG_USER, "-d", PG_DB],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        if ready.returncode == 0:
            return name, port
        time.sleep(1)
    raise RuntimeError("PostgreSQL did not become ready")


def http_get(url: str, accept: str) -> tuple[int, bytes]:
    request = urllib.request.Request(url, headers={"Accept": accept})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def start_service(java: str, db_port: int, seed: Path, log: Path) -> tuple[subprocess.Popen, str]:
    port = free_port()
    log.parent.mkdir(parents=True, exist_ok=True)
    process = subprocess.Popen(
        [java, "-jar", str(JAR), f"--server.port={port}",
         f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:{db_port}/{PG_DB}",
         f"--spring.datasource.username={PG_USER}",
         f"--spring.datasource.password={PG_PASSWORD}",
         f"--fee-schedule.seed-csv={seed}"],
        stdout=log.open("w"), stderr=subprocess.STDOUT,
    )
    base = f"http://127.0.0.1:{port}"
    for _ in range(120):
        if process.poll() is not None:
            raise RuntimeError(f"fee-schedule-service exited; see {log}")
        try:
            if http_get(base + "/fee-rules", "application/json")[0] == 200:
                return process, base
        except OSError:
            pass
        time.sleep(0.5)
    process.terminate()
    raise RuntimeError(f"fee-schedule-service did not start; see {log}")


def recorded_fee_lookups(case: str) -> list[dict[str, object]]:
    path = dataset_file(CHAIN_ROOT / case / "expected" / "datasets", FEES_DSN)
    if path is None:
        return []
    length = record_length(parse_copybook(FEES_COPYBOOK))
    data = path.read_bytes()
    return [
        decode_record(FEES_COPYBOOK, data[offset:offset + length])
        for offset in range(0, len(data), length)
    ]


def check_case(case: str, base: str, candidate: Path) -> tuple[str, int]:
    diffs: list[str] = []
    lookups = []
    for record in recorded_fee_lookups(case):
        tran_id = str(record["XFE-TRAN-ID"]).strip()
        book = str(record["XFE-BOOK-ID"]).strip()
        date = str(record["XFE-TRAN-DT"]).strip()
        query = urllib.parse.urlencode({"book": book, "date": date})
        status, body = http_get(f"{base}/fee-rules/effective?{query}", "application/json")
        actual = json.loads(body) if status == 200 else {}
        lookups.append({"tran_id": tran_id, "book": book, "date": date,
                        "status": status, "rule": actual})
        expected_pct = Decimal(record["XFE-FEE-PCT"])
        expected_eff = str(record["XFE-RULE-EFF-DT"]).strip()
        if status != 200:
            diffs.append(f"| {FEES_DSN} | {tran_id} | HTTP | 200 | {status} |")
            continue
        if Decimal(actual["feePct"]) != expected_pct:
            diffs.append(f"| {FEES_DSN} | {tran_id} | XFE-FEE-PCT | "
                         f"{expected_pct:.6f} | {actual['feePct']} |")
        if actual["effDt"] != expected_eff:
            diffs.append(f"| {FEES_DSN} | {tran_id} | XFE-RULE-EFF-DT | "
                         f"{expected_eff} | {actual['effDt']} |")

    status, csv_bytes = http_get(base + "/fee-rules", "text/csv")
    after = candidate / "db2_after" / "CTL_XFER_PARM.csv"
    after.parent.mkdir(parents=True, exist_ok=True)
    after.write_bytes(csv_bytes)
    (candidate / "fee_lookups.jsonl").write_text(
        "".join(json.dumps(item, sort_keys=True) + "\n" for item in lookups))
    expected_bytes = (
        CHAIN_ROOT / case / "expected" / "db2_after" / "CTL_XFER_PARM.csv"
    ).read_bytes()
    byte_equal = status == 200 and csv_bytes == expected_bytes
    if not byte_equal:
        before = len(diffs)
        expected_lines = expected_bytes.splitlines()
        actual_lines = csv_bytes.splitlines()
        for index, (left, right) in enumerate(zip(expected_lines, actual_lines), 1):
            if left != right:
                diffs.append(f"| CTL_XFER_PARM | line {index} | bytes | "
                             f"{left.decode()} | {right.decode()} |")
        if len(expected_lines) != len(actual_lines) or len(diffs) == before:
            diffs.append(f"| CTL_XFER_PARM | file | bytes | {len(expected_bytes)} bytes | "
                         f"{len(csv_bytes)} bytes (HTTP {status}) |")

    verdict = "PASS" if not diffs else "FAIL"
    lines = [
        f"# Fee-schedule parity: xferfee / {case} — {verdict}",
        "",
        f"- XFER.FEES rule lookups checked: {len(lookups)}",
        f"- CTL_XFER_PARM.csv byte-equal: {'yes' if byte_equal else 'no'}",
        "",
        "| Dataset/Table | Record key | Field | Expected | Actual |",
        "|---|---|---|---|---|",
        *diffs,
        "",
        f"PARITY: {verdict}" + (f" ({len(diffs)} differences)" if diffs else ""),
    ]
    report = "\n".join(lines) + "\n"
    (candidate.parent / "fee-schedule-report.md").write_text(report)
    return report, 1 if diffs else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--case", action="append", choices=CASES)
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--java", default=os.environ.get("JAVA", "java"))
    parser.add_argument("--rebuild", action="store_true")
    args = parser.parse_args()
    cases = list(CASES) if args.all or not args.case else args.case
    if args.rebuild or not JAR.exists():
        build_jar()

    out_root = ROOT / "work" / "parity-java"
    container, db_port = start_postgres()
    overall = 0
    summary = []
    try:
        for case in cases:
            candidate = out_root / case / "fee-schedule"
            if candidate.exists():
                shutil.rmtree(candidate)
            seed = CHAIN_ROOT / case / "db2_before" / "CTL_XFER_PARM.csv"
            process, base = start_service(
                args.java, db_port, seed, out_root / case / "fee-schedule.log")
            try:
                report, rc = check_case(case, base, candidate)
            finally:
                process.terminate()
                process.wait(timeout=30)
            print(report)
            summary.append(f"{case}: {'PASS' if rc == 0 else 'FAIL'}")
            overall = max(overall, rc)
    finally:
        subprocess.run(["docker", "stop", container],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("Fee-schedule parity summary: " + ", ".join(summary))
    return overall


if __name__ == "__main__":
    sys.exit(main())
