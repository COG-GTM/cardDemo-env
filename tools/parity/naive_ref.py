#!/usr/bin/env python3
"""Naive reference mimicking Java BigDecimal.setScale(2, RoundingMode.HALF_EVEN)."""

from __future__ import annotations

import argparse
import csv
import json
import shutil
from decimal import Decimal, ROUND_HALF_EVEN
from pathlib import Path

from copybook import decode_record, encode_record


ROOT = Path(__file__).resolve().parents[2]
INPUT_DSNS = {
    "ACCTDATA.PS": "AWS.M2.CARDDEMO.ACCTDATA.PS",
    "CARDXREF.PS": "AWS.M2.CARDDEMO.CARDXREF.PS",
    "DALYTRAN.PS": "AWS.M2.CARDDEMO.DALYTRAN.PS",
}
RULES = {
    "RETAIL": (0.0125, 25.0),
    "INSTL": (0.005, 500.0),
}


def fee_rule(book: str, date: str) -> tuple[float, float]:
    if book == "RETAIL" and date >= "2024-06-15":
        return 0.015, 25.0
    return RULES[book]


def load_accounts(case: str) -> dict[int, dict[str, object]]:
    path = ROOT / "fixtures" / "xferfee" / case / "input" / "ACCTDATA.PS"
    accounts = {}
    for offset in range(0, path.stat().st_size, 300):
        raw = path.read_bytes()[offset:offset + 300]
        values = decode_record("CVACT01Y", raw)
        accounts[int(values["ACCT-ID"])] = values
    return accounts


def load_transfers(case: str) -> list[dict[str, object]]:
    path = ROOT / "fixtures" / "xferfee" / case / "input" / "DALYTRAN.PS"
    xrefs = {}
    xref_path = ROOT / "fixtures" / "xferfee" / case / "input" / "CARDXREF.PS"
    for offset in range(0, xref_path.stat().st_size, 50):
        values = decode_record("CVACT03Y", xref_path.read_bytes()[offset:offset + 50])
        xrefs[values["XREF-CARD-NUM"]] = int(values["XREF-ACCT-ID"])
    transfers = []
    for offset in range(0, path.stat().st_size, 350):
        raw = path.read_bytes()[offset:offset + 350]
        fields = decode_record("CVTRA05Y", raw)
        if fields["TRAN-TYPE-CD"] != "08":
            continue
        source = xrefs[fields["TRAN-CARD-NUM"]]
        target = int(fields["TRAN-DESC"][13:24])
        transfers.append({
            "id": fields["TRAN-ID"],
            "date": fields["TRAN-ORIG-TS"][:10],
            "source": source,
            "target": target,
            "amount": float(fields["TRAN-AMT"]),
            "card": fields["TRAN-CARD-NUM"],
        })
    return transfers


