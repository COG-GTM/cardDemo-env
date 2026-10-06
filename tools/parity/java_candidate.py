#!/usr/bin/env python3
"""Replay xferfee fixtures through the Java services and compare with the COBOL recording.

For each case: decode the fixture's fixed-width inputs to JSON-lines, run
java/parity-replay, encode its JSON-lines outputs back to fixed-width datasets
and hand the candidate directory to compare.py.

Until transfer-intake (COG-1237) exists, posting is fed the XFER.EXTRACT that
the COBOL STEP010 recorded for the case.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from decimal import Decimal
from pathlib import Path

from compare import CASES, CHAIN_ROOT, ROOT, compare_case, dataset_file
from copybook import decode_record, encode_record, parse_copybook, record_length

JAVA_ROOT = ROOT / "java"
WORK_ROOT = ROOT / "work" / "parity-java"
MAIN_CLASS = "com.carddemo.parity.ParityReplayApplication"
JAVA_OUTPUTS = {
    "AWS.M2.CARDDEMO.XFER.FEES": "CVXFR02Y",
    "AWS.M2.CARDDEMO.ACCTDATA.XFER": "CVACT01Y",
}


def decode_dataset(path: Path, copybook: str, destination: Path) -> None:
    fields = parse_copybook(copybook)
    scales = {name: scale for name, _, _, kind, scale, _ in fields if kind != "text"}
    length = record_length(fields)
    data = path.read_bytes() if path.exists() else b""
    with destination.open("w") as stream:
        for index in range(0, len(data), length):
            values = decode_record(copybook, data[index:index + length])
            row = {
                name: format(value, f".{scales[name]}f")
                if isinstance(value, Decimal) else value
                for name, value in values.items()
            }
            stream.write(json.dumps(row) + "\n")


def encode_dataset(source: Path, copybook: str, destination: Path) -> None:
    records = bytearray()
    if source.exists():
        for line in source.read_text().splitlines():
            if line.strip():
                records += encode_record(copybook, json.loads(line))
    destination.write_bytes(bytes(records))


def classpath() -> str:
    cp_file = JAVA_ROOT / "parity-replay" / "target" / "cp.txt"
    if not cp_file.exists():
        raise SystemExit("java classpath missing: run `make java-build` first")
    classes = [
        JAVA_ROOT / module / "target" / "classes"
        for module in ("parity-replay", "account-posting-service", "contracts")
    ]
    return os.pathsep.join([*map(str, classes), cp_file.read_text().strip()])


def replay(case: str, java: str) -> Path:
    case_root = CHAIN_ROOT / case
    out = WORK_ROOT / case / "candidate"
    if out.exists():
        shutil.rmtree(out)
    jsonl_in = out / "input"
    jsonl_in.mkdir(parents=True)
    decode_dataset(case_root / "input" / "ACCTDATA.PS", "CVACT01Y", jsonl_in / "ACCTDATA.jsonl")
    decode_dataset(case_root / "input" / "CARDXREF.PS", "CVACT03Y", jsonl_in / "CARDXREF.jsonl")
    extract = dataset_file(case_root / "expected" / "datasets", "AWS.M2.CARDDEMO.XFER.EXTRACT")
    decode_dataset(extract or Path("/nonexistent"), "CVXFR01Y", jsonl_in / "XFER.EXTRACT.jsonl")

    subprocess.run(
        [java, "-cp", classpath(), MAIN_CLASS,
         f"--in={jsonl_in}", f"--db2-before={case_root / 'db2_before'}", f"--out={out}"],
        cwd=ROOT,
        check=True,
    )

    datasets = out / "datasets"
    datasets.mkdir(exist_ok=True)
    for dsn, copybook in JAVA_OUTPUTS.items():
        encode_dataset(out / "jsonl" / f"{dsn}.jsonl", copybook, datasets / f"{dsn}.G0001V00")
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case")
    parser.add_argument("--all", action="store_true")
    parser.add_argument(
        "--only",
        default="",
        help="comma-separated DSNs, tables and SYSOUT steps to compare (empty or 'all' = everything)",
    )
    parser.add_argument("--java", default=os.environ.get("JAVA", "java"))
    parser.add_argument("--report", type=Path, default=WORK_ROOT / "report.md")
    args = parser.parse_args()
    cases = CASES if args.all or not args.case else (args.case,)
    only = None
    if args.only and args.only != "all":
        only = {item.strip() for item in args.only.split(",") if item.strip()}

    reports = []
    overall = 0
    for case in cases:
        candidate = replay(case, args.java)
        report, rc = compare_case(case, candidate, only)
        reports.append(report)
        overall = max(overall, rc)
    text = "\n".join(reports)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(text)
    print(text, end="")
    summary = "PASS" if overall == 0 else "FAIL"
    print(f"\nJAVA PARITY: {summary} ({len(cases)} case(s); scope: {args.only or 'all'})")
    return overall


if __name__ == "__main__":
    sys.exit(main())
