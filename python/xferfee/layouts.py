"""Record layouts (spec section 2) parsed from the COBOL copybooks.

Each record is kept as a mutable byte buffer so fields that the COBOL only
``MOVE``s between identical pictures round-trip byte-for-byte (including
their zoned sign byte), while fields that take part in arithmetic are
re-encoded exactly like the runtime does (spec section 2 preamble).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

from .cobol_numeric import packed_decode, packed_encode, zoned_decode, zoned_encode

ROOT = Path(__file__).resolve().parents[2]
COPYBOOK_DIR = ROOT / "copybook"

_PIC = re.compile(
    r"^\s*\d+\s+([A-Z0-9-]+)\s+PIC\s+([SX0-9V()]+)(?:\s+(COMP-3|COMP3))?",
    re.IGNORECASE,
)


def _count(piece: str) -> int:
    return sum(int(n or 1) for _, n in re.findall(r"([X9])(?:\((\d+)\))?", piece))


@dataclass(frozen=True)
class Field:
    name: str
    offset: int
    length: int
    kind: str  # "text" | "display" | "comp-3"
    scale: int
    signed: bool

    @property
    def end(self) -> int:
        return self.offset + self.length


@dataclass(frozen=True)
class Layout:
    name: str
    fields: tuple[Field, ...]
    length: int

    def field(self, name: str) -> Field:
        for field in self.fields:
            if field.name == name:
                return field
        raise KeyError(name)


def load_layout(copybook: str) -> Layout:
    path = COPYBOOK_DIR / f"{copybook}.cpy"
    fields: list[Field] = []
    offset = 0
    for line in path.read_text().splitlines():
        match = _PIC.match(line)
        if not match:
            continue
        name, pic, usage = match.groups()
        pic = pic.upper()
        signed = pic.startswith("S")
        body = pic[1:] if signed else pic
        integer, _, fraction = body.partition("V")
        if "X" in body:
            kind, scale, length = "text", 0, _count(body)
        else:
            digits = _count(integer) + _count(fraction)
            scale = _count(fraction)
            if usage:
                kind, length = "comp-3", (digits + 2) // 2
            else:
                kind, length = "display", digits
        if name.upper() == "FILLER":
            name = f"FILLER@{offset}"
        fields.append(Field(name, offset, length, kind, scale, signed))
        offset += length
    return Layout(copybook, tuple(fields), offset)


# Copybooks used by the chain (spec 1.3).
CVTRA05Y = load_layout("CVTRA05Y")  # daily transaction, LRECL 350
CVACT03Y = load_layout("CVACT03Y")  # card cross-reference, LRECL 50
CVACT01Y = load_layout("CVACT01Y")  # account master, LRECL 300
CVXFR01Y = load_layout("CVXFR01Y")  # transfer extract, LRECL 120
CVXFR02Y = load_layout("CVXFR02Y")  # fee file, LRECL 100

assert (CVTRA05Y.length, CVACT03Y.length, CVACT01Y.length) == (350, 50, 300)
assert (CVXFR01Y.length, CVXFR02Y.length) == (120, 100)


class Record:
    """A fixed-length record over a copybook layout.

    New records start as ``0x00`` bytes, matching the uninitialised FD record
    areas the COBOL programs write their FILLER bytes from (spec 2.4/2.5, BR-14).
    """

    def __init__(self, layout: Layout, data: bytes | None = None):
        self.layout = layout
        if data is None:
            data = bytes(layout.length)
        if len(data) != layout.length:
            raise ValueError(f"{layout.name}: expected {layout.length} bytes, got {len(data)}")
        self.data = bytearray(data)

    def raw(self, name: str) -> bytes:
        field = self.layout.field(name)
        return bytes(self.data[field.offset:field.end])

    def set_raw(self, name: str, value: bytes) -> None:
        field = self.layout.field(name)
        if len(value) != field.length:
            raise ValueError(f"{name}: expected {field.length} bytes, got {len(value)}")
        self.data[field.offset:field.end] = value

    def text(self, name: str) -> str:
        return self.raw(name).decode("ascii", errors="replace")

    def set_text(self, name: str, value: str) -> None:
        field = self.layout.field(name)
        self.set_raw(name, value.ljust(field.length)[: field.length].encode("ascii"))

    def number(self, name: str) -> Decimal:
        field = self.layout.field(name)
        raw = self.raw(name)
        if field.kind == "comp-3":
            return packed_decode(raw, field.scale)
        return zoned_decode(raw, field.scale, field.signed)

    def set_number(self, name: str, value: Decimal) -> None:
        field = self.layout.field(name)
        if field.kind == "comp-3":
            self.set_raw(name, packed_encode(value, field.length, field.scale))
        else:
            self.set_raw(name, zoned_encode(value, field.length, field.scale, field.signed))

    def to_bytes(self) -> bytes:
        return bytes(self.data)


def read_records(path: Path, layout: Layout) -> list[Record]:
    data = path.read_bytes()
    if len(data) % layout.length:
        raise ValueError(f"{path}: length {len(data)} is not a multiple of {layout.length}")
    return [Record(layout, data[i:i + layout.length]) for i in range(0, len(data), layout.length)]
