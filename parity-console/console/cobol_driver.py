"""Runs the real compiled XFRDAILY chain on GnuCOBOL, one transaction per micro-batch.

Each call writes a one-record DALYTRAN.PS, runs jcl/XFRDAILY.jcl (PROC XFERFEEP:
CBXFR01C -> XFERFEE -> CBXFR03C) through the estate's own JCL runner, then reads
back what the COBOL produced: the XFER.FEES record, the XFER_FEE_LEDGER row the
COBOL inserted over embedded SQL, and the new ACCTDATA.XFER generation. That
generation becomes the next micro-batch's ACCTDATA.PS, so account state carries
forward exactly as it would across daily runs.
"""

from __future__ import annotations

import contextlib
import io
import shutil
import time
from pathlib import Path
from typing import Any

from common import (
    ROOT, TRANSFER_TYPE, WORK, money, pct, psql_exec, psql_rows, records, sql_literal,
)
from tools.parity.recorder import parse_rc
from tools.runjcl.runjcl import Runner

HLQ = "AWS.M2.CARDDEMO"
GDG_LIMIT = 5
OUTPUTS = {
    "extract": f"{HLQ}.XFER.EXTRACT",
    "acctdata": f"{HLQ}.ACCTDATA.XFER",
    "fees": f"{HLQ}.XFER.FEES",
    "recon": f"{HLQ}.XFER.RECON.RPT",
}


