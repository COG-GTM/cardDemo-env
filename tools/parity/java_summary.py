#!/usr/bin/env python3
"""Summarise per-case event-mode parity reports into work/parity-java/summary.md."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "work" / "parity-java"
VERDICT = re.compile(
    r"PARITY: (PASS|FAIL)"
    r"(?: \((\d+) field differences, (\d+) record differences\))?"
)


def main(cases: list[str]) -> int:
    lines = [
        "# Java event-mode parity (Kafka + Postgres)",
        "",
        "| Case | Result | Field diffs | Record diffs |",
        "|---|---|---|---|",
    ]
    failed = 0
    for case in cases:
        report = WORK / case / "report.md"
        match = VERDICT.search(report.read_text()) if report.exists() else None
        if match is None:
            lines.append(f"| {case} | ERROR | - | - |")
            failed += 1
            continue
        verdict, fields, records = match.groups(default="0")
        failed += verdict != "PASS"
        lines.append(f"| {case} | {verdict} | {fields} | {records} |")
    lines += ["", f"{len(cases) - failed}/{len(cases)} cases at zero diffs"]
    WORK.mkdir(parents=True, exist_ok=True)
    (WORK / "summary.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
