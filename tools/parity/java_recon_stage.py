#!/usr/bin/env python3
"""Replay STEP030 (CBXFR03C) alone through the Java reconciliation-service.

Until the Java STEP010/STEP020 stages exist, `make parity-java` stops before
STEP030. This script exercises reconciliation on its own: the recorded
STEP020 outputs (XFER.FEES and the STEP020 RC) are its inputs, and the
candidate it builds is the COBOL recording with every STEP030 artifact
(XFER.RECON.RPT, SYSOUT/STEP030.txt, rc STEP030) replaced by Java output.
compare.py then diffs the full case, so any difference is a STEP030 one.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

from java_candidate import CHAIN_ROOT, DEFAULT_JAR, HLQ, money, records


LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher"
REPLAY_MAIN = "com.carddemo.xferfee.reconciliation.parity.ReconReplay"
RECON_DSN = f"{HLQ}.XFER.RECON.RPT"
FEES_DSN = f"{HLQ}.XFER.FEES"


def fee_to_json(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "tranId": row["XFE-TRAN-ID"].rstrip(),
        "tranDate": row["XFE-TRAN-DT"],
        "sourceAccountId": int(row["XFE-SRC-ACCT-ID"]),
        "targetAccountId": int(row["XFE-TGT-ACCT-ID"]),
        "bookId": row["XFE-BOOK-ID"].rstrip(),
        "amount": money(row["XFE-TRAN-AMT"]),
        "feePct": money(row["XFE-FEE-PCT"], 6),
        "feeAmount": money(row["XFE-FEE-AMT"]),
        "capApplied": row["XFE-CAP-APPLIED"] == "Y",
        "ruleEffectiveDate": row["XFE-RULE-EFF-DT"],
    }


def latest(directory: Path, dsn: str) -> Path | None:
    found = sorted(directory.glob(dsn + ".G*V00"))
    return found[-1] if found else None


def build_candidate(expected: Path, replay_out: Path, candidate: Path) -> None:
    shutil.copytree(expected, candidate)
    for stale in candidate.glob("datasets/" + RECON_DSN + ".G*V00"):
        stale.unlink()
    (candidate / "sysout" / "STEP030.txt").unlink(missing_ok=True)

    report = replay_out / "XFER.RECON.RPT.txt"
    if report.exists():
        shutil.copyfile(report, candidate / "datasets" / f"{RECON_DSN}.G0001V00")
    sysout = replay_out / "sysout" / "STEP030.txt"
    if sysout.exists():
        shutil.copyfile(sysout, candidate / "sysout" / "STEP030.txt")

    rc = json.loads((expected / "rc.json").read_text())
    steps = {step: code for step, code in rc["steps"].items() if step != "STEP030"}
    steps.update(json.loads((replay_out / "rc.json").read_text())["steps"])
    (candidate / "rc.json").write_text(json.dumps(
        {"steps": steps, "maxcc": max(steps.values(), default=0)}, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--case", required=True)
    parser.add_argument("--out", required=True, type=Path,
                        help="work directory; the candidate lands in OUT/candidate")
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    args = parser.parse_args()

    case_root = CHAIN_ROOT / args.case
    if not (case_root / "case.json").exists():
        parser.error(f"unknown case: {args.case}")
    if not args.jar.exists():
        parser.error(f"{args.jar} not found; build with mvn -f java/pom.xml package")
    if args.out.exists():
        shutil.rmtree(args.out)
    args.out.mkdir(parents=True)
    expected = case_root / "expected"

    fees_path = latest(expected / "datasets", FEES_DSN)
    fees = records(fees_path, "CVXFR02Y") if fees_path else []
    fees_jsonl = args.out / "XFER.FEES.jsonl"
    fees_jsonl.write_text("".join(json.dumps(fee_to_json(row)) + "\n" for row in fees))
    posting_rc = json.loads((expected / "rc.json").read_text())["steps"]["STEP020"]

    replay_out = args.out / "replay-out"
    subprocess.run(
        ["java", f"-Dloader.main={REPLAY_MAIN}", "-cp", str(args.jar), LAUNCHER,
         "--fees", str(fees_jsonl), "--posting-rc", str(posting_rc), "--out", str(replay_out)],
        check=True,
    )
    build_candidate(expected, replay_out, args.out / "candidate")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