class CobolChainDriver:
    def __init__(self, work: Path = WORK / "cobol"):
        self.work = work
        self.datasets = work / "datasets"
        self.joblog = work / "joblog"

    def ensure_built(self) -> None:
        if not (ROOT / "loadlib" / "XFERFEE.so").exists():
            import subprocess
            subprocess.run(["sh", "tools/build.sh"], cwd=ROOT, check=True)

    def reset(self, inputs: Path) -> None:
        for path in (self.datasets, self.joblog):
            if path.exists():
                shutil.rmtree(path)
            path.mkdir(parents=True)
        shutil.copyfile(inputs / "ACCTDATA.PS", self.datasets / f"{HLQ}.ACCTDATA.PS")
        shutil.copyfile(inputs / "CARDXREF.PS", self.datasets / f"{HLQ}.CARDXREF.PS")
        psql_exec("TRUNCATE TABLE XFER_FEE_LEDGER")
        with contextlib.redirect_stdout(io.StringIO()):
            Runner(self.datasets, self.joblog / "setup").run_job(ROOT / "jcl" / "DEFGDGX.jcl")

    def _generation(self, runner: Runner, dsn: str) -> int:
        return runner.gdg_current(self.datasets / dsn)

    def _generation_file(self, dsn: str, generation: int) -> Path:
        return self.datasets / f"{dsn}.G{generation:04d}V00"

    def accounts(self) -> dict[int, dict[str, Any]]:
        return {
            int(row["ACCT-ID"]): row
            for row in records(self.datasets / f"{HLQ}.ACCTDATA.PS", "CVACT01Y")
        }

    def ledger(self) -> list[dict[str, str]]:
        return psql_rows(
            "SELECT TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, BOOK_ID, TRAN_AMT, "
            "FEE_AMT, CAP_APPLIED FROM XFER_FEE_LEDGER ORDER BY POSTED_TS, TRAN_ID"
        )

    def process(self, record: bytes, tran: dict[str, Any]) -> dict[str, Any]:
        (self.datasets / f"{HLQ}.DALYTRAN.PS").write_bytes(record)
        job_dir = self.joblog / "run"
        if job_dir.exists():
            shutil.rmtree(job_dir)
        runner = Runner(self.datasets, job_dir)
        before = {key: self._generation(runner, dsn) for key, dsn in OUTPUTS.items()}
        started = time.perf_counter()
        jes = io.StringIO()
        with contextlib.redirect_stdout(jes):
            maxcc = runner.run_job(ROOT / "jcl" / "XFRDAILY.jcl")
        elapsed = round((time.perf_counter() - started) * 1000)
        after = {key: self._generation(runner, dsn) for key, dsn in OUTPUTS.items()}
        new = {
            key: self._generation_file(OUTPUTS[key], after[key])
            for key in OUTPUTS if after[key] > before[key]
        }
        rc = parse_rc(job_dir / "XFRDAILY.log")
        extract = records(new["extract"], "CVXFR01Y") if "extract" in new else []
        fees = records(new["fees"], "CVXFR02Y") if "fees" in new else []
        accounts = (
            {int(r["ACCT-ID"]): r for r in records(new["acctdata"], "CVACT01Y")}
            if "acctdata" in new else self.accounts()
        )
        if "acctdata" in new:
            shutil.copyfile(new["acctdata"], self.datasets / f"{HLQ}.ACCTDATA.PS")
        self._prune(after)

        sysout = {}
        for step in ("STEP010", "STEP020", "STEP030"):
            path = job_dir / "XFRDAILY" / f"{step}.SYSOUT"
            if path.exists():
                sysout[step] = path.read_text().rstrip()
        side: dict[str, Any] = {
            "engine": "GnuCOBOL XFRDAILY",
            "durationMs": elapsed,
            "steps": rc["steps"],
            "maxcc": maxcc,
            "sysout": sysout,
            "jes": jes.getvalue().rstrip().splitlines()[-12:],
            "recon": new["recon"].read_text().rstrip() if "recon" in new else None,
        }
        if maxcc >= 8:
            side.update(outcome="REJECTED", reason=_abend_reason(sysout))
            return side
        if not extract:
            if tran["typeCd"] != TRANSFER_TYPE:
                side.update(outcome="IGNORED",
                            reason=f"CBXFR01C: TRAN-TYPE-CD {tran['typeCd']} not selected")
            else:
                side.update(outcome="SKIPPED", reason=_abend_reason(sysout))
            return side
        fee = fees[0]
        book = fee["XFE-BOOK-ID"].strip()
        source_id = int(fee["XFE-SRC-ACCT-ID"])
        target_id = int(fee["XFE-TGT-ACCT-ID"])
        rule_rows = psql_rows(
            "SELECT FEE_PCT, FEE_CAP, EFF_DT, EXP_DT FROM CTL_XFER_PARM "
            f"WHERE BOOK_ID = {sql_literal(book)} "
            f"AND EFF_DT = DATE {sql_literal(fee['XFE-RULE-EFF-DT'])}"
        )
        ledger_rows = psql_rows(
            "SELECT TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, BOOK_ID, TRAN_AMT, "
            "FEE_AMT, CAP_APPLIED FROM XFER_FEE_LEDGER "
            f"WHERE TRAN_ID = {sql_literal(fee['XFE-TRAN-ID'])}"
        )
        source, target = accounts[source_id], accounts[target_id]
        side.update(
            outcome="POSTED",
            reason="posted",
            book=book,
            businessDate=fee["XFE-TRAN-DT"],
            rule={
                "effDt": fee["XFE-RULE-EFF-DT"],
                "pct": pct(fee["XFE-FEE-PCT"]),
                "cap": money(rule_rows[0]["FEE_CAP"]) if rule_rows else None,
            },
            amount=money(fee["XFE-TRAN-AMT"]),
            fee=money(fee["XFE-FEE-AMT"]),
            capApplied=fee["XFE-CAP-APPLIED"],
            source={"id": source_id, "bal": money(source["ACCT-CURR-BAL"]),
                    "cycDebit": money(source["ACCT-CURR-CYC-DEBIT"])},
            target={"id": target_id, "bal": money(target["ACCT-CURR-BAL"]),
                    "cycCredit": money(target["ACCT-CURR-CYC-CREDIT"])},
            ledger=_ledger(ledger_rows[0]) if ledger_rows else None,
        )
        return side

    def _prune(self, current: dict[str, int]) -> None:
        for key, dsn in OUTPUTS.items():
            for generation in range(1, current[key] - GDG_LIMIT + 1):
                self._generation_file(dsn, generation).unlink(missing_ok=True)


def _ledger(row: dict[str, str]) -> dict[str, str]:
    return {
        "TRAN_ID": row["TRAN_ID"].strip(),
        "TRAN_DT": row["TRAN_DT"],
        "SRC_ACCT_ID": str(int(row["SRC_ACCT_ID"])),
        "TGT_ACCT_ID": str(int(row["TGT_ACCT_ID"])),
        "BOOK_ID": row["BOOK_ID"].strip(),
        "TRAN_AMT": money(row["TRAN_AMT"]),
        "FEE_AMT": money(row["FEE_AMT"]),
        "CAP_APPLIED": row["CAP_APPLIED"],
    }


def _abend_reason(sysout: dict[str, str]) -> str:
    lines = [line for text in sysout.values() for line in text.splitlines()]
    flagged = [line for line in lines if "NOT FOUND" in line or "NO FEE RULE" in line
               or "FAILED" in line or "ABEND" in line]
    return "; ".join(flagged) or "chain ended with a non-zero condition code"
