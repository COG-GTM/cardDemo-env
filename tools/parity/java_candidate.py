#!/usr/bin/env python3
"""Run the Java parity-replay for one fixture case and lay out a compare.py candidate.

Layout under ``work/parity-java/<case>/`` (see java/README.md):

* ``input/``     fixture ``input/*.PS`` decoded with ``copybook.decode_record`` to JSON-lines
                 shaped like the ``contracts`` records (DailyTransaction, CardXref, Account)
* ``out/``       raw Java output (``sysout/``, ``rc.json``, ``observability/``, and once the
                 service modules write them ``datasets/<DSN>.jsonl`` and ``db2_after/*.csv``)
* ``candidate/`` what ``compare.py --candidate`` / ``compare_counters.py`` read: ``out/`` with
                 any ``datasets/<DSN>.jsonl`` (copybook field names) encoded fixed-width
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
from decimal import Decimal
from pathlib import Path

from copybook import decode_record, encode_record, parse_copybook, record_length


ROOT = Path(__file__).resolve().parents[2]
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
JAR = ROOT / "java" / "parity-replay" / "target" / "parity-replay.jar"

# fixture file -> (DSN, copybook, {copybook field: contracts record component})
INPUTS = {
    "DALYTRAN.PS": ("AWS.M2.CARDDEMO.DALYTRAN.PS", "CVTRA05Y", {
        "TRAN-ID": "tranId",
        "TRAN-TYPE-CD": "typeCode",
        "TRAN-CAT-CD": "categoryCode",
        "TRAN-SOURCE": "source",
        "TRAN-DESC": "description",
        "TRAN-AMT": "amount",
        "TRAN-MERCHANT-ID": "merchantId",
        "TRAN-MERCHANT-NAME": "merchantName",
        "TRAN-MERCHANT-CITY": "merchantCity",
        "TRAN-MERCHANT-ZIP": "merchantZip",
        "TRAN-CARD-NUM": "cardNumber",
        "TRAN-ORIG-TS": "originTimestamp",
        "TRAN-PROC-TS": "processTimestamp",
    }),
    "CARDXREF.PS": ("AWS.M2.CARDDEMO.CARDXREF.PS", "CVACT03Y", {
        "XREF-CARD-NUM": "cardNumber",
        "XREF-CUST-ID": "customerId",
        "XREF-ACCT-ID": "accountId",
    }),
    "ACCTDATA.PS": ("AWS.M2.CARDDEMO.ACCTDATA.PS", "CVACT01Y", {
        "ACCT-ID": "accountId",
        "ACCT-ACTIVE-STATUS": "activeStatus",
        "ACCT-CURR-BAL": "currentBalance",
        "ACCT-CREDIT-LIMIT": "creditLimit",
        "ACCT-CASH-CREDIT-LIMIT": "cashCreditLimit",
        "ACCT-OPEN-DATE": "openDate",
        "ACCT-EXPIRAION-DATE": "expirationDate",
        "ACCT-REISSUE-DATE": "reissueDate",
        "ACCT-CURR-CYC-CREDIT": "currentCycleCredit",
        "ACCT-CURR-CYC-DEBIT": "currentCycleDebit",
        "ACCT-ADDR-ZIP": "addressZip",
        "ACCT-GROUP-ID": "groupId",
    }),
}


def as_json(value: object) -> object:
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral_value() and \
            value.as_tuple().exponent >= 0 else format(value, "f")
    if isinstance(value, bytes):
        return value.decode("ascii", errors="replace")
    return value


def decode_inputs(case: str, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    for filename, (dsn, copybook, fields) in INPUTS.items():
        data = (CHAIN_ROOT / case / "input" / filename).read_bytes()
        length = record_length(parse_copybook(copybook))
        with (destination / f"{dsn}.jsonl").open("w") as stream:
            for offset in range(0, len(data), length):
                values = decode_record(copybook, data[offset:offset + length])
                row = {fields[name]: as_json(value)
                       for name, value in values.items() if name in fields}
                stream.write(json.dumps(row) + "\n")


def assemble_candidate(case: str, out: Path, candidate: Path) -> None:
    if candidate.exists():
        shutil.rmtree(candidate)
    candidate.mkdir(parents=True)
    for name in ("sysout", "db2_after", "observability"):
        if (out / name).is_dir():
            shutil.copytree(out / name, candidate / name)
    if (out / "rc.json").exists():
        shutil.copy2(out / "rc.json", candidate / "rc.json")
    jsonl = out / "datasets"
    if not jsonl.is_dir():
        return
    metadata = json.loads((CHAIN_ROOT / case / "case.json").read_text())
    datasets = candidate / "datasets"
    datasets.mkdir()
    for output in metadata["outputs"]:
        source = jsonl / f"{output['dsn']}.jsonl"
        if not source.exists():
            continue
        rows = [json.loads(line) for line in source.read_text().splitlines()
                if line.strip()]
        target = datasets / f"{output['dsn']}.G0001V00"
        if output.get("text"):
            target.write_bytes(b"".join(
                (row["line"] + "\n").encode("ascii") for row in rows))
        else:
            target.write_bytes(b"".join(
                encode_record(output["copybook"], row) for row in rows))


def build() -> None:
    subprocess.run(["mvn", "-q", "-B", "-f", str(ROOT / "java" / "pom.xml"),
                    "package", "-DskipTests"], cwd=ROOT, check=True)


def run(case: str, work: Path) -> int:
    if work.exists():
        shutil.rmtree(work)
    decode_inputs(case, work / "input")
    java = os.environ.get("JAVA", "java")
    process = subprocess.run(
        [java, "-jar", str(JAR), "--case", case, "--out", str(work / "out"),
         "--input", str(work / "input"), "--fixtures", str(CHAIN_ROOT)],
        cwd=ROOT,
    )
    assemble_candidate(case, work / "out", work / "candidate")
    return process.returncode


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", required=True)
    parser.add_argument("--work", type=Path,
                        help="default: work/parity-java/<case>")
    parser.add_argument("--build", action="store_true",
                        help="mvn package the Java workspace first")
    args = parser.parse_args()
    if args.build or not JAR.exists():
        build()
    work = (args.work or ROOT / "work" / "parity-java" / args.case).resolve()
    return run(args.case, work)


if __name__ == "__main__":
    raise SystemExit(main())
