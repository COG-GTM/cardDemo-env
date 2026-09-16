"""STEP020 - ``XFERFEE``: fee rule lookup, fee posting, ledger insert (BR-6 .. BR-14).

DD mapping (spec 1.3):
  XFEREXTR -> extract in (CVXFR01Y)      ACCTFILE -> account master in (CVACT01Y)
  ACCTOUT  -> updated master out (CVACT01Y)  XFERFEE -> fee file out (CVXFR02Y)

Database: PostgreSQL via psycopg2 (spec 2.9); connection values come from the
``OCDB_*`` / ``PG*`` environment like the COBOL program, not from ``DB2PARM``.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

import psycopg2

from .cobol_numeric import display_signed, fit_picture, rounded_cents
from .extract import StepResult
from .layouts import CVACT01Y, CVXFR01Y, CVXFR02Y, Record, read_records

TABLE_LIMIT = 500  # OCCURS 500 TIMES (BR-6)

# Spec 2.9 - exact statements issued by XFERFEE.cbl:178-185 and 229-238.
SQL_SELECT_RULE = """
SELECT FEE_PCT, FEE_CAP, EFF_DT
  FROM CTL_XFER_PARM
 WHERE BOOK_ID = %(book_id)s
   AND EFF_DT <= CAST(%(tran_dt)s AS DATE)
   AND EXP_DT >  CAST(%(tran_dt)s AS DATE)
"""
SQL_INSERT_LEDGER = """
INSERT INTO XFER_FEE_LEDGER
    (TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID,
     BOOK_ID, TRAN_AMT, FEE_AMT, CAP_APPLIED)
VALUES
    (%(tran_id)s, CAST(%(tran_dt)s AS DATE),
     %(src_acct_id)s, %(tgt_acct_id)s,
     %(book_id)s, %(tran_amt)s, %(fee_amt)s,
     %(cap_applied)s)
