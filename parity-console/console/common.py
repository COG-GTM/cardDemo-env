"""Shared paths, record codecs and Postgres helpers for the live parity console."""

from __future__ import annotations

import csv
import io
import os
import subprocess
import sys
from decimal import Decimal
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "work" / "parity-console"
for _path in (ROOT, ROOT / "tools" / "parity", ROOT / "tools" / "fixtures"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from copybook import decode_record, encode_record, parse_copybook, record_length  # noqa: E402

LIVE_DB = os.environ.get("PGDATABASE", "carddemo_live")
TRANSFER_TYPE = "08"
TYPE_LABELS = {
    "01": "Purchase",
    "02": "Payment",
    "03": "Credit",
    "04": "Authorization",
    "05": "Refund / cash adv.",
    "06": "Reversal",
    "07": "Adjustment",
    "08": "Transfer",
}


EBCDIC_NEGATIVE = "}JKLMNOPQR"


def ascii_sign_to_overpunch(copybook: str, record: bytes) -> bytes:
    """Rewrite GnuCOBOL's ASCII negative sign (last byte 0x70+digit, 'p'..'y') as the
    EBCDIC-style overpunch copybook.py decodes; copybook.py maps 'p'..'y' to digit 0,
    which would show -100.25 as -100.20 once a balance written by COBOL goes negative."""
    fixed = bytearray(record)
    for _name, offset, length, kind, _scale, signed in parse_copybook(copybook):
        last = offset + length - 1
        if signed and kind not in ("text", "comp-3") and 0x70 <= fixed[last] <= 0x79:
            fixed[last] = ord(EBCDIC_NEGATIVE[fixed[last] - 0x70])
    return bytes(fixed)


def records(path: Path, copybook: str) -> list[dict[str, Any]]:
    length = record_length(parse_copybook(copybook))
    data = path.read_bytes() if path.exists() else b""
    return [
        decode_record(copybook, ascii_sign_to_overpunch(copybook, data[offset:offset + length]))
        for offset in range(0, len(data), length)
    ]


def raw_records(path: Path, length: int) -> list[bytes]:
    data = path.read_bytes()
    return [data[offset:offset + length] for offset in range(0, len(data), length)]


def money(value: Any) -> str | None:
    if value is None or value == "":
        return None
    return f"{Decimal(str(value)):.2f}"


def pct(value: Any) -> str | None:
    if value is None or value == "":
        return None
    return f"{Decimal(str(value)):.6f}"


def psql_rows(sql: str, database: str = LIVE_DB) -> list[dict[str, str]]:
    result = subprocess.run(
        ["psql", "-v", "ON_ERROR_STOP=1", "-d", database, "--csv", "-c", sql],
        cwd=ROOT, check=True, capture_output=True, text=True,
    )
    return [
        {key.upper(): value for key, value in row.items()}
        for row in csv.DictReader(io.StringIO(result.stdout))
    ]


def psql_exec(sql: str, database: str = LIVE_DB) -> None:
    subprocess.run(
        ["psql", "-v", "ON_ERROR_STOP=1", "-q", "-d", database, "-c", sql],
        cwd=ROOT, check=True, capture_output=True, text=True,
    )


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


__all__ = [
    "ROOT", "WORK", "LIVE_DB", "TRANSFER_TYPE", "TYPE_LABELS", "records", "raw_records",
    "decode_record", "encode_record", "money", "pct", "psql_rows", "psql_exec", "sql_literal",
]
