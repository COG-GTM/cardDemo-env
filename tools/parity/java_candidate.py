#!/usr/bin/env python3
"""Replay one xferfee fixture through java/parity-replay and diff it like compare.py.

work/parity-java/<case>/
  input/      fixture decoded to JSON lines (recorded XFER.EXTRACT = STEP010 upstream stub)
  out/        raw Java output (datasets/<DSN>.jsonl, db2_after/<TABLE>.jsonl, rc.json)
  candidate/  fixed-width datasets / CSV dumps for compare.py
  report.md   compare.py report (scoped to --only when given)
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import subprocess
import sys
from decimal import Decimal
from pathlib import Path

import compare
from copybook import decode_record, encode_record, parse_copybook, record_length


ROOT = Path(__file__).resolve().parents[2]
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
DEFAULT_JAR = ROOT / "java" / "parity-replay" / "target" / "parity-replay.jar"
EXTRACT_DSN = "AWS.M2.CARDDEMO.XFER.EXTRACT"
FEES_DSN = "AWS.M2.CARDDEMO.XFER.FEES"
GENERATION = ".G0001V00"
LEDGER_COLUMNS = [
    "tran_id", "tran_dt", "src_acct_id", "tgt_acct_id",
    "book_id", "tran_amt", "fee_amt", "cap_applied",
]
PARM_COLUMNS = ["book_id", "fee_pct", "fee_cap", "eff_dt", "exp_dt"]


def records(path: Path, copybook: str) -> list[dict]:
    length = record_length(parse_copybook(copybook))
    data = path.read_bytes()
    return [
        decode_record(copybook, data[offset:offset + length])
        for offset in range(0, len(data), length)
    ]


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row) + "\n" for row in rows))


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def prepare_input(case: str, target: Path) -> None:
    fixture = CHAIN_ROOT / case
    extract = compare.dataset_file(fixture / "expected" / "datasets", EXTRACT_DSN)
    transfers = []
    for row in records(extract, "CVXFR01Y") if extract else []:
        transfers.append({
            "tranId": row["XFR-TRAN-ID"],
            "tranDate": row["XFR-TRAN-DT"],
            "sourceAccountId": int(row["XFR-SRC-ACCT-ID"]),
            "targetAccountId": int(row["XFR-TGT-ACCT-ID"]),
            "bookId": row["XFR-BOOK-ID"].strip(),
            "amount": f"{row['XFR-TRAN-AMT']:.2f}",
            "cardNumber": row["XFR-CARD-NUM"],
        })
    write_jsonl(target / "recorded" / "XFER.EXTRACT.jsonl", transfers)
    write_jsonl(target / "db2_before" / "CTL_XFER_PARM.jsonl", [
        {
            "bookId": row["book_id"].strip(),
            "feePct": row["fee_pct"].strip(),
            "feeCap": row["fee_cap"].strip(),
            "effectiveDate": row["eff_dt"].strip(),
            "expiryDate": row["exp_dt"].strip(),
        }
        for row in read_csv(fixture / "db2_before" / "CTL_XFER_PARM.csv")
    ])
    write_jsonl(target / "db2_before" / "XFER_FEE_LEDGER.jsonl", [
        {
            "tranId": row["tran_id"].strip(),
            "tranDate": row["tran_dt"].strip(),
            "sourceAccountId": int(row["src_acct_id"]),
            "targetAccountId": int(row["tgt_acct_id"]),
            "bookId": row["book_id"].strip(),
            "amount": row["tran_amt"].strip(),
            "feeAmount": row["fee_amt"].strip(),
            "capApplied": row["cap_applied"].strip() == "Y",
        }
        for row in read_csv(fixture / "db2_before" / "XFER_FEE_LEDGER.csv")
    ])


def run_java(jar: Path, case: str, input_dir: Path, out: Path) -> None:
    java = "java"
    if os.environ.get("JAVA_HOME"):
        java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
    subprocess.run(
        [java, "-jar", str(jar), f"--case={case}", f"--in={input_dir}", f"--out={out}"],
        cwd=ROOT,
        check=True,
        stdout=subprocess.DEVNULL,
    )


def flag(value: bool) -> str:
    return "Y" if value else "N"


def encode_candidate(out: Path, candidate: Path) -> None:
    datasets = candidate / "datasets"
    datasets.mkdir(parents=True, exist_ok=True)
    fees_jsonl = out / "datasets" / f"{FEES_DSN}.jsonl"
    if fees_jsonl.exists():
        rows = [
            {
                "XFE-TRAN-ID": row["tranId"],
                "XFE-TRAN-DT": row["tranDate"],
                "XFE-SRC-ACCT-ID": row["sourceAccountId"],
                "XFE-TGT-ACCT-ID": row["targetAccountId"],
                "XFE-BOOK-ID": row["bookId"],
                "XFE-TRAN-AMT": Decimal(row["amount"]),
                "XFE-FEE-PCT": Decimal(row["feePct"]),
                "XFE-FEE-AMT": Decimal(row["feeAmount"]),
                "XFE-CAP-APPLIED": flag(row["capApplied"]),
                "XFE-RULE-EFF-DT": row["ruleEffectiveDate"],
            }
            for row in read_jsonl(fees_jsonl)
        ]
        (datasets / (FEES_DSN + GENERATION)).write_bytes(
            b"".join(encode_record("CVXFR02Y", row) for row in rows)
        )
    db2_after = candidate / "db2_after"
    db2_after.mkdir(parents=True, exist_ok=True)
    ledger = out / "db2_after" / "XFER_FEE_LEDGER.jsonl"
    if ledger.exists():
        with (db2_after / "XFER_FEE_LEDGER.csv").open("w", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow(LEDGER_COLUMNS)
            for row in read_jsonl(ledger):
                writer.writerow([
                    row["tranId"], row["tranDate"], row["sourceAccountId"],
                    row["targetAccountId"], row["bookId"].ljust(10), row["amount"],
                    row["feeAmount"], flag(row["capApplied"]),
                ])
    parm = out / "db2_after" / "CTL_XFER_PARM.jsonl"
    if parm.exists():
        with (db2_after / "CTL_XFER_PARM.csv").open("w", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow(PARM_COLUMNS)
            for row in read_jsonl(parm):
                writer.writerow([
                    row["bookId"].ljust(10), row["feePct"], row["feeCap"],
                    row["effectiveDate"], row["expiryDate"],
                ])
    (candidate / "sysout").mkdir(exist_ok=True)
    rc = out / "rc.json"
    (candidate / "rc.json").write_text(rc.read_text() if rc.exists() else "{}\n")


def scoped_compare(case: str, candidate: Path, only: list[str]) -> tuple[str, int]:
    expected_root = CHAIN_ROOT / case / "expected"
    metadata = json.loads((CHAIN_ROOT / case / "case.json").read_text())
    outputs = {output["dsn"]: output for output in metadata["outputs"]}
    tables = {table["table"]: table for table in metadata["db2"]}
    unknown = [name for name in only if name not in outputs and name not in tables]
    if unknown:
        raise SystemExit(f"--only: {', '.join(unknown)} not in {case}/case.json")
    lines: list[str] = []
    field_diffs = record_diffs = 0
    for name in only:
        if name in outputs:
            expected = compare.dataset_file(expected_root / "datasets", name)
            actual = compare.dataset_file(candidate / "datasets", name)
            if expected is None and actual is None:
                continue
            if expected is None or actual is None:
                lines.append(f"- {name}: {'extra' if expected is None else 'missing'} dataset")
                record_diffs += 1
                continue
            fields, recs = compare.append_dataset_diffs(lines, expected, actual, outputs[name])
        else:
            if not (candidate / "db2_after" / f"{name}.csv").exists():
                lines.append(f"- {name}: missing table dump")
                record_diffs += 1
                continue
            fields, recs = compare.append_db_diffs(
                lines, expected_root / "db2_after", candidate / "db2_after", tables[name]
            )
        field_diffs += fields
        record_diffs += recs
    rc = 1 if field_diffs or record_diffs else 0
    verdict = (
        f"PARITY: FAIL ({field_diffs} field differences, {record_diffs} record differences)"
        if rc else "PARITY: PASS"
    )
    field_lines = [line for line in lines if line.startswith("| ")]
    record_lines = [line for line in lines if line.startswith("- ")]
    report = "\n".join([
        f"# Java parity: xferfee / {case} (scope: {', '.join(only)}) — {'FAIL' if rc else 'PASS'}",
        "",
        "| Dataset/Table | Record key | Field | Expected | Actual |",
        "|---|---|---|---|---|",
        *field_lines,
        "",
        "## Missing/extra records",
        *(record_lines or ["(none)"]),
        "",
        verdict,
    ]) + "\n"
    (candidate.parent / "report.md").write_text(report)
    return report, rc


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", required=True)
    parser.add_argument("--only", default="", help="comma-separated DSNs/tables from case.json")
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--work", type=Path, default=ROOT / "work" / "parity-java")
    args = parser.parse_args()
    root = args.work / args.case
    shutil.rmtree(root, ignore_errors=True)
    prepare_input(args.case, root / "input")
    run_java(args.jar, args.case, root / "input", root / "out")
    encode_candidate(root / "out", root / "candidate")
    only = [name.strip() for name in args.only.split(",") if name.strip()]
    if only:
        report, rc = scoped_compare(args.case, root / "candidate", only)
    else:
        report, rc = compare.compare_case(args.case, root / "candidate")
    print(report, end="")
    return rc


if __name__ == "__main__":
    sys.exit(main())
