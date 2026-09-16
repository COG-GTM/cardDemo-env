"""STEP010 - ``CBXFR01C``: extract type-08 transfers (BR-1 .. BR-5).

DD mapping (spec 1.3):
  DALYTRAN -> transactions (CVTRA05Y)   XREFFILE -> card xref (CVACT03Y)
  ACCTFILE -> account master (CVACT01Y) XFEREXTR -> extract out (CVXFR01Y)
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .layouts import CVACT01Y, CVACT03Y, CVTRA05Y, CVXFR01Y, Record, read_records

TABLE_LIMIT = 500  # OCCURS 500 TIMES (BR-2, BR-3; spec Q6)
TRANSFER_TYPE = b"08"  # BR-1


@dataclass
class StepResult:
    rc: int
    sysout: list[str] = field(default_factory=list)


def load_xref(path: Path) -> list[tuple[bytes, bytes]]:
    """1000-LOAD-XREF (CBXFR01C.cbl:70-83): first 500 rows, file order."""
    rows = read_records(path, CVACT03Y)[:TABLE_LIMIT]
    return [(r.raw("XREF-CARD-NUM"), r.raw("XREF-ACCT-ID")) for r in rows]


def load_accounts(path: Path) -> list[tuple[bytes, bytes]]:
    """1100-LOAD-ACCOUNTS (CBXFR01C.cbl:84-97): first 500 rows, file order."""
    rows = read_records(path, CVACT01Y)[:TABLE_LIMIT]
    return [(r.raw("ACCT-ID"), r.raw("ACCT-GROUP-ID")) for r in rows]


def _numeric_equal(left: bytes, right: bytes) -> bool:
    """``PIC 9(11) = PIC 9(11)`` is a numeric compare."""
    return int(left) == int(right)


def run(dalytran: Path, xreffile: Path, acctfile: Path, xferextr: Path) -> StepResult:
    xref = load_xref(xreffile)
    accounts = load_accounts(acctfile)
    read_count = select_count = unmatched_count = 0
    sysout: list[str] = []
    out = bytearray()
    # The extract record area persists across transactions, exactly like the
    # FD record in the COBOL program.
    extract = Record(CVXFR01Y)

    for tran in read_records(dalytran, CVTRA05Y):
        read_count += 1
        if tran.raw("TRAN-TYPE-CD") != TRANSFER_TYPE:  # BR-1
            continue
        # 2100-WRITE-TRANSFER (CBXFR01C.cbl:110-144)
        card = tran.raw("TRAN-CARD-NUM")
        src_acct = next((acct for xcard, acct in xref if xcard == card), None)  # BR-2 first match
        if src_acct is None:
            sysout.append(f"CBXFR01C: CARD NOT FOUND {card.decode('ascii', 'replace')}")
            unmatched_count += 1
            continue
        extract.set_raw("XFR-SRC-ACCT-ID", src_acct)
        book = next((b for acct_id, b in accounts if _numeric_equal(acct_id, src_acct)), None)  # BR-3
        if book is None:
            sysout.append(f"CBXFR01C: ACCOUNT NOT FOUND {src_acct.decode('ascii', 'replace')}")
            unmatched_count += 1
            continue
        extract.set_raw("XFR-BOOK-ID", book)
        # BR-4: field copies (same pictures => byte copies, sign byte preserved)
        extract.set_raw("XFR-TRAN-ID", tran.raw("TRAN-ID"))
        extract.set_raw("XFR-TRAN-DT", tran.raw("TRAN-ORIG-TS")[:10])
        extract.set_raw("XFR-TRAN-AMT", tran.raw("TRAN-AMT"))
        extract.set_raw("XFR-CARD-NUM", card)
        extract.set_raw("XFR-TGT-ACCT-ID", tran.raw("TRAN-DESC")[13:24])  # TRAN-DESC(14:11)
        out += extract.to_bytes()
        select_count += 1

    xferextr.parent.mkdir(parents=True, exist_ok=True)
    xferextr.write_bytes(bytes(out))
    # BR-5
    sysout.append(f"CBXFR01C: RECORDS READ {read_count:09d}")
    sysout.append(f"CBXFR01C: TRANSFERS SELECTED {select_count:09d}")
    sysout.append(f"CBXFR01C: UNMATCHED CARDS {unmatched_count:09d}")
    return StepResult(4 if unmatched_count > 0 else 0, sysout)