def record(case: str, out: Path) -> None:
    datasets = out / "datasets"
    datasets.mkdir(parents=True, exist_ok=True)
    accounts = load_accounts(case)
    extracts = []
    fees = []
    for transfer in load_transfers(case):
        book = str(accounts[transfer["source"]]["ACCT-GROUP-ID"]).strip()
        pct, cap = fee_rule(book, transfer["date"])
        amount = transfer["amount"]
        extracts.append({
            "XFR-TRAN-ID": transfer["id"],
            "XFR-TRAN-DT": transfer["date"],
            "XFR-SRC-ACCT-ID": transfer["source"],
            "XFR-TGT-ACCT-ID": transfer["target"],
            "XFR-BOOK-ID": book,
            "XFR-TRAN-AMT": Decimal(str(amount)),
            "XFR-CARD-NUM": transfer["card"],
        })
        amount_decimal = Decimal(str(amount))
        fee = (
            Decimal(str(amount)) * Decimal(str(pct))
        ).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)
        cap_decimal = Decimal(str(cap))
        capped = "Y" if fee > cap_decimal else "N"
        fee = min(fee, cap_decimal)
        accounts[transfer["source"]]["ACCT-CURR-BAL"] -= amount_decimal + fee
        accounts[transfer["source"]]["ACCT-CURR-CYC-DEBIT"] += amount_decimal + fee
        accounts[transfer["target"]]["ACCT-CURR-BAL"] += amount_decimal
        accounts[transfer["target"]]["ACCT-CURR-CYC-CREDIT"] += amount_decimal
        fees.append({
            "XFE-TRAN-ID": transfer["id"],
            "XFE-TRAN-DT": transfer["date"],
            "XFE-SRC-ACCT-ID": transfer["source"],
            "XFE-TGT-ACCT-ID": transfer["target"],
            "XFE-BOOK-ID": book,
            "XFE-TRAN-AMT": Decimal(str(amount)),
            "XFE-FEE-PCT": Decimal(str(pct)),
            "XFE-FEE-AMT": Decimal(str(fee)),
            "XFE-CAP-APPLIED": capped,
            "XFE-RULE-EFF-DT": "2024-06-15" if book == "RETAIL" and transfer["date"] >= "2024-06-15" else "2020-01-01",
        })
    (datasets / "AWS.M2.CARDDEMO.XFER.EXTRACT.G0001V00").write_bytes(
        b"".join(encode_record("CVXFR01Y", row) for row in extracts)
    )
    output = datasets / "AWS.M2.CARDDEMO.XFER.FEES.G0001V00"
    output.write_bytes(b"".join(encode_record("CVXFR02Y", row) for row in fees))
    account_output = datasets / "AWS.M2.CARDDEMO.ACCTDATA.XFER.G0001V00"
    account_output.write_bytes(
        b"".join(encode_record("CVACT01Y", row) for row in accounts.values())
    )
    db2_after = out / "db2_after"
    db2_after.mkdir(parents=True, exist_ok=True)
    with (db2_after / "XFER_FEE_LEDGER.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=[
                "TRAN_ID", "TRAN_DT", "SRC_ACCT_ID", "TGT_ACCT_ID",
                "BOOK_ID", "TRAN_AMT", "FEE_AMT", "CAP_APPLIED",
            ],
        )
        writer.writeheader()
        for fee in fees:
            writer.writerow({
                "TRAN_ID": fee["XFE-TRAN-ID"],
                "TRAN_DT": fee["XFE-TRAN-DT"],
                "SRC_ACCT_ID": fee["XFE-SRC-ACCT-ID"],
                "TGT_ACCT_ID": fee["XFE-TGT-ACCT-ID"],
                "BOOK_ID": str(fee["XFE-BOOK-ID"]).ljust(10),
                "TRAN_AMT": f"{fee['XFE-TRAN-AMT']:.2f}",
                "FEE_AMT": f"{fee['XFE-FEE-AMT']:.2f}",
                "CAP_APPLIED": fee["XFE-CAP-APPLIED"],
            })
    with (db2_after / "CTL_XFER_PARM.csv").open("w", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["book_id", "fee_pct", "fee_cap", "eff_dt", "exp_dt"])
        writer.writerow(["INSTL     ", "0.005000", "500.00", "2020-01-01", "9999-12-31"])
        writer.writerow(["RETAIL    ", "0.012500", "25.00", "2020-01-01", "2024-06-15"])
        writer.writerow(["RETAIL    ", "0.015000", "25.00", "2024-06-15", "9999-12-31"])
    write_recon(out, fees)
    total = sum(
        (row["XFE-FEE-AMT"] for row in fees),
        Decimal("0"),
    )
    sysout = out / "sysout"
    sysout.mkdir(parents=True, exist_ok=True)
    (sysout / "STEP010.txt").write_text(
        f"CBXFR01C: RECORDS READ {len(load_transfers(case)):09d}\n"
        f"CBXFR01C: TRANSFERS SELECTED {len(extracts):09d}\n"
        "CBXFR01C: UNMATCHED CARDS 000000000\n"
    )
    (sysout / "STEP020.txt").write_text(
        f"XFERFEE: TRANSFERS POSTED {len(fees):09d}\n"
        f"XFERFEE: TOTAL FEES +{int(round(total * 100)):011d}\n"
    )
    (sysout / "STEP030.txt").write_text(
        f"CBXFR03C: GRAND TOTAL FEE +{int(round(total * 100)):011d}\n"
    )
    (out / "rc.json").write_text(json.dumps({
        "steps": {"STEP010": 0, "STEP020": 0, "STEP030": 0},
        "maxcc": 0,
    }, indent=2) + "\n")


def write_recon(out: Path, fees: list[dict[str, object]]) -> None:
    lines = [
        b" TRANSFER FEE RECONCILIATION",
        b" TRANSACTION       DATE       BOOK       AMOUNT          FEE",
    ]
    book_amount = Decimal("0")
    book_fee = Decimal("0")
    last_book = ""
    grand_amount = Decimal("0")
    grand_fee = Decimal("0")
    for fee in fees:
        book = str(fee["XFE-BOOK-ID"])
        if last_book and last_book != book:
            lines.append(
                f" BOOK {last_book.ljust(10)} SUBTOTAL AMOUNT ".encode()
                + packed(f"{book_amount:.2f}")
                + b" FEE " + packed(f"{book_fee:.2f}")
            )
            book_amount = book_fee = Decimal("0")
        last_book = book
        raw = encode_record("CVXFR02Y", fee)
        lines.append(
            b" " + str(fee["XFE-TRAN-ID"]).encode() + b" "
            + str(fee["XFE-TRAN-DT"]).encode() + b" "
            + book.encode().ljust(10) + b" "
            + raw[58:64] + b" " + raw[68:74]
        )
        amount = Decimal(str(fee["XFE-TRAN-AMT"]))
        value = Decimal(str(fee["XFE-FEE-AMT"]))
        book_amount += amount
        book_fee += value
        grand_amount += amount
        grand_fee += value
    if last_book:
        lines.append(
            f" BOOK {last_book.ljust(10)} SUBTOTAL AMOUNT ".encode()
            + packed(f"{book_amount:.2f}")
            + b" FEE " + packed(f"{book_fee:.2f}")
        )
    lines.append(
        f" GRAND TOTAL COUNT {len(fees):09d} AMOUNT ".encode()
        + packed(f"{grand_amount:.2f}")
        + b" FEE " + packed(f"{grand_fee:.2f}")
    )
    (out / "datasets" / "AWS.M2.CARDDEMO.XFER.RECON.RPT.G0001V00").write_bytes(
        b"\n".join(lines) + b"\n"
    )


def packed(amount: str) -> bytes:
    value = Decimal(amount)
    digits = f"{abs(value) * 100:.0f}"
    digits = digits.rjust(11, "0")
    nibbles = [int(char) for char in digits] + [0xD if value < 0 else 0xC]
    return bytes(
        (nibbles[index] << 4) | nibbles[index + 1]
        for index in range(0, len(nibbles), 2)
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", required=True)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    if args.out.exists():
        shutil.rmtree(args.out)
    record(args.case, args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
