"""STEP030 - ``CBXFR03C``: reconciliation report (BR-15 .. BR-17).

DD mapping (spec 1.3): XFERFEE -> fee file in (CVXFR02Y), XFERRPT -> report out
(LRECL 133, written LINE SEQUENTIAL: trailing spaces stripped, ``\\n`` terminated).
"""

from __future__ import annotations

from decimal import Decimal
from pathlib import Path

from .cobol_numeric import display_signed, edit_z8_9, edit_z8_9_99_minus
from .extract import StepResult
from .layouts import CVXFR02Y, read_records

HEADER_1 = " TRANSFER FEE RECONCILIATION"
HEADER_2 = " TRANSACTION       DATE       BOOK       AMOUNT          FEE"


def _line(text: str) -> str:
    return text[:133].rstrip(" ") + "\n"


def _subtotal(book: str, amount: Decimal, fee: Decimal) -> str:
    # 2000-SUBTOTAL (CBXFR03C.cbl:94-102)
    return _line(f" BOOK {book} SUBTOTAL AMOUNT {edit_z8_9_99_minus(amount)} FEE {edit_z8_9_99_minus(fee)}")


def run(xferfee: Path, xferrpt: Path) -> StepResult:
    lines = [_line(HEADER_1), _line(HEADER_2)]
    count = 0
    grand_amt = grand_fee = book_amt = book_fee = Decimal("0.00")
    last_book = " " * 10
    for xfe in read_records(xferfee, CVXFR02Y):
        # 1000-RECORD (CBXFR03C.cbl:75-93)
        book = xfe.text("XFE-BOOK-ID")
        amount = xfe.number("XFE-TRAN-AMT")
        fee = xfe.number("XFE-FEE-AMT")
        if last_book.strip() and book != last_book:  # BR-15: break on every change, input order
            lines.append(_subtotal(last_book, book_amt, book_fee))
            book_amt = book_fee = Decimal("0.00")
            last_book = book
        if not last_book.strip():
            last_book = book
        count += 1
        book_amt += amount
        book_fee += fee
        grand_amt += amount
        grand_fee += fee
        lines.append(_line(
            f" {xfe.text('XFE-TRAN-ID')} {xfe.text('XFE-TRAN-DT')} {book} "
            f"{edit_z8_9_99_minus(amount)} {edit_z8_9_99_minus(fee)}"
        ))

    sysout: list[str] = []
    if count > 0:  # BR-16
        lines.append(_subtotal(last_book, book_amt, book_fee))
        lines.append(_line(
            f" GRAND TOTAL COUNT {edit_z8_9(count)} AMOUNT {edit_z8_9_99_minus(grand_amt)}"
            f" FEE {edit_z8_9_99_minus(grand_fee)}"
        ))
        sysout.append(f"CBXFR03C: GRAND TOTAL FEE {display_signed(grand_fee, 11, 2)}")
        rc = 0
    else:  # BR-17
        sysout.append("CBXFR03C: NO FEE RECORDS")
        rc = 4
    xferrpt.parent.mkdir(parents=True, exist_ok=True)
    xferrpt.write_text("".join(lines))
    return StepResult(rc, sysout)
