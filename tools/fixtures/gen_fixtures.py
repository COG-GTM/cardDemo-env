#!/usr/bin/env python3
"""Generate fixed-length CardDemo input members for the transfer-fee demo."""

from __future__ import annotations

import argparse
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


def generate(case: str, root: Path) -> None:
    if case != "default":
        raise ValueError(f"unsupported fixture case: {case}")
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
    transactions = [
        transaction("TRN0000000000001", "01", 42.00, cards[2], "2024-06-05",
                    "POS purchase"),
        transaction("TRN0000000000002", "08", 100.00, cards[0], "2024-06-20",
                    "XFER TO ACCT 00000000002"),
        transaction("TRN0000000000003", "08", 1000.00, cards[5], "2024-06-21",
                    "XFER TO ACCT 00000000007"),
        transaction("TRN0000000000004", "01", 18.50, cards[4], "2024-06-29",
                    "POS purchase"),
    ]
    (output / "ACCTDATA.PS").write_bytes(b"".join(account_rows))
    (output / "CARDXREF.PS").write_bytes(b"".join(xref_rows))
    (output / "DALYTRAN.PS").write_bytes(b"".join(transactions))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="default")
    parser.add_argument("--root", default=None, type=Path)
    args = parser.parse_args()
    generate(args.case, args.root or Path(__file__).resolve().parents[2])


if __name__ == "__main__":
    main()
