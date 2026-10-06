#!/usr/bin/env python3
"""Replay STEP030 (CBXFR03C) through the Java reconciliation-service.

For each case the COBOL-recorded STEP020 output (XFER.FEES) and STEP020 RC
are fed to the Java replay; the candidate's RECON.RPT, STEP030 SYSOUT and
STEP030 RC are then diffed against the recorded expected outputs.
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
from pathlib import Path

from compare import CASES, CHAIN_ROOT, ROOT, compare_case, dataset_file

FEES_DSN = "AWS.M2.CARDDEMO.XFER.FEES"
REPORT_DSN = "AWS.M2.CARDDEMO.XFER.RECON.RPT"
SCOPE = {REPORT_DSN, "STEP030"}
DEFAULT_JAVA = (
    "java -cp java/reconciliation-service/target/classes:"
    "java/contracts/target/classes "
    "com.carddemo.xferfee.recon.ReconReplay"
)


def replay(case: str, java: list[str]) -> Path:
    expected = CHAIN_ROOT / case / "expected"
    metadata = json.loads((CHAIN_ROOT / case / "case.json").read_text())
    posting_rc = json.loads((expected / "rc.json").read_text())["steps"]
    fees = dataset_file(expected / "datasets", FEES_DSN)
    candidate = ROOT / "work" / "parity" / case / "java-recon"
    command = java + [
        "--business-date", metadata["run_date"],
        "--posting-rc", str(posting_rc.get("STEP020", 0)),
        "--out", str(candidate),
    ]
    if fees is not None:
        command += ["--fees", str(fees)]
    subprocess.run(command, cwd=ROOT, check=True)
    return candidate


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    java = shlex.split(os.environ.get("RECON_JAVA", DEFAULT_JAVA))
    cases = (args.case,) if args.case else CASES
    reports = []
    overall = 0
    for case in cases:
        report, rc = compare_case(case, replay(case, java), SCOPE)
        reports.append(report)
        overall = max(overall, rc)
    text = "\n".join(reports)
    destination = args.report or ROOT / "work" / "parity" / "java-recon.md"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(text)
    print(text, end="")
    passed = sum("PARITY: PASS" in report for report in reports)
    print(f"\nJava recon parity: {passed}/{len(reports)} cases PASS")
    return overall


if __name__ == "__main__":
    sys.exit(main())
