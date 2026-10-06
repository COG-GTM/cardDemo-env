#!/usr/bin/env python3
"""Bridge between the fixed-width xferfee fixtures and the Java parity-replay harness.

  inputs  decode a fixture's input/*.PS (and the recorded upstream outputs used to stub
          unimplemented steps) into JSON-lines that parity-replay reads.
  encode  turn parity-replay's JSON-lines per DSN back into fixed-width datasets with
          copybook.encode_record, so compare.py can diff the candidate unchanged.
  run     inputs -> java -jar parity-replay -> encode -> compare.py, per case.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from decimal import Decimal
from pathlib import Path
from typing import Any

from compare import CASES, CHAIN_ROOT, ROOT, compare_case, dataset_file
from copybook import decode_record, encode_record, parse_copybook, record_length

INPUTS = {
    "DALYTRAN": "CVTRA05Y",
    "CARDXREF": "CVACT03Y",
    "ACCTDATA": "CVACT01Y",
}
STUB_DSNS = ("AWS.M2.CARDDEMO.XFER.EXTRACT", "AWS.M2.CARDDEMO.XFER.FEES")
GENERATION = ".G0001V00"
DEFAULT_JAR = ROOT / "java" / "parity-replay" / "target" / "parity-replay.jar"


def case_metadata(case: str) -> dict[str, Any]:
    return json.loads((CHAIN_ROOT / case / "case.json").read_text())


def to_json(values: dict[str, Any]) -> dict[str, str]:
    return {
        name: format(value, "f") if isinstance(value, Decimal) else str(value)
        for name, value in values.items()
    }


def decode_dataset(path: Path, copybook: str) -> list[dict[str, str]]:
    length = record_length(parse_copybook(copybook))
    data = path.read_bytes()
    return [
        to_json(decode_record(copybook, data[offset:offset + length]))
        for offset in range(0, len(data), length)
    ]


def write_jsonl(path: Path, rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row) + "\n" for row in rows))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [
        json.loads(line) for line in path.read_text().splitlines() if line.strip()
    ]


def stage_inputs(case: str, out: Path) -> None:
    case_dir = CHAIN_ROOT / case
    for name, copybook in INPUTS.items():
        write_jsonl(
            out / f"{name}.jsonl",
            decode_dataset(case_dir / "input" / f"{name}.PS", copybook),
        )
    copybooks = {
        output["dsn"]: output["copybook"]
        for output in case_metadata(case)["outputs"]
        if not output.get("text")
    }
    for dsn in STUB_DSNS:
        recorded = dataset_file(case_dir / "expected" / "datasets", dsn)
        target = out / "stub" / f"{dsn}.jsonl"
        if recorded is None:
            target.unlink(missing_ok=True)
            continue
        write_jsonl(target, decode_dataset(recorded, copybooks[dsn]))


def encode_candidate(case: str, raw: Path, out: Path) -> None:
    if out.exists():
        shutil.rmtree(out)
    datasets = out / "datasets"
    datasets.mkdir(parents=True)
    for output in case_metadata(case)["outputs"]:
        source = raw / "datasets" / f"{output['dsn']}.jsonl"
        if not source.exists():
            continue
        rows = read_jsonl(source)
        target = datasets / f"{output['dsn']}{GENERATION}"
        if output.get("text"):
            target.write_bytes(
                b"".join((row["line"] + "\n").encode("ascii") for row in rows)
            )
        else:
            target.write_bytes(
                b"".join(encode_record(output["copybook"], row) for row in rows)
            )
    for folder in ("db2_after", "sysout"):
        if (raw / folder).is_dir():
            shutil.copytree(raw / folder, out / folder)
    if (raw / "rc.json").exists():
        shutil.copy2(raw / "rc.json", out / "rc.json")


def replay(case: str, inputs: Path, raw: Path, jar: Path, stub: bool) -> None:
    command = [
        "java", "-jar", str(jar), "--case", case, "--out", str(raw),
        "--input", str(inputs), "--fixtures", str(CHAIN_ROOT),
    ]
    if not stub:
        command.append("--no-stub-upstream")
    subprocess.run(command, cwd=ROOT, check=True)


def run(args: argparse.Namespace) -> int:
    cases = CASES if args.all or not args.case else (args.case,)
    only = (
        {name.strip().upper() for name in args.only.split(",") if name.strip()}
        if args.only is not None else None
    )
    reports: list[str] = []
    summary: list[tuple[str, str]] = []
    overall = 0
    for case in cases:
        work = args.work / case
        inputs, raw, candidate = work / "input", work / "raw", work / "candidate"
        try:
            stage_inputs(case, inputs)
            replay(case, inputs, raw, args.jar, not args.no_stub_upstream)
            encode_candidate(case, raw, candidate)
            report, rc = compare_case(case, candidate, only)
        except (subprocess.CalledProcessError, OSError, ValueError, KeyError) as exc:
            report = f"# Parity: xferfee / {case} — ERROR\n\nharness error: {exc}\n"
            rc = 2
        reports.append(report)
        verdict = report.strip().splitlines()[-1]
        summary.append((case, verdict))
        overall = max(overall, rc)
    lines = ["# Java parity summary (xferfee)", "", "| Case | Result |", "|---|---|"]
    lines += [f"| {case} | {verdict} |" for case, verdict in summary]
    text = "\n".join(lines) + "\n\n" + "\n".join(reports)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(text)
    print(text, end="")
    return overall


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    inputs = commands.add_parser("inputs")
    inputs.add_argument("--case", required=True)
    inputs.add_argument("--out", type=Path, required=True)

    encode = commands.add_parser("encode")
    encode.add_argument("--case", required=True)
    encode.add_argument("--raw", type=Path, required=True)
    encode.add_argument("--out", type=Path, required=True)

    runner = commands.add_parser("run")
    runner.add_argument("--case")
    runner.add_argument("--all", action="store_true")
    runner.add_argument("--only")
    runner.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    runner.add_argument("--work", type=Path, default=ROOT / "work" / "parity-java")
    runner.add_argument(
        "--report", type=Path, default=ROOT / "work" / "parity-java" / "report.md"
    )
    runner.add_argument("--no-stub-upstream", action="store_true")

    args = parser.parse_args()
    if args.command == "inputs":
        stage_inputs(args.case, args.out)
        return 0
    if args.command == "encode":
        encode_candidate(args.case, args.raw, args.out)
        return 0
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
