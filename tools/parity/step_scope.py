#!/usr/bin/env python3
"""Count the compare.py differences that belong to one XFERFEEP step.

While the Java port lands step by step, a case report also lists the outputs of steps whose
module does not exist yet. This keeps only the datasets, tables, SYSOUT and return code
written by --step and exits non-zero if any of those differ.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


STEP_OUTPUTS = {
    "STEP010": {"AWS.M2.CARDDEMO.XFER.EXTRACT", "SYSOUT/STEP010.txt"},
    "STEP020": {"AWS.M2.CARDDEMO.ACCTDATA.XFER", "AWS.M2.CARDDEMO.XFER.FEES",
                "XFER_FEE_LEDGER", "CTL_XFER_PARM", "SYSOUT/STEP020.txt"},
    "STEP030": {"AWS.M2.CARDDEMO.XFER.RECON.RPT", "SYSOUT/STEP030.txt"},
}
RC_LINE = re.compile(r"- RC: expected `(?P<expected>.*)`, actual `(?P<actual>.*)`")


def owner(name: str, outputs: set[str]) -> bool:
    return any(name == output or name.startswith(output + " ") for output in outputs)


def scoped_diffs(report: str, step: str) -> list[str]:
    outputs = STEP_OUTPUTS[step]
    diffs = []
    for line in report.splitlines():
        if line.startswith("| ") and not line.startswith("| Dataset/Table"):
            if owner(line.split("|")[1].strip(), outputs):
                diffs.append(line)
        elif (match := RC_LINE.match(line)):
            expected = json.loads(match["expected"])["steps"].get(step)
            actual = json.loads(match["actual"])["steps"].get(step)
            if expected != actual:
                diffs.append(f"- RC {step}: expected {expected}, actual {actual}")
        elif line.startswith("- ") and owner(line[2:].split(":")[0].strip(), outputs):
            diffs.append(line)
    return diffs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--step", required=True, choices=sorted(STEP_OUTPUTS))
    parser.add_argument("reports", nargs="+", type=Path, help="work/parity-java/<case>/report.md")
    args = parser.parse_args()
    failed = False
    for path in args.reports:
        diffs = scoped_diffs(path.read_text(), args.step)
        print(f"{path.parent.name}: {args.step} {len(diffs)} diffs")
        for diff in diffs:
            print(f"  {diff}")
        failed |= bool(diffs)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
