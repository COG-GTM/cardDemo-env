#!/usr/bin/env python3
"""Generate fixed-length CardDemo input members for the transfer-fee demo."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


POSITIVE = "{}ABCDEFGHI"
NEGATIVE = "}JKLMNOPQR"


def zoned(value: float, digits: int = 12) -> str:
    cents = int(round(abs(value) * 100))
    raw = f"{cents:0{digits}d}"
    sign = POSITIVE if value >= 0 else NEGATIVE
    return raw[:-1] + sign[int(raw[-1])]


def account(account_id: int, balance: float, book: str) -> bytes:
    fields = [
        f"{account_id:011d}",
        "Y",
        zoned(balance),
        zoned(50000),
        zoned(10000),
        "2020-01-01",
        "2030-12-31",
        "2025-01-01",
        zoned(0),
        zoned(0),
        f"{account_id:05d}     ",
        book.ljust(10),
    ]
    record = "".join(fields).ljust(300)
    assert len(record) == 300
    return record.encode("ascii")


def xref(card: str, account_id: int, customer_id: int) -> bytes:
    record = f"{card}{customer_id:09d}{account_id:011d}".ljust(50)
    assert len(record) == 50
    return record.encode("ascii")


def transaction(
    transaction_id: str,
    transaction_type: str,
    amount: float,
    card: str,
    date: str,
    description: str,
) -> bytes:
    fields = [
        transaction_id.ljust(16),
        transaction_type,
        "0001",
        "DEMO".ljust(10),
        description.ljust(100),
        zoned(amount, 11),
        "000000001",
        "CardDemo Test Merchant".ljust(50),
        "Legacyville".ljust(50),
        "00000".ljust(10),
        card,
        f"{date} 12:00:00.000000".ljust(26),
        f"{date} 12:01:00.000000".ljust(26),
    ]
    record = "".join(fields).ljust(350)
    assert len(record) == 350
    return record.encode("ascii")


def transfer(
    transaction_id: str,
    amount: float,
    source: int,
    target: int,
    card: str,
    date: str,
) -> bytes:
    return transaction(
        transaction_id,
        "08",
        amount,
        card,
        date,
        f"XFER TO ACCT {target:011d}",
    )


def case_transactions(case: str, cards: list[str]) -> list[bytes]:
    if case == "default":
        return [
            transaction("TRN0000000000001", "01", 42.00, cards[2],
                        "2024-06-05", "POS purchase"),
            transfer("TRN0000000000002", 100.00, 1, 2, cards[0],
                     "2024-06-20"),
            transfer("TRN0000000000003", 1000.00, 6, 7, cards[5],
                     "2024-06-21"),
            transaction("TRN0000000000004", "01", 18.50, cards[4],
                        "2024-06-29", "POS purchase"),
        ]
    if case == "under_cap":
        return [
            transfer("TRN0000000000001", 100.00, 1, 2, cards[0],
                     "2024-06-05"),
            transfer("TRN0000000000002", 1000.00, 6, 7, cards[5],
                     "2024-06-05"),
        ]
    if case == "at_cap":
        return [
            transfer("TRN0000000000001", 5000.00, 1, 2, cards[0],
                     "2024-06-05"),
            transfer("TRN0000000000002", 200000.00, 6, 7, cards[5],
                     "2024-06-05"),
        ]
    if case == "rate_change":
        return [
            transfer("TRN0000000000001", 100.00, 1, 2, cards[0],
                     "2024-06-14"),
            transfer("TRN0000000000002", 100.00, 1, 2, cards[0],
                     "2024-06-15"),
            transfer("TRN0000000000003", 100.00, 1, 2, cards[0],
                     "2024-06-16"),
        ]
    if case == "zero_amount":
        return [
            transfer("TRN0000000000001", 0.00, 1, 2, cards[0],
                     "2024-06-05"),
        ]
    if case == "non_transfer":
        return [
            transaction("TRN0000000000001", "01", 42.00, cards[2],
                        "2024-06-05", "POS purchase"),
            transaction("TRN0000000000002", "02", 10.00, cards[3],
                        "2024-06-05", "PAYMENT"),
            transaction("TRN0000000000003", "05", 15.00, cards[4],
                        "2024-06-05", "CASH ADVANCE"),
            transfer("TRN0000000000004", 100.00, 1, 2, cards[0],
                     "2024-06-05"),
        ]
    if case == "half_cent":
        rows = [
            (4.20, 1, 2),
            (0.20, 2, 3),
            (12.20, 3, 4),
            (20.20, 4, 5),
            (28.20, 5, 1),
        ]
        transfers = [
            transfer(f"TRN000000000000{index}", amount, source, target,
                     cards[source - 1], "2024-06-05")
            for index, (amount, source, target) in enumerate(rows, 1)
        ]
        transfers.append(
            transfer("TRN0000000000006", 5.00, 6, 7, cards[5],
                     "2024-06-05")
        )
        return transfers
    raise ValueError(f"unsupported fixture case: {case}")


CASES = {
    "default": "Default transfer-fee chain fixture",
    "under_cap": "Retail and installment transfers below their fee caps",
    "at_cap": "Retail and installment transfers at their fee caps",
    "rate_change": "Retail transfers spanning the June 15 rate change",
    "zero_amount": "Zero-amount transfer preserves fee and ledger records",
    "non_transfer": "Non-transfer transactions are ignored by the extract",
    "half_cent": "Half-cent fee rounding cases for both books",
}


def case_metadata(case: str) -> dict:
    return {
        "description": CASES[case],
        "run_date": "2024-06-30",
        "outputs": [
            {
                "dsn": "AWS.M2.CARDDEMO.XFER.EXTRACT",
                "copybook": "CVXFR01Y",
                "key": ["XFR-TRAN-ID"],
            },
            {
                "dsn": "AWS.M2.CARDDEMO.ACCTDATA.XFER",
                "copybook": "CVACT01Y",
                "key": ["ACCT-ID"],
            },
            {
                "dsn": "AWS.M2.CARDDEMO.XFER.FEES",
                "copybook": "CVXFR02Y",
                "key": ["XFE-TRAN-ID"],
            },
            {
                "dsn": "AWS.M2.CARDDEMO.XFER.RECON.RPT",
                "text": True,
                "key": ["line"],
            },
        ],
        "db2": [
            {"table": "CTL_XFER_PARM", "key": ["BOOK_ID", "EFF_DT"]},
            {"table": "XFER_FEE_LEDGER", "key": ["TRAN_ID"]},
        ],
    }


def generate(case: str, root: Path) -> None:
    output = root / "fixtures" / "xferfee" / case / "input"
    output.mkdir(parents=True, exist_ok=True)

    cards = [f"{n:016d}" for n in range(1000000000000001, 1000000000000009)]
    account_rows = [
        account(1, 1000, "RETAIL"),
        account(2, 500, "RETAIL"),
        account(3, 750, "RETAIL"),
        account(4, 250, "RETAIL"),
        account(5, 1250, "RETAIL"),
        account(6, 2000, "INSTL"),
        account(7, 1500, "INSTL"),
        account(8, 900, "INSTL"),
    ]
    xref_rows = [xref(card, idx, 900000000 + idx) for idx, card in enumerate(cards, 1)]
    transactions = case_transactions(case, cards)
    (output / "ACCTDATA.PS").write_bytes(b"".join(account_rows))
    (output / "CARDXREF.PS").write_bytes(b"".join(xref_rows))
    (output / "DALYTRAN.PS").write_bytes(b"".join(transactions))
    (output.parent / "case.json").write_text(
        json.dumps(case_metadata(case), indent=2) + "\n"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="default")
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--root", default=None, type=Path)
    args = parser.parse_args()
    root = args.root or Path(__file__).resolve().parents[2]
    if args.all:
        for case in CASES:
            generate(case, root)
    else:
        generate(args.case, root)


if __name__ == "__main__":
    main()
