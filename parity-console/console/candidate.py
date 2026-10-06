"""Package a live run in the recorder's candidate layout and diff it with tools/parity/compare.py.

The same `compare_case` that backs `make parity` / `make parity-naive` judges the
console's output, so the console's per-transaction verdicts can be cross-checked
against the recorded batch baselines in fixtures/xferfee/<case>/expected.
"""

from __future__ import annotations

import csv
import json
import shutil
from decimal import Decimal
from pathlib import Path
from typing import Any

from common import ROOT, WORK, encode_record

import compare as parity_compare
import naive_ref

HLQ = "AWS.M2.CARDDEMO"


def write_candidate(
    out: Path,
    records_read: int,
    unmatched: int,
    extracts: list[dict[str, Any]],
    accounts: list[dict[str, Any]],
    fees: list[dict[str, Any]],
    rules: list[dict[str, str]],
    steps: dict[str, int],
) -> Path:
    if out.exists():
        shutil.rmtree(out)
    datasets = out / "datasets"
    datasets.mkdir(parents=True)
    (datasets / f"{HLQ}.XFER.EXTRACT.G0001V00").write_bytes(
        b"".join(encode_record("CVXFR01Y", row) for row in extracts))
    (datasets / f"{HLQ}.ACCTDATA.XFER.G0001V00").write_bytes(
        b"".join(encode_record("CVACT01Y", row) for row in accounts))
    (datasets / f"{HLQ}.XFER.FEES.G0001V00").write_bytes(
        b"".join(encode_record("CVXFR02Y", row) for row in fees))
    naive_ref.write_recon(out, fees)

    db2 = out / "db2_after"
    db2.mkdir()
    with (db2 / "XFER_FEE_LEDGER.csv").open("w", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["tran_id", "tran_dt", "src_acct_id", "tgt_acct_id", "book_id",
                         "tran_amt", "fee_amt", "cap_applied"])
        for fee in fees:
            writer.writerow([fee["XFE-TRAN-ID"], fee["XFE-TRAN-DT"], fee["XFE-SRC-ACCT-ID"],
                             fee["XFE-TGT-ACCT-ID"], str(fee["XFE-BOOK-ID"]).ljust(10),
                             f"{fee['XFE-TRAN-AMT']:.2f}", f"{fee['XFE-FEE-AMT']:.2f}",
                             fee["XFE-CAP-APPLIED"]])
    with (db2 / "CTL_XFER_PARM.csv").open("w", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["book_id", "fee_pct", "fee_cap", "eff_dt", "exp_dt"])
        for rule in rules:
            writer.writerow([rule["bookId"].ljust(10), f"{Decimal(rule['feePct']):.6f}",
                             f"{Decimal(rule['feeCap']):.2f}", rule["effDt"], rule["expDt"]])

    total = sum((Decimal(str(fee["XFE-FEE-AMT"])) for fee in fees), Decimal("0"))
    cents = int(total * 100)
    sysout = out / "sysout"
    sysout.mkdir()
    (sysout / "STEP010.txt").write_text(
        f"CBXFR01C: RECORDS READ {records_read:09d}\n"
        f"CBXFR01C: TRANSFERS SELECTED {len(extracts):09d}\n"
        f"CBXFR01C: UNMATCHED CARDS {unmatched:09d}\n")
    if "STEP020" in steps:
        (sysout / "STEP020.txt").write_text(
            f"XFERFEE: TRANSFERS POSTED {len(fees):09d}\n"
            f"XFERFEE: TOTAL FEES +{cents:011d}\n")
    if "STEP030" in steps:
        (sysout / "STEP030.txt").write_text(
            f"CBXFR03C: GRAND TOTAL FEE +{cents:011d}\n" if fees
            else "CBXFR03C: NO FEE RECORDS\n")
    (out / "rc.json").write_text(json.dumps(
        {"steps": steps, "maxcc": max(steps.values(), default=0)}, indent=2) + "\n")
    return out


def judge(case: str, candidate: Path) -> dict[str, Any]:
    report, rc = parity_compare.compare_case(case, candidate)
    return summarize(report, rc, candidate.parent / "report.md")


def judge_naive(case: str) -> dict[str, Any]:
    """Exactly what `make parity-naive CASE=<case>` runs, for side-by-side comparison."""
    out = WORK / "candidate" / case / "naive" / "candidate"
    if out.exists():
        shutil.rmtree(out)
    naive_ref.record(case, out)
    return judge(case, out)


def summarize(report: str, rc: int, path: Path) -> dict[str, Any]:
    lines = report.splitlines()
    diff_rows = [line for line in lines if line.startswith("| ") and not line.startswith("| Dataset")]
    return {
        "pass": rc == 0,
        "verdict": lines[-1] if lines else "",
        "diffRows": diff_rows,
        "report": str(path.relative_to(ROOT)),
    }
