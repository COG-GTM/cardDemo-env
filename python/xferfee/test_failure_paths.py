"""Failure-path coverage for the Python ``xferfee`` chain (RC 4 / RC 8 / overflow).

The seven recorded fixtures all end MAXCC=0, so these tests derive failing inputs
from the ``default`` fixture (never touching ``fixtures/``) and assert the
behaviour the spec documents for BR-5, BR-7, BR-11, BR-17 and BR-18, plus the
COBOL picture-width semantics of the fee accumulators.

Run inside the estate container (needs the Compose PostgreSQL)::

    python3 python/xferfee/test_failure_paths.py -v
"""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import psycopg2  # noqa: E402

from xferfee import post_fees, run_chain  # noqa: E402
from xferfee.cobol_numeric import fit_picture, rounded_cents  # noqa: E402
from xferfee.layouts import CVTRA05Y  # noqa: E402

HLQ = run_chain.HLQ
DALYTRAN = run_chain.INPUT_DSNS["DALYTRAN.PS"]
CARDXREF = run_chain.INPUT_DSNS["CARDXREF.PS"]


def _field(name: str) -> slice:
    field = next(f for f in CVTRA05Y.fields if f.name == name)
    return slice(field.offset, field.offset + field.length)


class ChainCase(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="xferfee-fail-"))
        self.datasets = self.tmp / "datasets"
        self.joblog = self.tmp / "joblog"
        self.candidate = self.tmp / "candidate"
        run_chain.load_fixtures("default", self.datasets)
        run_chain.reset_db()

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_job(self) -> tuple[dict[str, int], int, list[str]]:
        log: list[str] = []
        results, maxcc, outputs = run_chain.run_job(self.datasets, self.joblog, log.append)
        log.append(f"$HASP395 XFRDAILY ENDED - MAXCC={maxcc:04d}")
        run_chain.write_candidate(self.candidate, self.joblog, outputs, results, maxcc)
        return results, maxcc, log

    def edit_transfers(self, mutate) -> None:
        path = self.datasets / DALYTRAN
        raw = path.read_bytes()
        records = [bytearray(raw[i:i + CVTRA05Y.length]) for i in range(0, len(raw), CVTRA05Y.length)]
        for record in records:
            if bytes(record[_field("TRAN-TYPE-CD")]) == b"08":
                mutate(record)
        path.write_bytes(b"".join(records))

    def gdg_current(self, base: str) -> int:
        return json.loads((self.datasets / f"{base}.gdg").read_text())["current"]

    def ledger_rows(self) -> int:
        with post_fees.connect() as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT COUNT(*) FROM XFER_FEE_LEDGER")
            return cursor.fetchone()[0]

    def candidate_datasets(self) -> set[str]:
        return {p.name for p in (self.candidate / "datasets").iterdir()}

    def rc_json(self) -> dict:
        return json.loads((self.candidate / "rc.json").read_text())

    # BR-5 / BR-18: unmatched card -> STEP010 RC 4, job stops, extract kept as evidence
    def test_unmatched_card_rc4(self) -> None:
        (self.datasets / CARDXREF).write_bytes(b"")
        results, maxcc, log = self.run_job()
        self.assertEqual(results, {"STEP010": 4})
        self.assertEqual(maxcc, 4)
        self.assertEqual(self.rc_json(), {"steps": {"STEP010": 4}, "maxcc": 4})
        self.assertEqual(self.candidate_datasets(), {f"{HLQ}.XFER.EXTRACT.G0001V00"})
        self.assertEqual(self.gdg_current(f"{HLQ}.XFER.EXTRACT"), 0)  # not catalogued
        self.assertIn("IEF142I XFRDAILY STEP010 - STEP WAS EXECUTED - COND CODE 0004", log)
        self.assertEqual(self.ledger_rows(), 0)

    # BR-17: no transfers -> empty fee file -> STEP030 RC 4, report headers preserved
    def test_empty_fee_file_rc4(self) -> None:
        (self.datasets / DALYTRAN).write_bytes(b"")
        results, maxcc, _ = self.run_job()
        self.assertEqual(results, {"STEP010": 0, "STEP020": 0, "STEP030": 4})
        self.assertEqual(maxcc, 4)
        report = f"{HLQ}.XFER.RECON.RPT.G0001V00"
        self.assertIn(report, self.candidate_datasets())
        self.assertEqual((self.candidate / "datasets" / report).read_text().splitlines(),
                         [" TRANSFER FEE RECONCILIATION",
                          " TRANSACTION       DATE       BOOK       AMOUNT          FEE"])
        self.assertEqual((self.candidate / "sysout" / "STEP030.txt").read_text(),
                         "CBXFR03C: NO FEE RECORDS\n")
        self.assertEqual(self.gdg_current(f"{HLQ}.XFER.FEES"), 1)
        self.assertEqual(self.gdg_current(f"{HLQ}.XFER.RECON.RPT"), 0)

    # BR-7: no rule in effect on the transaction date -> RC 8, rollback, STEP030 not run
    def test_no_fee_rule_rc8(self) -> None:
        def backdate(record: bytearray) -> None:
            start = _field("TRAN-ORIG-TS").start
            record[start:start + 10] = b"2019-01-01"
        self.edit_transfers(backdate)
        results, maxcc, _ = self.run_job()
        self.assertEqual(results, {"STEP010": 0, "STEP020": 8})
        self.assertEqual(maxcc, 8)
        self.assertIn("XFERFEE: NO FEE RULE FOR BOOK RETAIL",
                      (self.candidate / "sysout" / "STEP020.txt").read_text())
        self.assertEqual(self.ledger_rows(), 0)
        self.assertEqual(self.gdg_current(f"{HLQ}.ACCTDATA.XFER"), 0)
        self.assertEqual(self.gdg_current(f"{HLQ}.XFER.FEES"), 0)
        self.assertTrue({f"{HLQ}.ACCTDATA.XFER.G0001V00", f"{HLQ}.XFER.FEES.G0001V00"}
                        <= self.candidate_datasets())

    # BR-11: unknown target account -> RC 8 and nothing committed
    def test_unknown_target_account_rc8(self) -> None:
        def retarget(record: bytearray) -> None:
            start = _field("TRAN-DESC").start + 13
            record[start:start + 11] = b"99999999999"
        self.edit_transfers(retarget)
        results, maxcc, _ = self.run_job()
        self.assertEqual(results, {"STEP010": 0, "STEP020": 8})
        self.assertEqual(maxcc, 8)
        self.assertIn("XFERFEE: ACCOUNT NOT FOUND",
                      (self.candidate / "sysout" / "STEP020.txt").read_text())
        self.assertEqual(self.ledger_rows(), 0)

    # Rule lookup DB error (invalid date literal) -> Abend path, RC 8
    def test_rule_lookup_db_error_rc8(self) -> None:
        def corrupt_date(record: bytearray) -> None:
            start = _field("TRAN-ORIG-TS").start
            record[start:start + 10] = b"2024-13-45"
        self.edit_transfers(corrupt_date)
        results, maxcc, _ = self.run_job()
        self.assertEqual(results, {"STEP010": 0, "STEP020": 8})
        self.assertEqual(maxcc, 8)
        self.assertIn("XFERFEE: RULE LOOKUP FAILED",
                      (self.candidate / "sysout" / "STEP020.txt").read_text())
        self.assertEqual(self.ledger_rows(), 0)
        self.assertEqual(self.rc_json()["maxcc"], 8)


