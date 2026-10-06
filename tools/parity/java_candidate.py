#!/usr/bin/env python3
"""Bridge between the COBOL fixtures and the Java parity-replay (COG-1234 / COG-1251).

decode: fixtures/xferfee/<case>/{input,db2_before} -> <work>/input/*.jsonl (contract JSON)
encode: <work>/out (JSON lines written by parity-replay) -> <work>/candidate in the exact
        layout tools/parity/compare.py diffs (fixed-width datasets, db2_after CSV, SYSOUT, rc.json)
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import sys
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterable

sys.path.insert(0, str(Path(__file__).resolve().parent))
from copybook import decode_record, encode_record, parse_copybook, record_length  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"

EXTRACT = "AWS.M2.CARDDEMO.XFER.EXTRACT"
ACCTDATA = "AWS.M2.CARDDEMO.ACCTDATA.XFER"
FEES = "AWS.M2.CARDDEMO.XFER.FEES"
RECON = "AWS.M2.CARDDEMO.XFER.RECON.RPT"

LEDGER_COLUMNS = ("tran_id", "tran_dt", "src_acct_id", "tgt_acct_id", "book_id",
                  "tran_amt", "fee_amt", "cap_applied")
RULE_COLUMNS = ("book_id", "fee_pct", "fee_cap", "eff_dt", "exp_dt")


def records(path: Path, copybook: str) -> Iterable[dict[str, Any]]:
    length = record_length(parse_copybook(copybook))
    data = path.read_bytes() if path.exists() else b""
    for offset in range(0, len(data) - length + 1, length):
        yield decode_record(copybook, data[offset:offset + length])


def money(value: Decimal | str, scale: int = 2) -> str:
    return format(Decimal(value), f".{scale}f")


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row) + "\n" for row in rows))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open(newline="") as stream:
        return [{key.lower(): value for key, value in row.items()} for row in csv.DictReader(stream)]


def write_csv(path: Path, columns: tuple[str, ...], rows: Iterable[tuple[Any, ...]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(columns)
        writer.writerows(rows)


def account_json(row: dict[str, Any]) -> dict[str, Any]:
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


def decode(case: str, work: Path) -> None:
    fixture = CHAIN_ROOT / case
    if not fixture.is_dir():
        raise SystemExit(f"unknown case {case}: {fixture} missing")
    inputs = work / "input"
    if inputs.exists():
        shutil.rmtree(inputs)
    write_jsonl(inputs / "accounts.jsonl",
                (account_json(row) for row in records(fixture / "input" / "ACCTDATA.PS", "CVACT01Y")))
    write_jsonl(inputs / "card_xref.jsonl", (
        {"cardNumber": row["XREF-CARD-NUM"], "customerId": int(row["XREF-CUST-ID"]),
         "accountId": int(row["XREF-ACCT-ID"])}
        for row in records(fixture / "input" / "CARDXREF.PS", "CVACT03Y")))
    write_jsonl(inputs / "daily_transactions.jsonl", (
        {"tranId": row["TRAN-ID"], "typeCode": row["TRAN-TYPE-CD"], "categoryCode": int(row["TRAN-CAT-CD"]),
         "source": row["TRAN-SOURCE"], "description": row["TRAN-DESC"], "amount": money(row["TRAN-AMT"]),
         "merchantId": int(row["TRAN-MERCHANT-ID"]), "merchantName": row["TRAN-MERCHANT-NAME"],
         "merchantCity": row["TRAN-MERCHANT-CITY"], "merchantZip": row["TRAN-MERCHANT-ZIP"],
         "cardNumber": row["TRAN-CARD-NUM"], "originTimestamp": row["TRAN-ORIG-TS"],
         "processTimestamp": row["TRAN-PROC-TS"]}
        for row in records(fixture / "input" / "DALYTRAN.PS", "CVTRA05Y")))
    write_jsonl(inputs / "fee_rules.jsonl", (
        {"bookId": row["book_id"].strip(), "feePct": row["fee_pct"], "feeCap": row["fee_cap"],
         "effectiveDate": row["eff_dt"], "expiryDate": row["exp_dt"]}
        for row in read_csv(fixture / "db2_before" / "CTL_XFER_PARM.csv")))
    write_jsonl(inputs / "ledger_before.jsonl", (
        {"tranId": row["tran_id"].rstrip(), "tranDate": row["tran_dt"], "sourceAccountId": int(row["src_acct_id"]),
         "targetAccountId": int(row["tgt_acct_id"]), "bookId": row["book_id"].strip(),
         "amount": row["tran_amt"], "feeAmount": row["fee_amt"], "capApplied": row["cap_applied"] == "Y"}
        for row in read_csv(fixture / "db2_before" / "XFER_FEE_LEDGER.csv")))


def encode_dataset(path: Path, copybook: str, rows: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"".join(encode_record(copybook, row) for row in rows))


def encode(work: Path) -> None:
    out = work / "out"
    candidate = work / "candidate"
    if candidate.exists():
        shutil.rmtree(candidate)
    datasets = candidate / "datasets"
    datasets.mkdir(parents=True)
    produced = out / "datasets"

    if (produced / f"{EXTRACT}.jsonl").exists():
        encode_dataset(datasets / EXTRACT, "CVXFR01Y", (
            {"XFR-TRAN-ID": r["tranId"], "XFR-TRAN-DT": r["tranDate"], "XFR-SRC-ACCT-ID": r["sourceAccountId"],
             "XFR-TGT-ACCT-ID": r["targetAccountId"], "XFR-BOOK-ID": r["bookId"],
             "XFR-TRAN-AMT": Decimal(r["amount"]), "XFR-CARD-NUM": r["cardNumber"]}
            for r in read_jsonl(produced / f"{EXTRACT}.jsonl")))
    if (produced / f"{ACCTDATA}.jsonl").exists():
        encode_dataset(datasets / ACCTDATA, "CVACT01Y", (
            {"ACCT-ID": r["accountId"], "ACCT-ACTIVE-STATUS": r["activeStatus"],
             "ACCT-CURR-BAL": Decimal(r["currentBalance"]), "ACCT-CREDIT-LIMIT": Decimal(r["creditLimit"]),
             "ACCT-CASH-CREDIT-LIMIT": Decimal(r["cashCreditLimit"]), "ACCT-OPEN-DATE": r["openDate"],
             "ACCT-EXPIRAION-DATE": r["expirationDate"], "ACCT-REISSUE-DATE": r["reissueDate"],
             "ACCT-CURR-CYC-CREDIT": Decimal(r["currentCycleCredit"]),
             "ACCT-CURR-CYC-DEBIT": Decimal(r["currentCycleDebit"]), "ACCT-ADDR-ZIP": r["addressZip"],
             "ACCT-GROUP-ID": r["groupId"]}
            for r in read_jsonl(produced / f"{ACCTDATA}.jsonl")))
    if (produced / f"{FEES}.jsonl").exists():
        encode_dataset(datasets / FEES, "CVXFR02Y", (
            {"XFE-TRAN-ID": r["tranId"], "XFE-TRAN-DT": r["tranDate"], "XFE-SRC-ACCT-ID": r["sourceAccountId"],
             "XFE-TGT-ACCT-ID": r["targetAccountId"], "XFE-BOOK-ID": r["bookId"],
             "XFE-TRAN-AMT": Decimal(r["amount"]), "XFE-FEE-PCT": Decimal(r["feePct"]),
             "XFE-FEE-AMT": Decimal(r["feeAmount"]), "XFE-CAP-APPLIED": "Y" if r["capApplied"] else "N",
             "XFE-RULE-EFF-DT": r["ruleEffectiveDate"]}
            for r in read_jsonl(produced / f"{FEES}.jsonl")))
    if (produced / f"{RECON}.txt").exists():
        shutil.copyfile(produced / f"{RECON}.txt", datasets / RECON)

    db2 = out / "db2_after"
    write_csv(candidate / "db2_after" / "CTL_XFER_PARM.csv", RULE_COLUMNS, (
        (r["bookId"].ljust(10), money(r["feePct"], 6), money(r["feeCap"]), r["effectiveDate"], r["expiryDate"])
        for r in read_jsonl(db2 / "CTL_XFER_PARM.jsonl")))
    write_csv(candidate / "db2_after" / "XFER_FEE_LEDGER.csv", LEDGER_COLUMNS, (
        (r["tranId"].ljust(16), r["tranDate"], r["sourceAccountId"], r["targetAccountId"], r["bookId"].ljust(10),
         money(r["amount"]), money(r["feeAmount"]), "Y" if r["capApplied"] else "N")
        for r in sorted(read_jsonl(db2 / "XFER_FEE_LEDGER.jsonl"), key=lambda r: r["tranId"])))
    shutil.copytree(out / "sysout", candidate / "sysout")
    shutil.copyfile(out / "rc.json", candidate / "rc.json")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("decode", "encode"))
    parser.add_argument("--case", required=True)
    parser.add_argument("--work", type=Path)
    args = parser.parse_args()
    work = args.work or ROOT / "work" / "parity-java" / args.case
    if args.command == "decode":
        decode(args.case, work)
    else:
        encode(work)
    return 0


if __name__ == "__main__":
    sys.exit(main())
