"""COBOL numeric storage emulation (GnuCOBOL, ASCII, ``-std=ibm``).

Spec reference: XFERFEE-BUSINESS-SPEC.md section 2 (storage conventions) and
BR-8 (``ROUNDED`` semantics), BR-16 (edit pictures).

All values are ``decimal.Decimal``; floats are never used for money.
"""

from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal

CENT = Decimal("0.01")


def scaled(value: Decimal, scale: int) -> Decimal:
    return value.scaleb(-scale) if scale else value


def zoned_decode(raw: bytes, scale: int, signed: bool) -> Decimal:
    """Decode a DISPLAY (zoned) numeric field the way the GnuCOBOL runtime does.

    Trailing-sign rules observed in the estate (spec section 2 preamble, Q3):
    ``0``-``9`` positive; ``p``-``y`` negative digit 0-9; any other byte
    (including the EBCDIC-style ``{`` and ``}`` over-punches produced by the
    fixture generator) is read as digit 0, positive.
    """
    text = raw.decode("ascii", errors="replace")
    negative = False
    if signed:
        last = text[-1]
        if "0" <= last <= "9":
            digit = last
        elif "p" <= last <= "y":
            negative = True
            digit = chr(ord(last) - 0x70 + ord("0"))
        else:
            digit = "0"
        text = text[:-1] + digit
    digits = "".join(char if char.isdigit() else "0" for char in text)
    value = Decimal(int(digits))
    if negative:
        value = -value
    return scaled(value, scale)


def zoned_encode(value: Decimal, length: int, scale: int, signed: bool) -> bytes:
    """Encode a DISPLAY numeric result of COBOL arithmetic.

    Positive values end in a plain digit; negative values carry the sign in the
    last byte as ``p``-``y`` (observed ``-4025.00`` -> ``00000040250p``).
    High-order digits beyond the picture are truncated (no ``ON SIZE ERROR``).
    """
    units = int(value.scaleb(scale).to_integral_value(rounding="ROUND_DOWN"))
    negative = units < 0
    units = abs(units) % (10 ** length)
    text = f"{units:0{length}d}"
    if signed and negative and units:
        text = text[:-1] + chr(ord(text[-1]) - ord("0") + 0x70)
    return text.encode("ascii")


def packed_decode(raw: bytes, scale: int) -> Decimal:
    nibbles: list[int] = []
    for byte in raw:
        nibbles.extend((byte >> 4, byte & 0x0F))
    sign = nibbles.pop()
    value = Decimal(int("".join(str(n) for n in nibbles) or "0"))
    if sign == 0xD:
        value = -value
    return scaled(value, scale)


def packed_encode(value: Decimal, length: int, scale: int) -> bytes:
    """COMP-3: two digits per byte, trailing sign nibble ``C`` (+) / ``D`` (-)."""
    units = int(value.scaleb(scale).to_integral_value(rounding="ROUND_DOWN"))
    digits_count = length * 2 - 1
    negative = units < 0
    units = abs(units) % (10 ** digits_count)
    digits = [int(c) for c in f"{units:0{digits_count}d}"]
    digits.append(0xD if negative and units else 0xC)
    return bytes((digits[i] << 4) | digits[i + 1] for i in range(0, len(digits), 2))


def fit_picture(value: Decimal, digits: int, scale: int) -> Decimal:
    """Store an arithmetic result into a ``PIC S9(digits-scale)V9(scale)`` item.

    COBOL without ``ON SIZE ERROR`` keeps the low-order digits and the sign;
    high-order digits beyond the picture are lost.
    """
    units = int(value.scaleb(scale).to_integral_value(rounding="ROUND_DOWN"))
    negative = units < 0
    units = abs(units) % (10 ** digits)
    return scaled(Decimal(-units if negative else units), scale)


def rounded_cents(value: Decimal) -> Decimal:
    """``COMPUTE x ROUNDED`` into a ``V99`` picture: half away from zero (BR-8)."""
    return value.quantize(CENT, rounding=ROUND_HALF_UP)


def display_signed(value: Decimal, digits: int, scale: int) -> str:
    """``DISPLAY`` of a ``PIC S9(n)V99 COMP-3`` item: sign then all digits, no point.

    e.g. ``6.50`` in ``S9(09)V99`` -> ``+00000000650``.
    """
    units = int(value.scaleb(scale).to_integral_value(rounding="ROUND_DOWN"))
    sign = "-" if units < 0 else "+"
    return f"{sign}{abs(units):0{digits}d}"


def edit_z8_9_99_minus(value: Decimal) -> str:
    """``PIC Z(8)9.99-`` (13 chars): zero-suppressed integer, ``.``, 2 decimals,
    trailing ``-`` for negatives or a space otherwise (BR-16)."""
    units = int(value.scaleb(2).to_integral_value(rounding="ROUND_DOWN"))
    magnitude = abs(units) % (10 ** 11)
    integer, fraction = divmod(magnitude, 100)
    text = f"{integer:>9d}.{fraction:02d}"
    return text + ("-" if units < 0 else " ")


def edit_z8_9(value: int) -> str:
    """``PIC Z(8)9`` (9 chars)."""
    return f"{value % (10 ** 9):>9d}"