class SelectRuleErrors(unittest.TestCase):
    def test_psycopg2_error_becomes_abend(self) -> None:
        with post_fees.connect() as conn:
            sysout: list[str] = []
            with self.assertRaises(post_fees.Abend):
                post_fees.select_rule(conn.cursor(), "RETAIL", "not-a-date", sysout)
            self.assertEqual(len(sysout), 1)
            self.assertTrue(sysout[0].startswith("XFERFEE: RULE LOOKUP FAILED "))
            conn.rollback()

    def test_error_is_psycopg2_error(self) -> None:
        with post_fees.connect() as conn:
            with self.assertRaises(psycopg2.Error):
                conn.cursor().execute("SELECT CAST('x' AS DATE)")
            conn.rollback()


class FixedWidthAccumulators(unittest.TestCase):
    """``PIC S9(09)V99``: 11 digits, high-order truncation, sign kept."""

    def test_within_picture(self) -> None:
        self.assertEqual(fit_picture(Decimal("999999999.99"), 11, 2), Decimal("999999999.99"))

    def test_positive_overflow(self) -> None:
        self.assertEqual(fit_picture(Decimal("1000000000.01"), 11, 2), Decimal("0.01"))
        self.assertEqual(fit_picture(Decimal("1234567890.12"), 11, 2), Decimal("234567890.12"))

    def test_negative_overflow(self) -> None:
        self.assertEqual(fit_picture(Decimal("-1000000000.01"), 11, 2), Decimal("-0.01"))

    def test_fee_truncated_before_cap(self) -> None:
        # WS-FEE-AMT overflow: 999,999,999.99 * 1.1 -> 1,099,999,999.99 -> 99,999,999.99 (< cap)
        fee = fit_picture(rounded_cents(Decimal("999999999.99") * Decimal("1.100000")), 11, 2)
        self.assertEqual(fee, Decimal("99999999.99"))
        self.assertFalse(fee > Decimal("500000000.00"))

    def test_sign_change(self) -> None:
        self.assertEqual(fit_picture(Decimal("5.00") + Decimal("-7.25"), 11, 2), Decimal("-2.25"))
        self.assertEqual(fit_picture(Decimal("-5.00") + Decimal("5.00"), 11, 2), Decimal("0.00"))


if __name__ == "__main__":
    unittest.main()
