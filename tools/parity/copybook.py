#!/usr/bin/env python3
"""Small fixed-record codec for the CardDemo copybook subset."""

from __future__ import annotations

import re
from decimal import Decimal
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
POSITIVE = "{}ABCDEFGHI"
NEGATIVE = "}JKLMNOPQR"
OVERPUNCH_DIGITS = {
    char: str(index % 10)
    for index, char in enumerate(POSITIVE)
}
OVERPUNCH_DIGITS.update({
    char: str(index % 10)
    for index, char in enumerate(NEGATIVE)
})


def _count_picture(piece: str) -> int:
    total = 0
    for match in re.finditer(r"([X9])(?:\((\d+)\))?", piece.upper()):
        total += int(match.group(2) or 1)
    return total


def _field_picture(pic: str) -> tuple[str, int, int, bool]:
    pic = pic.upper()
    signed = pic.startswith("S")
    body = pic[1:] if signed else pic
    integer, _, fraction = body.partition("V")
    integer_digits = _count_picture(integer)
    scale = _count_picture(fraction) if fraction else 0
    digits = integer_digits + scale
    if "X" in body:
        return "text", 0, _count_picture(body), False
    return "packed" if False else "display", scale, digits, signed


def _lookup(name: str) -> Path:
    wanted = name.upper()
    for path in (ROOT / "copybook").iterdir():
        if path.suffix.lower() == ".cpy" and path.stem.upper() == wanted:
            return path
    raise FileNotFoundError(f"copybook not found: {name}")


def parse_copybook(name: str) -> list[tuple[str, int, int, str, int, bool]]:
    """Return (name, offset, length, kind, scale, signed) fields."""
    path = _lookup(name)
    fields: list[tuple[str, int, int, str, int, bool]] = []
    offset = 0
    pattern = re.compile(
        r"^\s*\d+\s+([A-Z0-9-]+)\s+PIC\s+([SX0-9V()]+)"
        r"(?:\s+(COMP-3|COMP3))?(?:\s+OCCURS\s+(\d+))?",
        re.IGNORECASE,
    )
    for raw in path.read_text().splitlines():
        match = pattern.match(raw)
        if not match:
            continue
        name, pic, usage, occurs = match.groups()
        kind, scale, digits, signed = _field_picture(pic)
        count = int(occurs or 1)
        if kind == "text":
            length = digits
        elif usage:
            kind = "comp-3"
            length = (digits + 2) // 2
        else:
            length = digits
        for index in range(count):
            field_name = name if count == 1 else f"{name}[{index + 1}]"
            if name.upper() == "FILLER":
                field_name = f"FILLER@{offset}"
            fields.append((field_name, offset, length, kind, scale, signed))
            offset += length
    return fields


def record_length(fields: list[tuple[str, int, int, str, int, bool]]) -> int:
    if not fields:
        return 0
    name, offset, length, kind, scale, signed = fields[-1]
    return offset + length


def _decode_display(raw: bytes, scale: int, signed: bool) -> Decimal:
    text = raw.decode("ascii")
    sign = 1
    if signed and text[-1] in NEGATIVE:
        sign = -1
    last = text[-1]
    if last in OVERPUNCH_DIGITS:
        text = text[:-1] + OVERPUNCH_DIGITS[last]
    elif signed and last.islower():
        text = text[:-1] + "0"
        sign = -1
    value = Decimal(int(text or "0"))
    return sign * value / (Decimal(10) ** scale)


def _decode_packed(raw: bytes, scale: int, signed: bool) -> Decimal:
    nibbles = []
    for byte in raw:
        nibbles.extend((byte >> 4, byte & 0x0F))
    sign_nibble = nibbles.pop()
    sign = -1 if signed and sign_nibble == 0xD else 1
    digits = "".join(str(nibble) for nibble in nibbles)
    value = Decimal(int(digits or "0"))
    return sign * value / (Decimal(10) ** scale)


def decode_record(
    copybook: str, data: bytes
) -> dict[str, Any]:
    values: dict[str, Any] = {}
    for name, offset, length, kind, scale, signed in parse_copybook(copybook):
        if name.upper().startswith("FILLER"):
            continue
        raw = data[offset:offset + length]
        if kind == "text":
            values[name] = raw.decode("ascii").rstrip()
        elif kind == "comp-3":
            values[name] = _decode_packed(raw, scale, signed)
        else:
            values[name] = _decode_display(raw, scale, signed)
    return values


def _decimal(value: Any) -> Decimal:
    return value if isinstance(value, Decimal) else Decimal(str(value))


def _encode_display(value: Any, length: int, scale: int, signed: bool) -> bytes:
    decimal = _decimal(value)
    units = int(abs(decimal) * (Decimal(10) ** scale))
    text = f"{units:0{length}d}"
    if signed:
        table = NEGATIVE if decimal < 0 else POSITIVE
        text = text[:-1] + table[int(text[-1])]
    return text.encode("ascii")


def _encode_packed(value: Any, length: int, scale: int, signed: bool) -> bytes:
    decimal = _decimal(value)
    units = int(abs(decimal) * (Decimal(10) ** scale))
    digits = f"{units:0{length * 2 - 1}d}"
    nibbles = [int(char) for char in digits]
    if len(nibbles) % 2 == 0:
        nibbles.insert(0, 0)
    nibbles.append(0xD if signed and decimal < 0 else 0xC)
    return bytes(
        (nibbles[index] << 4) | nibbles[index + 1]
        for index in range(0, len(nibbles), 2)
    )


def encode_record(copybook: str, values: dict[str, Any]) -> bytes:
    fields = parse_copybook(copybook)
    output = bytearray(b" " * record_length(fields))
    for name, offset, length, kind, scale, signed in fields:
        if name.upper().startswith("FILLER"):
            continue
        value = values.get(name, Decimal("0") if kind != "text" else "")
        if kind == "text":
            encoded = str(value).ljust(length)[:length].encode("ascii")
        elif kind == "comp-3":
            encoded = _encode_packed(value, length, scale, signed)
        else:
            encoded = _encode_display(value, length, scale, signed)
        output[offset:offset + length] = encoded
    return bytes(output)
