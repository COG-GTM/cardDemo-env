#!/usr/bin/env python3
"""Run the Java parity-replay for one case and encode its output as a candidate.

The fixture inputs are decoded with the shared copybook codec into JSON-lines
(the contract shapes in java/contracts), parity-replay runs whichever stage beans
exist, and its JSON-lines outputs are encoded back into the candidate layout that
compare.py reads: datasets/, db2_after/, sysout/ and rc.json.
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import subprocess
from decimal import Decimal
from pathlib import Path
from typing import Any

from copybook import decode_record, encode_record, parse_copybook, record_length


ROOT = Path(__file__).resolve().parents[2]
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
DEFAULT_JAR = ROOT / "java" / "parity-replay" / "target" / "parity-replay.jar"
HLQ = "AWS.M2.CARDDEMO"
LEDGER_COLUMNS = [
    "TRAN_ID", "TRAN_DT", "SRC_ACCT_ID", "TGT_ACCT_ID",
    "BOOK_ID", "TRAN_AMT", "FEE_AMT", "CAP_APPLIED",
]


def records(path: Path, copybook: str) -> list[dict[str, Any]]:
    length = record_length(parse_copybook(copybook))
    data = path.read_bytes()
    return [
        decode_record(copybook, data[index:index + length])
        for index in range(0, len(data), length)
    ]


def money(value: Decimal, scale: int = 2) -> str:
    return format(value, f".{scale}f")


def account_to_json(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "accountId": int(row["ACCT-ID"]),
        "activeStatus": row["ACCT-ACTIVE-STATUS"],
        "currentBalance": money(row["ACCT-CURR-BAL"]),
        "creditLimit": money(row["ACCT-CREDIT-LIMIT"]),
        "cashCreditLimit": money(row["ACCT-CASH-CREDIT-LIMIT"]),
        "openDate": row["ACCT-OPEN-DATE"],
        "expirationDate": row["ACCT-EXPIRAION-DATE"],
        "reissueDate": row["ACCT-REISSUE-DATE"],
        "currentCycleCredit": money(row["ACCT-CURR-CYC-CREDIT"]),
        "currentCycleDebit": money(row["ACCT-CURR-CYC-DEBIT"]),
        "addressZip": row["ACCT-ADDR-ZIP"],
        "groupId": row["ACCT-GROUP-ID"],
    }


def account_from_json(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "ACCT-ID": row["accountId"],
        "ACCT-ACTIVE-STATUS": row["activeStatus"],
        "ACCT-CURR-BAL": Decimal(row["currentBalance"]),
        "ACCT-CREDIT-LIMIT": Decimal(row["creditLimit"]),
        "ACCT-CASH-CREDIT-LIMIT": Decimal(row["cashCreditLimit"]),
        "ACCT-OPEN-DATE": row["openDate"],
        "ACCT-EXPIRAION-DATE": row["expirationDate"],
        "ACCT-REISSUE-DATE": row["reissueDate"],
        "ACCT-CURR-CYC-CREDIT": Decimal(row["currentCycleCredit"]),
        "ACCT-CURR-CYC-DEBIT": Decimal(row["currentCycleDebit"]),
        "ACCT-ADDR-ZIP": row["addressZip"],
        "ACCT-GROUP-ID": row["groupId"],
    }


def transaction_to_json(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "tranId": row["TRAN-ID"],
        "typeCode": row["TRAN-TYPE-CD"],
        "categoryCode": int(row["TRAN-CAT-CD"]),
        "source": row["TRAN-SOURCE"],
        "description": row["TRAN-DESC"],
        "amount": money(row["TRAN-AMT"]),
        "merchantId": int(row["TRAN-MERCHANT-ID"]),
        "merchantName": row["TRAN-MERCHANT-NAME"],
        "merchantCity": row["TRAN-MERCHANT-CITY"],
        "merchantZip": row["TRAN-MERCHANT-ZIP"],
        "cardNumber": row["TRAN-CARD-NUM"],
        "origTimestamp": row["TRAN-ORIG-TS"],
        "procTimestamp": row["TRAN-PROC-TS"],
    }


def xref_to_json(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "cardNumber": row["XREF-CARD-NUM"],
        "customerId": int(row["XREF-CUST-ID"]),
        "accountId": int(row["XREF-ACCT-ID"]),
    }


def extract_from_json(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "XFR-TRAN-ID": row["tranId"],
        "XFR-TRAN-DT": row["tranDate"],
        "XFR-SRC-ACCT-ID": row["sourceAccountId"],
        "XFR-TGT-ACCT-ID": row["targetAccountId"],
        "XFR-BOOK-ID": row["bookId"],
        "XFR-TRAN-AMT": Decimal(row["amount"]),
        "XFR-CARD-NUM": row["cardNumber"],
    }


def fee_from_json(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "XFE-TRAN-ID": row["tranId"],
        "XFE-TRAN-DT": row["tranDate"],
        "XFE-SRC-ACCT-ID": row["sourceAccountId"],
        "XFE-TGT-ACCT-ID": row["targetAccountId"],
        "XFE-BOOK-ID": row["bookId"],
        "XFE-TRAN-AMT": Decimal(row["amount"]),
        "XFE-FEE-PCT": Decimal(row["feePct"]),
        "XFE-FEE-AMT": Decimal(row["feeAmount"]),
        "XFE-CAP-APPLIED": "Y" if row["capApplied"] else "N",
        "XFE-RULE-EFF-DT": row["ruleEffectiveDate"],
    }


def ledger_from_json(row: dict[str, Any]) -> dict[str, str]:
    return {
        "TRAN_ID": row["tranId"],
        "TRAN_DT": row["tranDate"],
        "SRC_ACCT_ID": str(row["sourceAccountId"]),
        "TGT_ACCT_ID": str(row["targetAccountId"]),
        "BOOK_ID": row["bookId"].ljust(10),
        "TRAN_AMT": row["amount"],
        "FEE_AMT": row["feeAmount"],
        "CAP_APPLIED": "Y" if row["capApplied"] else "N",
    }


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text("".join(json.dumps(row) + "\n" for row in rows))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def prepare_inputs(case_root: Path, replay_in: Path) -> None:
    replay_in.mkdir(parents=True)
    inputs = case_root / "input"
    write_jsonl(replay_in / "DALYTRAN.jsonl", [
        transaction_to_json(row) for row in records(inputs / "DALYTRAN.PS", "CVTRA05Y")
    ])
    write_jsonl(replay_in / "CARDXREF.jsonl", [
        xref_to_json(row) for row in records(inputs / "CARDXREF.PS", "CVACT03Y")
    ])
    write_jsonl(replay_in / "ACCTDATA.jsonl", [
        account_to_json(row) for row in records(inputs / "ACCTDATA.PS", "CVACT01Y")
    ])
    with (case_root / "db2_before" / "CTL_XFER_PARM.csv").open(newline="") as stream:
        write_jsonl(replay_in / "CTL_XFER_PARM.jsonl", [
            {
                "bookId": row["book_id"].rstrip(),
                "feePct": row["fee_pct"],
                "feeCap": row["fee_cap"],
                "effectiveDate": row["eff_dt"],
                "expiryDate": row["exp_dt"],
            }
            for row in csv.DictReader(stream)
        ])


def encode_candidate(case_root: Path, replay_out: Path, candidate: Path) -> None:
    datasets = candidate / "datasets"
    datasets.mkdir(parents=True)
    encoded = (
        ("XFER.EXTRACT", "CVXFR01Y", extract_from_json),
        ("ACCTDATA.XFER", "CVACT01Y", account_from_json),
        ("XFER.FEES", "CVXFR02Y", fee_from_json),
    )
    for name, copybook, convert in encoded:
        source = replay_out / f"{name}.jsonl"
        if source.exists():
            (datasets / f"{HLQ}.{name}.G0001V00").write_bytes(b"".join(
                encode_record(copybook, convert(row)) for row in read_jsonl(source)
            ))
    report = replay_out / "XFER.RECON.RPT.txt"
    if report.exists():
        shutil.copyfile(report, datasets / f"{HLQ}.XFER.RECON.RPT.G0001V00")

    db2_after = candidate / "db2_after"
    shutil.copytree(case_root / "db2_before", db2_after)
    fees = replay_out / "XFER.FEES.jsonl"
    if fees.exists():
        with (db2_after / "XFER_FEE_LEDGER.csv").open("a", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=LEDGER_COLUMNS)
            for row in read_jsonl(fees):
                writer.writerow(ledger_from_json(row))

    sysout = replay_out / "sysout"
    if sysout.exists():
        shutil.copytree(sysout, candidate / "sysout")
    else:
        (candidate / "sysout").mkdir()
    shutil.copyfile(replay_out / "rc.json", candidate / "rc.json")


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
    replay_in, replay_out = args.out / "replay-in", args.out / "replay-out"

    prepare_inputs(case_root, replay_in)
    subprocess.run(
        ["java", "-jar", str(args.jar), f"--case={args.case}",
         f"--in={replay_in}", f"--out={replay_out}"],
        check=True,
    )
    encode_candidate(case_root, replay_out, args.out / "candidate")
    status = json.loads((replay_out / "replay.json").read_text())
    for step, outcome in status.items():
        print(f"{args.case} {step}: {outcome}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