"""


class Abend(Exception):
    """9999-ABEND-PROGRAM: RC 8, nothing committed (XFERFEE.cbl:292-295)."""


@dataclass
class FeeRule:
    fee_pct: Decimal
    fee_cap: Decimal
    eff_dt: str


def connect():
    """EXEC SQL CONNECT :WS-DB-USER IDENTIFIED BY :WS-DB-PASS USING :WS-DB-NAME."""
    return psycopg2.connect(
        dbname=os.environ.get("OCDB_NAME") or os.environ.get("PGDATABASE", "carddemo"),
        user=os.environ.get("OCDB_USER") or os.environ.get("PGUSER", "carddemo"),
        password=os.environ.get("OCDB_PASS") or os.environ.get("PGPASSWORD", "carddemo"),
        host=os.environ.get("PGHOST", "localhost"),
        port=int(os.environ.get("PGPORT", "5432")),
    )


def select_rule(cursor, book_id: str, tran_dt: str, sysout: list[str]) -> FeeRule:
    """BR-7: singleton SELECT ... INTO; SQLCODE 100 / any error -> abend."""
    try:
        cursor.execute(SQL_SELECT_RULE, {"book_id": book_id, "tran_dt": tran_dt})
        rows = cursor.fetchall()
    except psycopg2.Error as error:
        sysout.append(f"XFERFEE: RULE LOOKUP FAILED {error.pgcode or ''}")
        raise Abend from error
    if not rows:
        sysout.append(f"XFERFEE: NO FEE RULE FOR BOOK {book_id}")
        raise Abend
    if len(rows) > 1:
        sysout.append("XFERFEE: RULE LOOKUP FAILED -00000811")
        raise Abend
    fee_pct, fee_cap, eff_dt = rows[0]
    return FeeRule(Decimal(fee_pct), Decimal(fee_cap), eff_dt.isoformat())


def find_account(accounts: list[Record], acct_id: int) -> int | None:
    """2200-FIND-ACCOUNTS (XFERFEE.cbl:245-259): numeric compare, *last* match wins (BR-11)."""
    found = None
    for index, account in enumerate(accounts):
        if int(account.raw("ACCT-ID")) == acct_id:
            found = index
    return found


def run(xferextr: Path, acctfile: Path, acctout: Path, xferfee: Path) -> StepResult:
    sysout: list[str] = []
    accounts = read_records(acctfile, CVACT01Y)[:TABLE_LIMIT]  # BR-6
    # 1000-LOAD-MASTER never copies FILLER; NEW-ACCT is written from the
    # uninitialised FD area, so the output FILLER bytes are 0x00 (BR-14).
    for account in accounts:
        account.set_raw("FILLER@122", bytes(178))

    transfer_count = 0
    fee_total = Decimal("0.00")
    fee_records = bytearray()
    conn = None
    try:
        try:
            conn = connect()
        except psycopg2.Error as error:
            sysout.append(f"XFERFEE: DATABASE CONNECT FAILED {error.pgcode or ''}")
            raise Abend from error
        cursor = conn.cursor()
        for xfr in read_records(xferextr, CVXFR01Y):
            # 2100-POST-ONE (XFERFEE.cbl:174-244)
            tran_id = xfr.text("XFR-TRAN-ID")
            tran_dt = xfr.text("XFR-TRAN-DT")
            book_id = xfr.text("XFR-BOOK-ID")
            src_id = int(xfr.raw("XFR-SRC-ACCT-ID"))
            tgt_id = int(xfr.raw("XFR-TGT-ACCT-ID"))
            amount = xfr.number("XFR-TRAN-AMT")

            fee = Decimal("0.00")
            cap_applied = "N"
            rule = select_rule(cursor, book_id, tran_dt, sysout)  # BR-7 (also for zero amounts, BR-10)
            if amount != 0:  # BR-10
                fee = rounded_cents(amount * rule.fee_pct)  # BR-8
                if fee > rule.fee_cap:  # BR-9 (strict)
                    fee = rule.fee_cap
                    cap_applied = "Y"

            src = find_account(accounts, src_id)  # BR-11
            tgt = find_account(accounts, tgt_id)
            if src is None or tgt is None:
                sysout.append(f"XFERFEE: ACCOUNT NOT FOUND {src_id:011d} / {tgt_id:011d}")
                raise Abend
            source, target = accounts[src], accounts[tgt]
            # BR-12, in COBOL statement order (source and target may be the same row)
            source.set_number("ACCT-CURR-BAL", source.number("ACCT-CURR-BAL") - amount)
            source.set_number("ACCT-CURR-BAL", source.number("ACCT-CURR-BAL") - fee)
            target.set_number("ACCT-CURR-BAL", target.number("ACCT-CURR-BAL") + amount)
            target.set_number("ACCT-CURR-CYC-CREDIT", target.number("ACCT-CURR-CYC-CREDIT") + amount)
            source.set_number("ACCT-CURR-CYC-DEBIT", source.number("ACCT-CURR-CYC-DEBIT") + amount + fee)

            # BR-13: fee record (CVXFR02Y) ...
            xfe = Record(CVXFR02Y)
            xfe.set_raw("XFE-TRAN-ID", xfr.raw("XFR-TRAN-ID"))
            xfe.set_raw("XFE-TRAN-DT", xfr.raw("XFR-TRAN-DT"))
            xfe.set_raw("XFE-SRC-ACCT-ID", xfr.raw("XFR-SRC-ACCT-ID"))
            xfe.set_raw("XFE-TGT-ACCT-ID", xfr.raw("XFR-TGT-ACCT-ID"))
            xfe.set_raw("XFE-BOOK-ID", xfr.raw("XFR-BOOK-ID"))
            xfe.set_number("XFE-TRAN-AMT", amount)
            xfe.set_number("XFE-FEE-PCT", rule.fee_pct)
            xfe.set_number("XFE-FEE-AMT", fee)
            xfe.set_text("XFE-CAP-APPLIED", cap_applied)
            xfe.set_text("XFE-RULE-EFF-DT", rule.eff_dt)
            fee_records += xfe.to_bytes()
            # ... then the ledger row
            try:
                cursor.execute(SQL_INSERT_LEDGER, {
                    "tran_id": tran_id,
                    "tran_dt": tran_dt,
                    "src_acct_id": src_id,
                    "tgt_acct_id": tgt_id,
                    "book_id": book_id,
                    "tran_amt": amount,
                    "fee_amt": fee,
                    "cap_applied": cap_applied,
                })
            except psycopg2.Error as error:
                sysout.append(f"XFERFEE: LEDGER INSERT FAILED {error.pgcode or ''}")
                raise Abend from error
            transfer_count += 1  # BR-14
            fee_total = fit_picture(fee_total + fee, 11, 2)  # WS-FEE-TOTAL S9(09)V99

        # 3000-WRITE-MASTER then COMMIT (BR-14)
        acctout.parent.mkdir(parents=True, exist_ok=True)
        acctout.write_bytes(b"".join(a.to_bytes() for a in accounts))
        xferfee.parent.mkdir(parents=True, exist_ok=True)
        xferfee.write_bytes(bytes(fee_records))
        conn.commit()
    except Abend:
        if conn is not None:
            conn.rollback()
        # Records already written before the abend stay in the new generation (spec Q5/Q14).
        for path, content in ((acctout, b""), (xferfee, bytes(fee_records))):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        sysout.append("XFERFEE: 9999-ABEND-PROGRAM")
        return StepResult(8, sysout)
    finally:
        if conn is not None:
            conn.close()

    sysout.append(f"XFERFEE: TRANSFERS POSTED {transfer_count:09d}")
    sysout.append(f"XFERFEE: TOTAL FEES {display_signed(fee_total, 11, 2)}")
    return StepResult(0, sysout)
