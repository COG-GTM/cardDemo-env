#!/usr/bin/env python3
"""Build a parity candidate directory from the Java parity-replay CLI.

Per case this decodes the fixture's fixed-width input datasets to JSON-lines,
runs ``parity-replay`` in-process against them, and encodes the JSON-lines it
writes back into fixed-width datasets with ``copybook.encode_record`` so that
``compare.py --candidate`` can diff them unchanged.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from decimal import Decimal
from pathlib import Path

from copybook import decode_record, encode_record, parse_copybook, record_length


ROOT = Path(__file__).resolve().parents[2]
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
CASES = (
    "default",
    "under_cap",
    "at_cap",
    "rate_change",
    "zero_amount",
    "non_transfer",
    "half_cent",
)
INPUTS = {
    "ACCTDATA.PS": ("AWS.M2.CARDDEMO.ACCTDATA.PS", "CVACT01Y"),
    "CARDXREF.PS": ("AWS.M2.CARDDEMO.CARDXREF.PS", "CVACT03Y"),
    "DALYTRAN.PS": ("AWS.M2.CARDDEMO.DALYTRAN.PS", "CVTRA05Y"),
}
DEFAULT_JAR = ROOT / "java" / "parity-replay" / "target" / "parity-replay.jar"


def to_text(value: object, scale: int) -> str:
    if isinstance(value, Decimal):
        return format(value, f".{scale}f")
    return str(value)


def decode_inputs(case: str, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    for filename, (dsn, copybook) in INPUTS.items():
        source = CHAIN_ROOT / case / "input" / filename
        if not source.exists():
            continue
        fields = parse_copybook(copybook)
        scales = {name: scale for name, _, _, _, scale, _ in fields}
        length = record_length(fields)
        data = source.read_bytes()
        with (destination / f"{dsn}.jsonl").open("w") as stream:
            for offset in range(0, len(data) - length + 1, length):
                values = decode_record(copybook, data[offset:offset + length])
                record = {
                    name: to_text(value, scales.get(name, 0))
                    for name, value in values.items()
                }
                stream.write(json.dumps(record) + "\n")


def encode_outputs(case: str, candidate: Path) -> None:
    metadata = json.loads((CHAIN_ROOT / case / "case.json").read_text())
    datasets = candidate / "datasets"
    datasets.mkdir(parents=True, exist_ok=True)
    for output in metadata["outputs"]:
        source = candidate / "jsonl" / f"{output['dsn']}.jsonl"
        if not source.exists():
            continue
        records = [
            json.loads(line)
            for line in source.read_text().splitlines()
            if line.strip()
        ]
        if output.get("text"):
            payload = b"".join(
                (record.get("line", "") + "\n").encode("ascii")
                for record in records
            )
        else:
            payload = b"".join(
                encode_record(output["copybook"], record) for record in records
            )
        (datasets / f"{output['dsn']}.G0001V00").write_bytes(payload)


def run_replay(case: str, candidate: Path, jar: Path, java: str) -> None:
    subprocess.run(
        [
            java,
            "-jar",
            str(jar),
            "--case",
            case,
            "--out",
            str(candidate),
            "--fixtures",
            str(CHAIN_ROOT),
            "--input",
            str(candidate / "input"),
        ],
        cwd=ROOT,
        check=True,
    )


def build_candidate(case: str, candidate: Path, jar: Path, java: str) -> None:
    if case not in CASES:
        raise ValueError(f"unsupported case: {case}")
    if candidate.exists():
        shutil.rmtree(candidate)
    decode_inputs(case, candidate / "input")
    run_replay(case, candidate, jar, java)
    encode_outputs(case, candidate)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default=None)
    parser.add_argument("--all", action="store_true")
    parser.add_argument(
        "--root",
        type=Path,
        default=ROOT / "work" / "parity-java",
        help="candidates are written to <root>/<case>/candidate",
    )
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    cases = CASES if args.all else (args.case,)
    if cases[0] is None:
        parser.error("--case is required unless --all is used")
    if not args.jar.exists():
        parser.error(f"parity-replay jar not found: {args.jar}")
    for case in cases:
        build_candidate(case, args.root / case / "candidate", args.jar, args.java)
    return 0


if __name__ == "__main__":
    sys.exit(main())
