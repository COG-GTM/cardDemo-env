#!/usr/bin/env python3
"""Diff SYSOUT counters and return codes of a candidate against the COBOL recording.

Expected values are parsed from ``fixtures/xferfee/<case>/expected/sysout/STEP0x0.txt``
(the DISPLAY lines of CBXFR01C, XFERFEE and CBXFR03C) and ``expected/rc.json``.
Candidate values come from the JSON parity-replay writes next to its SYSOUT text
(``<candidate>/sysout/STEP0x0.json``) and ``<candidate>/rc.json``.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from decimal import Decimal
from pathlib import Path


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
# (program, DISPLAY label) -> implied decimal places of the displayed field
COUNTERS = {
    ("CBXFR01C", "RECORDS READ"): 0,
    ("CBXFR01C", "TRANSFERS SELECTED"): 0,
    ("CBXFR01C", "UNMATCHED CARDS"): 0,
    ("XFERFEE", "TRANSFERS POSTED"): 0,
    ("XFERFEE", "TOTAL FEES"): 2,
    ("CBXFR03C", "GRAND TOTAL FEE"): 2,
}
LINE = re.compile(r"^(?P<program>[A-Z0-9]+): (?P<label>[A-Z ]+?) (?P<value>[+-]?\d+)\s*$")


def parse_sysout(path: Path) -> tuple[dict[str, Decimal], list[str]]:
    counters: dict[str, Decimal] = {}
    messages: list[str] = []
    for line in path.read_text().splitlines():
        match = LINE.match(line)
        key = (match.group("program"), match.group("label")) if match else None
        if key in COUNTERS:
            scale = COUNTERS[key]
            counters[key[1]] = Decimal(int(match.group("value"))).scaleb(-scale)
        else:
            messages.append(line)
    return counters, messages


def candidate_step(path: Path) -> tuple[dict[str, Decimal], list[str]]:
    data = json.loads(path.read_text())
    counters = {
        row["label"]: Decimal(row["value"]) for row in data.get("counters", [])
    }
    labels = {(data["program"], label) for label in counters}
    messages = [
        line for line in data.get("sysout", [])
        if not (LINE.match(line) and (
            LINE.match(line).group("program"), LINE.match(line).group("label")
        ) in labels)
    ]
    return counters, messages


def compare_case(case: str, candidate: Path) -> tuple[str, int]:
    expected_root = CHAIN_ROOT / case / "expected"
    rows: list[str] = []
    extra: list[str] = []
    checked = 0
    steps = sorted(
        {p.stem for p in (expected_root / "sysout").glob("STEP*.txt")}
        | {p.stem for p in (candidate / "sysout").glob("STEP*.json")}
    )
    for step in steps:
        expected_txt = expected_root / "sysout" / f"{step}.txt"
        actual_json = candidate / "sysout" / f"{step}.json"
        if not expected_txt.exists():
            extra.append(f"- {step}: extra step in candidate")
            continue
        if not actual_json.exists():
            extra.append(f"- {step}: missing counters JSON")
            continue
        expected, expected_messages = parse_sysout(expected_txt)
        actual, actual_messages = candidate_step(actual_json)
        for label in sorted(set(expected) | set(actual)):
            checked += 1
            left = expected.get(label)
            right = actual.get(label)
            if left is None or right is None or left != right:
                rows.append(f"| {step} | {label} | {left} | {right} |")
        if expected_messages != actual_messages:
            rows.append(
                f"| {step} | (messages) | {expected_messages} | {actual_messages} |"
            )
    expected_rc = json.loads((expected_root / "rc.json").read_text())
    rc_path = candidate / "rc.json"
    actual_rc = json.loads(rc_path.read_text()) if rc_path.exists() else None
    checked += 1
    if expected_rc != actual_rc:
        rows.append(
            f"| RC | rc.json | {json.dumps(expected_rc, sort_keys=True)} | "
            f"{json.dumps(actual_rc, sort_keys=True)} |"
        )
    diffs = len(rows) + len(extra)
    status = "FAIL" if diffs else "PASS"
    lines = [
        f"# Counters parity: xferfee / {case} — {status}",
        "",
        "| Step | Counter | Expected | Actual |",
        "|---|---|---|---|",
        *rows,
        *extra,
        "",
        f"COUNTERS: {status} ({checked} checked, {diffs} differences)",
    ]
    return "\n".join(lines) + "\n", 1 if diffs else 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chain", default="xferfee")
    parser.add_argument("--case")
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--candidate", type=Path, required=True,
                        help="candidate dir, or with --all a root holding <case>/ dirs")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.chain != "xferfee":
        parser.error("only the xferfee chain is supported")
    if not args.all and not args.case:
        parser.error("--case is required unless --all is used")
    cases = CASES if args.all else (args.case,)
    reports = []
    overall = 0
    for case in cases:
        candidate = args.candidate / case if args.all else args.candidate
        report, rc = compare_case(case, candidate)
        reports.append(report)
        overall = max(overall, rc)
    text = "\n".join(reports)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text)
    print(text, end="")
    return overall


if __name__ == "__main__":
    sys.exit(main())
