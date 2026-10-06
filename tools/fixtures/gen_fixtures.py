#!/usr/bin/env python3
"""Generate fixed-length CardDemo input members for the transfer-fee demo."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import random
import tempfile
from decimal import Decimal, ROUND_FLOOR
from pathlib import Path


POSITIVE = "{ABCDEFGHI"
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
            (Decimal("2.00"), 1, 2, "2024-06-05", Decimal("0.0125")),
            (Decimal("5.20"), 2, 3, "2024-06-05", Decimal("0.0125")),
            (Decimal("3.00"), 3, 4, "2024-06-15", Decimal("0.0150")),
            (Decimal("7.00"), 4, 5, "2024-06-16", Decimal("0.0150")),
            (Decimal("11.00"), 5, 1, "2024-06-20", Decimal("0.0150")),
        ]
        transfers = []
        for index, (amount, source, target, date, pct) in enumerate(rows, 1):
            assert_half_cent(amount, pct)
            transfers.append(
                transfer(
                    f"TRN000000000000{index}", amount, source, target,
                    cards[source - 1], date,
                )
            )
        transfers.append(
            transfer(
                "TRN0000000000006", Decimal("5.00"), 6, 7, cards[5],
                "2024-06-05",
            )
        )
        assert_half_cent(Decimal("5.00"), Decimal("0.0050"))
        return transfers
    raise ValueError(f"unsupported fixture case: {case}")


def assert_half_cent(amount: Decimal, pct: Decimal) -> None:
    exact_cents = amount * pct * Decimal("100")
    lower_cents = exact_cents.to_integral_value(rounding=ROUND_FLOOR)
    assert exact_cents - lower_cents == Decimal("0.5"), (
        f"{amount} * {pct} is not a half-cent tie"
    )
    assert int(lower_cents) % 2 == 0, (
        f"{amount} * {pct} has an odd cent before rounding"
    )


SYNTHETIC_CASES = {
    "synthetic_day": {"date": "2024-06-15", "transactions": 250, "seed": 1242},
}
SYNTHETIC_ACCOUNTS = 120
RETAIL_CAP_AMOUNT = Decimal("1666.67")


def synthetic_day(
    date: str, transactions: int = 250, seed: int = 1242
) -> tuple[list[bytes], list[bytes], list[bytes]]:
    """One deterministic business day: (ACCTDATA, CARDXREF, DALYTRAN) records.

    Ten percent of the records are non-transfer types; the rest are type-08
    transfers between matched accounts of both books, in shuffled book order,
    with zero, sub-cent, at-cap and over-cap amounts and a few prior-day
    stragglers. Balances never go negative and every card is matched, so the
    whole chain runs (no BR-05 stop).
    """
    rng = random.Random(f"{seed}:{date}")
    day = dt.date.fromisoformat(date)
    prior = (day - dt.timedelta(days=1)).isoformat()
    books = {}
    balances = {}
    account_rows = []
    xref_rows = []
    cards = {}
    for account_id in range(1, SYNTHETIC_ACCOUNTS + 1):
        book = "RETAIL" if rng.random() < 0.6 else "INSTL"
        balance = rng.randrange(50_000, 200_000)
        books[account_id] = book
        balances[account_id] = Decimal(balance)
        cards[account_id] = f"{4000000000000000 + account_id:016d}"
        account_rows.append(account(account_id, balance, book))
        xref_rows.append(xref(cards[account_id], account_id, 800000000 + account_id))

    non_transfers = transactions // 10
    kinds = ["08"] * (transactions - non_transfers) + ["NT"] * non_transfers
    rng.shuffle(kinds)
    records = []
    for number, kind in enumerate(kinds, 1):
        tran_id = f"SYN{day:%Y%m%d}{number:05d}"
        source = rng.randrange(1, SYNTHETIC_ACCOUNTS + 1)
        tran_date = prior if rng.random() < 0.08 else date
        if kind == "NT":
            records.append(transaction(
                tran_id, rng.choice(("01", "02", "03")),
                float(Decimal(rng.randrange(100, 50_000)) / 100),
                cards[source], tran_date, "POS purchase",
            ))
            continue
        target = rng.choice(
            [n for n in range(1, SYNTHETIC_ACCOUNTS + 1) if n != source]
        )
        roll = rng.random()
        if roll < 0.05:
            amount = Decimal("0")
        elif roll < 0.20:
            amount = Decimal(rng.randrange(1, 100)) / 100
        elif roll < 0.25:
            amount = RETAIL_CAP_AMOUNT - Decimal(rng.randrange(0, 2)) / 100
        elif roll < 0.35:
            amount = Decimal(rng.randrange(170_000, 500_000)) / 100
        else:
            amount = Decimal(rng.randrange(100, 150_000)) / 100
        amount = min(amount, balances[source] - 600)
        balances[source] -= amount + 500
        balances[target] += amount
        records.append(transfer(
            tran_id, float(amount), source, target, cards[source], tran_date
        ))
    return account_rows, xref_rows, records


def write_day(
    output: Path, account_rows: list[bytes], xref_rows: list[bytes],
    transactions: list[bytes],
) -> None:
    output.mkdir(parents=True, exist_ok=True)
    (output / "ACCTDATA.PS").write_bytes(b"".join(account_rows))
    (output / "CARDXREF.PS").write_bytes(b"".join(xref_rows))
    (output / "DALYTRAN.PS").write_bytes(b"".join(transactions))


CASES = {
    "default": "Default transfer-fee chain fixture",
    "under_cap": "Retail and installment transfers below their fee caps",
    "at_cap": "Retail and installment transfers at their fee caps",
    "rate_change": "Retail transfers spanning the June 15 rate change",
    "zero_amount": "Zero-amount transfer preserves fee and ledger records",
    "non_transfer": "Non-transfer transactions are ignored by the extract",
    "half_cent": "Half-cent fee rounding cases for both books",
    "synthetic_day": "Synthetic business day: 250 transactions, 225 transfers "
    "across both books and the June 15 rate change (shadow-run, COG-1242)",
}


def case_metadata(case: str) -> dict:
    return {
        "description": CASES[case],
        "run_date": SYNTHETIC_CASES.get(case, {}).get("date", "2024-06-30"),
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
    if case in SYNTHETIC_CASES:
        write_day(output, *synthetic_day(**SYNTHETIC_CASES[case]))
        (output.parent / "case.json").write_text(
            json.dumps(case_metadata(case), indent=2) + "\n"
        )
        return

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


def check(case: str, root: Path) -> None:
    with tempfile.TemporaryDirectory() as directory:
        generated_root = Path(directory)
        generate(case, generated_root)
        expected_root = root / "fixtures" / "xferfee" / case
        generated_case = generated_root / "fixtures" / "xferfee" / case
        for relative in (
            Path("input/ACCTDATA.PS"),
            Path("input/CARDXREF.PS"),
            Path("input/DALYTRAN.PS"),
            Path("case.json"),
        ):
            expected = expected_root / relative
            generated = generated_case / relative
            if not expected.exists() or expected.read_bytes() != generated.read_bytes():
                raise SystemExit(f"fixture is stale: {expected}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="default")
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--root", default=None, type=Path)
    parser.add_argument("--synthetic", type=int, metavar="N",
                        help="write a synthetic day of N transactions to --out")
    parser.add_argument("--date", default=dt.date.today().isoformat())
    parser.add_argument("--seed", type=int, default=1242)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    root = args.root or Path(__file__).resolve().parents[2]
    if args.synthetic is not None:
        if args.out is None:
            parser.error("--synthetic needs --out")
        write_day(args.out, *synthetic_day(args.date, args.synthetic, args.seed))
        return
    cases = CASES if args.all or args.check else (args.case,)
    if args.check:
        for case in cases:
            check(case, root)
    else:
        for case in cases:
            generate(case, root)


if __name__ == "__main__":
    main()
