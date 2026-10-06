"""Transaction streams: the seven recorded fixtures plus a generated longer stream."""

from __future__ import annotations

import random
import re
import shutil
import tempfile
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal, ROUND_FLOOR
from pathlib import Path

from common import ROOT, WORK

import gen_fixtures

FIXTURE_CASES = (
    "default",
    "under_cap",
    "at_cap",
    "rate_change",
    "zero_amount",
    "non_transfer",
    "half_cent",
)
GENERATED = "generated"
INPUT_FILES = ("ACCTDATA.PS", "CARDXREF.PS", "DALYTRAN.PS")


@dataclass
class Stream:
    id: str
    description: str
    inputs: Path
    recorded: bool


def list_streams() -> list[dict[str, object]]:
    items: list[dict[str, object]] = [
        {"id": case, "description": gen_fixtures.CASES[case], "recorded": True}
        for case in FIXTURE_CASES
    ]
    items.append({
        "id": GENERATED,
        "description": "Generated stream: transfers across both books, the June 15 rate change, "
                       "cap hits, half-cent ties, zero amounts and non-transfers",
        "recorded": False,
    })
    return items


def prepare(stream_id: str, count: int = 60, seed: int = 1250) -> Stream:
    if stream_id in FIXTURE_CASES:
        return Stream(stream_id, gen_fixtures.CASES[stream_id],
                      ROOT / "fixtures" / "xferfee" / stream_id / "input", True)
    if stream_id != GENERATED:
        raise ValueError(f"unknown stream {stream_id}")
    return Stream(GENERATED, f"Generated stream ({count} transactions, seed {seed})",
                  generate_stream(count, seed), False)


def fee_rules() -> list[tuple[str, Decimal, Decimal, str, str]]:
    text = (ROOT / "db2" / "data" / "CTL_XFER_PARM.sql").read_text()
    pattern = re.compile(
        r"\('(\w+)\s*',\s*([\d.]+),\s*([\d.]+),\s*DATE '([\d-]+)',\s*DATE '([\d-]+)'\)"
    )
    return [
        (book, Decimal(rate), Decimal(cap), eff, exp)
        for book, rate, cap, eff, exp in pattern.findall(text)
    ]


def rate_for(book: str, day: str) -> Decimal:
    for rule_book, rate, _cap, eff, exp in fee_rules():
        if rule_book == book and eff <= day < exp:
            return rate
    raise ValueError(f"no rule for {book} on {day}")


def is_even_half_cent_tie(amount: Decimal, rate: Decimal) -> bool:
    exact = amount * rate * 100
    lower = exact.to_integral_value(rounding=ROUND_FLOOR)
    return exact - lower == Decimal("0.5") and int(lower) % 2 == 0


def dimes(rng: random.Random, low_cents: int, high_cents: int) -> Decimal:
    """Random amount in whole dimes (cents digit 0).

    gen_fixtures.zoned() writes the sign as an EBCDIC-style overpunch ({, A-I) on the last
    digit, while GnuCOBOL on ASCII reads a trailing A-I as 0 -- so 32.79 reaches XFERFEE as
    32.70. Every committed fixture amount ends in 0 cents; generated streams keep that shape.
    """
    return Decimal(rng.randrange(low_cents // 10, high_cents // 10) * 10) / 100


def generate_stream(count: int, seed: int) -> Path:
    """Build a longer DALYTRAN with gen_fixtures' record builders over the fixture accounts."""
    out = WORK / "streams" / f"{GENERATED}-{count}-{seed}"
    with tempfile.TemporaryDirectory() as tmp:
        gen_fixtures.generate("default", Path(tmp))
        base = Path(tmp) / "fixtures" / "xferfee" / "default" / "input"
        out.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(base / "ACCTDATA.PS", out / "ACCTDATA.PS")
        shutil.copyfile(base / "CARDXREF.PS", out / "CARDXREF.PS")

    rng = random.Random(seed)
    cards = [f"{n:016d}" for n in range(1000000000000001, 1000000000000009)]
    books = {1: "RETAIL", 2: "RETAIL", 3: "RETAIL", 4: "RETAIL", 5: "RETAIL",
             6: "INSTL", 7: "INSTL", 8: "INSTL"}
    start = date(2024, 6, 1)
    rows: list[bytes] = []
    for index in range(1, count + 1):
        tran_id = f"GEN{index:013d}"
        day = (start + timedelta(days=rng.randrange(30))).isoformat()
        roll = rng.random()
        if roll < 0.18:
            type_cd, desc = rng.choice([("01", "POS purchase"), ("02", "PAYMENT"),
                                        ("05", "CASH ADVANCE")])
            amount = dimes(rng, 100, 30000)
            rows.append(gen_fixtures.transaction(
                tran_id, type_cd, amount, cards[rng.randrange(8)], day, desc))
            continue
        source = rng.randrange(1, 9)
        target = rng.choice([acct for acct in range(1, 9) if acct != source])
        rate = rate_for(books[source], day)
        if roll < 0.40:
            amount = dimes(rng, 10, 4000)
            while not is_even_half_cent_tie(amount, rate):
                amount = dimes(rng, 10, 4000)
        elif roll < 0.47:
            amount = Decimal("0.00")
        elif roll < 0.60:
            amount = (dimes(rng, 150000, 600000) if books[source] == "RETAIL"
                      else dimes(rng, 9000000, 20000000))
        else:
            amount = dimes(rng, 100, 150000)
        rows.append(gen_fixtures.transfer(
            tran_id, amount, source, target, cards[source - 1], day))
    (out / "DALYTRAN.PS").write_bytes(b"".join(rows))
    return out
