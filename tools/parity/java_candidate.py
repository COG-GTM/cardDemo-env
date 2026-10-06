#!/usr/bin/env python3
"""Run the Java parity-replay for one case and encode its output as a compare.py candidate.

WORK/input/      fixture decoded to contract-shaped JSON-lines (+ recorded/ upstream stubs)
WORK/out/        raw Java output: datasets/<DSN>.jsonl|.txt, db2_after/*.csv, sysout/, rc.json
WORK/candidate/  fixed-width datasets, db2_after/, sysout/, rc.json for compare.py --candidate
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import subprocess
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable

from copybook import decode_record, encode_record, parse_copybook, record_length


ROOT = Path(__file__).resolve().parents[2]
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
DEFAULT_JAR = ROOT / "java" / "parity-replay" / "target" / "parity-replay.jar"
HLQ = "AWS.M2.CARDDEMO."


def money(value: Any) -> str:
    return format(Decimal(value), ".2f")


def records(path: Path, copybook: str) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    length = record_length(parse_copybook(copybook))
    data = path.read_bytes()
    return [decode_record(copybook, data[i:i + length]) for i in range(0, len(data), length)]


def account_to_json(r: dict[str, Any]) -> dict[str, Any]:
    return {
        "accountId": int(r["ACCT-ID"]),
        "activeStatus": r["ACCT-ACTIVE-STATUS"],
        "currentBalance": money(r["ACCT-CURR-BAL"]),
        "creditLimit": money(r["ACCT-CREDIT-LIMIT"]),
        "cashCreditLimit": money(r["ACCT-CASH-CREDIT-LIMIT"]),
        "openDate": r["ACCT-OPEN-DATE"],
        "expirationDate": r["ACCT-EXPIRAION-DATE"],
        "reissueDate": r["ACCT-REISSUE-DATE"],
        "currentCycleCredit": money(r["ACCT-CURR-CYC-CREDIT"]),
        "currentCycleDebit": money(r["ACCT-CURR-CYC-DEBIT"]),
        "addressZip": r["ACCT-ADDR-ZIP"],
        "groupId": r["ACCT-GROUP-ID"],
    }


def account_from_json(r: dict[str, Any]) -> dict[str, Any]:
    return {
        "ACCT-ID": r["accountId"],
        "ACCT-ACTIVE-STATUS": r["activeStatus"],
        "ACCT-CURR-BAL": Decimal(r["currentBalance"]),
        "ACCT-CREDIT-LIMIT": Decimal(r["creditLimit"]),
        "ACCT-CASH-CREDIT-LIMIT": Decimal(r["cashCreditLimit"]),
        "ACCT-OPEN-DATE": r["openDate"],
        "ACCT-EXPIRAION-DATE": r["expirationDate"],
        "ACCT-REISSUE-DATE": r["reissueDate"],
        "ACCT-CURR-CYC-CREDIT": Decimal(r["currentCycleCredit"]),
        "ACCT-CURR-CYC-DEBIT": Decimal(r["currentCycleDebit"]),
        "ACCT-ADDR-ZIP": r["addressZip"],
        "ACCT-GROUP-ID": r["groupId"],
    }


def transaction_to_json(r: dict[str, Any]) -> dict[str, Any]:
    return {
        "tranId": r["TRAN-ID"],
        "typeCode": r["TRAN-TYPE-CD"],
        "categoryCode": int(r["TRAN-CAT-CD"]),
        "source": r["TRAN-SOURCE"],
        "description": r["TRAN-DESC"],
        "amount": money(r["TRAN-AMT"]),
        "merchantId": int(r["TRAN-MERCHANT-ID"]),
        "merchantName": r["TRAN-MERCHANT-NAME"],
        "merchantCity": r["TRAN-MERCHANT-CITY"],
        "merchantZip": r["TRAN-MERCHANT-ZIP"],
        "cardNumber": r["TRAN-CARD-NUM"],
        "originTimestamp": r["TRAN-ORIG-TS"],
        "processTimestamp": r["TRAN-PROC-TS"],
    }


def xref_to_json(r: dict[str, Any]) -> dict[str, Any]:
    return {
        "cardNumber": r["XREF-CARD-NUM"],
        "customerId": int(r["XREF-CUST-ID"]),
        "accountId": int(r["XREF-ACCT-ID"]),
    }


def extract_to_json(r: dict[str, Any]) -> dict[str, Any]:
    return {
        "tranId": r["XFR-TRAN-ID"],
        "tranDate": r["XFR-TRAN-DT"],
        "sourceAccountId": int(r["XFR-SRC-ACCT-ID"]),
        "targetAccountId": int(r["XFR-TGT-ACCT-ID"]),
        "bookId": r["XFR-BOOK-ID"].rstrip(),
        "amount": money(r["XFR-TRAN-AMT"]),
        "cardNumber": r["XFR-CARD-NUM"],
    }


def extract_from_json(r: dict[str, Any]) -> dict[str, Any]:
    return {
        "XFR-TRAN-ID": r["tranId"],
        "XFR-TRAN-DT": r["tranDate"],
        "XFR-SRC-ACCT-ID": r["sourceAccountId"],
        "XFR-TGT-ACCT-ID": r["targetAccountId"],
        "XFR-BOOK-ID": r["bookId"],
        "XFR-TRAN-AMT": Decimal(r["amount"]),
        "XFR-CARD-NUM": r["cardNumber"],
    }


def fee_to_json(r: dict[str, Any]) -> dict[str, Any]:
    return {
        "tranId": r["XFE-TRAN-ID"],
        "tranDate": r["XFE-TRAN-DT"],
        "sourceAccountId": int(r["XFE-SRC-ACCT-ID"]),
        "targetAccountId": int(r["XFE-TGT-ACCT-ID"]),
        "bookId": r["XFE-BOOK-ID"].rstrip(),
        "amount": money(r["XFE-TRAN-AMT"]),
        "feePct": format(Decimal(r["XFE-FEE-PCT"]), ".6f"),
        "feeAmount": money(r["XFE-FEE-AMT"]),
        "capApplied": r["XFE-CAP-APPLIED"] == "Y",
        "ruleEffectiveDate": r["XFE-RULE-EFF-DT"],
    }


def fee_from_json(r: dict[str, Any]) -> dict[str, Any]:
    return {
        "XFE-TRAN-ID": r["tranId"],
        "XFE-TRAN-DT": r["tranDate"],
        "XFE-SRC-ACCT-ID": r["sourceAccountId"],
        "XFE-TGT-ACCT-ID": r["targetAccountId"],
        "XFE-BOOK-ID": r["bookId"],
        "XFE-TRAN-AMT": Decimal(r["amount"]),
        "XFE-FEE-PCT": Decimal(r["feePct"]),
        "XFE-FEE-AMT": Decimal(r["feeAmount"]),
        "XFE-CAP-APPLIED": "Y" if r["capApplied"] else "N",
        "XFE-RULE-EFF-DT": r["ruleEffectiveDate"],
    }


ENCODED: dict[str, tuple[str, Callable[[dict[str, Any]], dict[str, Any]]]] = {
    "XFER.EXTRACT": ("CVXFR01Y", extract_from_json),
    "ACCTDATA.XFER": ("CVACT01Y", account_from_json),
    "XFER.FEES": ("CVXFR02Y", fee_from_json),
}


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row) + "\n" for row in rows))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def prepare_inputs(case_root: Path, work_in: Path) -> None:
    inputs = case_root / "input"
    write_jsonl(work_in / "DALYTRAN.jsonl",
                [transaction_to_json(r) for r in records(inputs / "DALYTRAN.PS", "CVTRA05Y")])
    write_jsonl(work_in / "CARDXREF.jsonl",
                [xref_to_json(r) for r in records(inputs / "CARDXREF.PS", "CVACT03Y")])
    write_jsonl(work_in / "ACCTDATA.jsonl",
                [account_to_json(r) for r in records(inputs / "ACCTDATA.PS", "CVACT01Y")])
    db2 = case_root / "db2_before"
    write_jsonl(work_in / "CTL_XFER_PARM.jsonl", [
        {"bookId": r["book_id"].rstrip(), "feePct": r["fee_pct"], "feeCap": r["fee_cap"],
         "effectiveDate": r["eff_dt"], "expiryDate": r["exp_dt"]}
        for r in read_csv(db2 / "CTL_XFER_PARM.csv")
    ])
    write_jsonl(work_in / "XFER_FEE_LEDGER.jsonl", [
        {"tranId": r["tran_id"].rstrip(), "tranDate": r["tran_dt"],
         "sourceAccountId": int(r["src_acct_id"]), "targetAccountId": int(r["tgt_acct_id"]),
         "bookId": r["book_id"].rstrip(), "amount": r["tran_amt"], "feeAmount": r["fee_amt"],
         "capApplied": r["cap_applied"] == "Y"}
        for r in read_csv(db2 / "XFER_FEE_LEDGER.csv")
    ])
    # Upstream stubs: recorded COBOL outputs, used only as the next step's input.
    datasets = case_root / "expected" / "datasets"
    write_jsonl(work_in / "recorded" / "XFER.EXTRACT.jsonl", [
        extract_to_json(r)
        for r in records(datasets / f"{HLQ}XFER.EXTRACT.G0001V00", "CVXFR01Y")
    ])
    write_jsonl(work_in / "recorded" / "XFER.FEES.jsonl", [
        fee_to_json(r) for r in records(datasets / f"{HLQ}XFER.FEES.G0001V00", "CVXFR02Y")
    ])


def encode_candidate(out: Path, candidate: Path) -> None:
    datasets = candidate / "datasets"
    datasets.mkdir(parents=True)
    for name, (copybook, convert) in ENCODED.items():
        source = out / "datasets" / f"{HLQ}{name}.jsonl"
        if source.exists():
            (datasets / f"{HLQ}{name}.G0001V00").write_bytes(
                b"".join(encode_record(copybook, convert(row)) for row in read_jsonl(source)))
    for text in (out / "datasets").glob("*.txt") if (out / "datasets").exists() else []:
        shutil.copyfile(text, datasets / f"{text.stem}.G0001V00")
    for name in ("db2_after", "sysout"):
        if (out / name).is_dir():
            shutil.copytree(out / name, candidate / name)
        else:
            (candidate / name).mkdir()
    shutil.copyfile(out / "rc.json", candidate / "rc.json")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--case", required=True)
    parser.add_argument("--out", required=True, type=Path, help="work dir, e.g. work/parity-java/<case>")
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--java", default=os.path.join(os.environ["JAVA_HOME"], "bin", "java")
                        if os.environ.get("JAVA_HOME") else "java")
    parser.add_argument("--no-stub-upstream", action="store_true")
    args = parser.parse_args()

    case_root = CHAIN_ROOT / args.case
    if not (case_root / "case.json").exists():
        parser.error(f"unknown case: {args.case}")
    if not args.jar.exists():
        parser.error(f"{args.jar} not found; build with mvn -f java/pom.xml package")
    if args.out.exists():
        shutil.rmtree(args.out)
    work_in, out = args.out / "input", args.out / "out"

    prepare_inputs(case_root, work_in)
    command = [args.java, "-jar", str(args.jar), f"--case={args.case}", f"--in={work_in}", f"--out={out}"]
    if args.no_stub_upstream:
        command.append("--no-stub-upstream")
    subprocess.run(command, check=True)
    encode_candidate(out, args.out / "candidate")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
