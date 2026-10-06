#!/usr/bin/env python3
"""Module-scope parity for fee-schedule-service (BR-06).

Checks parity-replay's raw output dir (``work/parity-java/<case>/out``) against the
COBOL recording for one case:
  1. db2_after/CTL_XFER_PARM.csv is byte-equal to the recorded snapshot.
  2. Every transfer's fee-rule lookup returns the rate and rule effective date
     XFERFEE wrote to XFER.FEES (XFE-FEE-PCT, XFE-RULE-EFF-DT).
"""

from __future__ import annotations

import argparse
import csv
import sys
from decimal import Decimal
from pathlib import Path

from compare import dataset_file
from copybook import decode_record, parse_copybook, record_length


ROOT = Path(__file__).resolve().parents[2]
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
FEES_DSN = "AWS.M2.CARDDEMO.XFER.FEES"


def expected_fees(case: str) -> dict[str, dict[str, object]]:
    path = dataset_file(CHAIN_ROOT / case / "expected" / "datasets", FEES_DSN)
    if path is None:
        return {}
    length = record_length(parse_copybook("CVXFR02Y"))
    data = path.read_bytes()
    fees = {}
    for offset in range(0, len(data), length):
        values = decode_record("CVXFR02Y", data[offset:offset + length])
        fees[str(values["XFE-TRAN-ID"]).strip()] = values
    return fees


def check(case: str, candidate: Path) -> tuple[list[str], int, int]:
    lines: list[str] = []
    diffs = 0
    table = "CTL_XFER_PARM.csv"
    expected_table = CHAIN_ROOT / case / "expected" / "db2_after" / table
    actual_table = candidate / "db2_after" / table
    if not actual_table.exists():
        lines.append("| CTL_XFER_PARM | (file) | - | present | missing |")
        diffs += 1
    elif expected_table.read_bytes() != actual_table.read_bytes():
        expected_rows = expected_table.read_text().splitlines()
        actual_rows = actual_table.read_text().splitlines()
        for index in range(max(len(expected_rows), len(actual_rows))):
            left = expected_rows[index] if index < len(expected_rows) else ""
            right = actual_rows[index] if index < len(actual_rows) else ""
            if left != right:
                lines.append(f"| CTL_XFER_PARM | line {index + 1} | bytes | {left} | {right} |")
                diffs += 1

    fees = expected_fees(case)
    resolution_path = candidate / "fee_resolution.csv"
    resolved: dict[str, dict[str, str]] = {}
    if resolution_path.exists():
        with resolution_path.open(newline="") as stream:
            resolved = {row["tran_id"]: row for row in csv.DictReader(stream)}
    elif fees:
        lines.append("| fee_resolution | (file) | - | present | missing |")
        diffs += 1
    for tran_id, fee in sorted(fees.items()):
        row = resolved.get(tran_id)
        if row is None:
            lines.append(f"| XFER.FEES | {tran_id} | lookup | posted | not resolved |")
            diffs += 1
            continue
        if row["status"] != "FOUND":
            lines.append(f"| XFER.FEES | {tran_id} | status | FOUND | {row['status']} |")
            diffs += 1
            continue
        if Decimal(row["fee_pct"]) != fee["XFE-FEE-PCT"]:
            lines.append(
                f"| XFER.FEES | {tran_id} | XFE-FEE-PCT | "
                f"{fee['XFE-FEE-PCT']:.6f} | {row['fee_pct']} |"
            )
            diffs += 1
        if row["eff_dt"] != str(fee["XFE-RULE-EFF-DT"]).strip():
            lines.append(
                f"| XFER.FEES | {tran_id} | XFE-RULE-EFF-DT | "
                f"{str(fee['XFE-RULE-EFF-DT']).strip()} | {row['eff_dt']} |"
            )
            diffs += 1
    for tran_id in sorted(set(resolved) - set(fees)):
        lines.append(f"| XFER.FEES | {tran_id} | lookup | (not posted) | {resolved[tran_id]['status']} |")
        diffs += 1
    return lines, diffs, len(fees)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", required=True)
    parser.add_argument("--candidate", required=True, type=Path)
    args = parser.parse_args()
    lines, diffs, lookups = check(args.case, args.candidate)
    verdict = "PASS" if diffs == 0 else f"FAIL ({diffs} differences)"
    print(f"# fee-schedule-service parity: xferfee / {args.case} — {verdict.split()[0]}")
    print()
    print(f"CTL_XFER_PARM byte-equal + BR-06 lookups checked: {lookups}")
    print()
    print("| Table/Dataset | Record key | Field | Expected | Actual |")
    print("|---|---|---|---|---|")
    for line in lines:
        print(line)
    print()
    print(f"FEE-SCHEDULE PARITY: {verdict}")
    return 0 if diffs == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
