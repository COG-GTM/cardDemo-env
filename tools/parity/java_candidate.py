#!/usr/bin/env python3
"""Run the Java parity-replay for one case and assemble a compare.py candidate.

--codec=python (default): the fixture inputs are decoded with the shared Python
copybook codec into contract-shaped JSON-lines (OUT/input/), parity-replay runs
the chain, and its JSON-lines output (OUT/out/) is encoded back into
OUT/candidate/ with the same Python codec.

--codec=java: parity-replay reads the fixture .PS files and writes
OUT/candidate/ itself through the legacy-adapter copybook codec (COG-1240).
"""

from __future__ import annotations

import argparse
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


def default_out(case: str, codec: str) -> Path:
    base = ROOT / "work" / "parity-java"
    return base / case if codec == "python" else base / "java-codec" / case


def records(path: Path, copybook: str) -> list[dict[str, Any]]:
    length = record_length(parse_copybook(copybook))
    data = path.read_bytes()
    return [decode_record(copybook, data[i:i + length]) for i in range(0, len(data), length)]


def money(value: Decimal) -> str:
    return format(value, ".2f")


def transaction_to_json(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "tranId": row["DALYTRAN-ID"],
        "typeCode": row["DALYTRAN-TYPE-CD"],
        "categoryCode": int(row["DALYTRAN-CAT-CD"]),
        "source": row["DALYTRAN-SOURCE"],
        "description": row["DALYTRAN-DESC"],
        "amount": money(row["DALYTRAN-AMT"]),
        "merchantId": int(row["DALYTRAN-MERCHANT-ID"]),
        "merchantName": row["DALYTRAN-MERCHANT-NAME"],
        "merchantCity": row["DALYTRAN-MERCHANT-CITY"],
        "merchantZip": row["DALYTRAN-MERCHANT-ZIP"],
        "cardNumber": row["DALYTRAN-CARD-NUM"],
        "originTimestamp": row["DALYTRAN-ORIG-TS"],
        "processTimestamp": row["DALYTRAN-PROC-TS"],
    }


def xref_to_json(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "cardNumber": row["XREF-CARD-NUM"],
        "customerId": int(row["XREF-CUST-ID"]),
        "accountId": int(row["XREF-ACCT-ID"]),
    }


ACCOUNT_FIELDS = {
    "accountId": "ACCT-ID",
    "activeStatus": "ACCT-ACTIVE-STATUS",
    "currentBalance": "ACCT-CURR-BAL",
    "creditLimit": "ACCT-CREDIT-LIMIT",
    "cashCreditLimit": "ACCT-CASH-CREDIT-LIMIT",
    "openDate": "ACCT-OPEN-DATE",
    "expirationDate": "ACCT-EXPIRAION-DATE",
    "reissueDate": "ACCT-REISSUE-DATE",
    "currentCycleCredit": "ACCT-CURR-CYC-CREDIT",
    "currentCycleDebit": "ACCT-CURR-CYC-DEBIT",
    "addressZip": "ACCT-ADDR-ZIP",
    "groupId": "ACCT-GROUP-ID",
}


def account_to_json(row: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, field in ACCOUNT_FIELDS.items():
        value = row[field]
        out[key] = int(value) if key == "accountId" else money(value) if isinstance(value, Decimal) else value
    return out


def account_from_json(row: dict[str, Any]) -> dict[str, Any]:
    return {
        field: Decimal(row[key]) if key not in ("accountId",) and field in DECIMAL_ACCOUNT_FIELDS else row[key]
        for key, field in ACCOUNT_FIELDS.items()
    }


DECIMAL_ACCOUNT_FIELDS = {
    "ACCT-CURR-BAL", "ACCT-CREDIT-LIMIT", "ACCT-CASH-CREDIT-LIMIT",
    "ACCT-CURR-CYC-CREDIT", "ACCT-CURR-CYC-DEBIT",
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


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text("".join(json.dumps(row) + "\n" for row in rows))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def prepare_inputs(case_root: Path, replay_in: Path) -> None:
    replay_in.mkdir(parents=True)
    inputs = case_root / "input"
    write_jsonl(replay_in / "DALYTRAN.jsonl",
                [transaction_to_json(r) for r in records(inputs / "DALYTRAN.PS", "CVTRA06Y")])
    write_jsonl(replay_in / "CARDXREF.jsonl",
                [xref_to_json(r) for r in records(inputs / "CARDXREF.PS", "CVACT03Y")])
    write_jsonl(replay_in / "ACCTDATA.jsonl",
                [account_to_json(r) for r in records(inputs / "ACCTDATA.PS", "CVACT01Y")])


def encode_candidate(raw: Path, candidate: Path) -> None:
    datasets = candidate / "datasets"
    datasets.mkdir(parents=True)
    for name, copybook, convert in (
        ("XFER.EXTRACT", "CVXFR01Y", extract_from_json),
        ("ACCTDATA.XFER", "CVACT01Y", account_from_json),
        ("XFER.FEES", "CVXFR02Y", fee_from_json),
    ):
        source = raw / "datasets" / f"{HLQ}.{name}.jsonl"
        if source.exists():
            (datasets / f"{HLQ}.{name}.G0001V00").write_bytes(
                b"".join(encode_record(copybook, convert(row)) for row in read_jsonl(source)))
    report = raw / "datasets" / f"{HLQ}.XFER.RECON.RPT.txt"
    if report.exists():
        shutil.copyfile(report, datasets / f"{HLQ}.XFER.RECON.RPT.G0001V00")
    shutil.copytree(raw / "db2_after", candidate / "db2_after")
    shutil.copytree(raw / "sysout", candidate / "sysout")
    shutil.copyfile(raw / "rc.json", candidate / "rc.json")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--case", required=True)
    parser.add_argument("--out", type=Path, help="work dir (default work/parity-java[/java-codec]/CASE)")
    parser.add_argument("--codec", choices=("python", "java"), default="python")
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    args = parser.parse_args()

    case_root = CHAIN_ROOT / args.case
    if not (case_root / "case.json").exists():
        parser.error(f"unknown case: {args.case}")
    if not args.jar.exists():
        parser.error(f"{args.jar} not found; build with mvn -f java/pom.xml install")
    out = (args.out or default_out(args.case, args.codec)).resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    if args.codec == "python":
        prepare_inputs(case_root, out / "input")
    subprocess.run(
        ["java", "-jar", str(args.jar), f"--case={args.case}", f"--out={out}",
         f"--codec={args.codec}", f"--fixtures={CHAIN_ROOT}"],
        check=True,
    )
    if args.codec == "python":
        encode_candidate(out / "out", out / "candidate")
    status = json.loads((out / "out" / "replay.json").read_text())
    for step, outcome in status.items():
        print(f"{args.case} [codec={args.codec}] {step}: {outcome}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
