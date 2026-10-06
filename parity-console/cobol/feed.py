#!/usr/bin/env python3
"""Stream daily transactions, one at a time, through the real XFRDAILY chain on GnuCOBOL.

Runs inside the estate container (``docker compose exec -T estate``). Protocol is JSON lines:

  stdin : {"seq": 1, "record": "<base64 350-byte CVTRA05Y record>"}
  stdout: {"event": "ready", ...} once, then {"event": "result", "seq": 1, ...} per record.

Every record gets its own XFRDAILY run (CBXFR01C -> XFERFEE -> CBXFR03C) against the account
master produced by the previous run, so balances carry forward exactly as the batch would.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "parity"))

from copybook import decode_record  # noqa: E402

HLQ = "AWS.M2.CARDDEMO"
ACCT_LEN = 300
TRAN_ID = re.compile(r"^[A-Za-z0-9 ]{16}$")
LEDGER_COLUMNS = (
    "TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, BOOK_ID, TRAN_AMT, FEE_AMT, CAP_APPLIED"
)


def emit(payload: dict) -> None:
    sys.stdout.write(json.dumps(payload, default=str) + "\n")
    sys.stdout.flush()


def plain(value):
    return format(value, "f") if hasattr(value, "as_tuple") else value


def accounts(master: bytes) -> dict[int, dict]:
    rows = {}
    for offset in range(0, len(master) - ACCT_LEN + 1, ACCT_LEN):
        row = decode_record("CVACT01Y", master[offset:offset + ACCT_LEN])
        rows[int(row["ACCT-ID"])] = row
    return rows


def parse_rc(joblog: Path) -> dict:
    steps, maxcc = {}, 0
    if not joblog.exists():
        return {"steps": steps, "maxcc": 12}
    for line in joblog.read_text().splitlines():
        match = re.search(r"IEF142I\s+\S+\s+(\S+)\s+-\s+STEP WAS EXECUTED\s+-\s+COND CODE\s+(\d+)", line)
        if match:
            steps[match.group(1)] = int(match.group(2))
        match = re.search(r"\$HASP395\s+\S+\s+ENDED\s+-\s+MAXCC=(\d+)", line)
        if match:
            maxcc = int(match.group(1))
    return {"steps": steps, "maxcc": maxcc}


def ledger_row(tran_id: str) -> dict | None:
    if not TRAN_ID.match(tran_id):
        return None
    query = f"SELECT {LEDGER_COLUMNS} FROM XFER_FEE_LEDGER WHERE TRAN_ID = '{tran_id}'"
    out = subprocess.run(
        ["psql", "-v", "ON_ERROR_STOP=1", "-At", "-F", ",", "-c", query],
        cwd=ROOT, check=True, capture_output=True, text=True,
    ).stdout.strip()
    if not out:
        return None
    cols = out.split(",")
    keys = ["tranId", "tranDate", "srcAcctId", "tgtAcctId", "bookId", "tranAmt", "feeAmt", "capApplied"]
    return dict(zip(keys, cols))


def newest(manifest: list[dict], dsn: str) -> Path | None:
    paths = [Path(e["path"]) for e in manifest if e["dsn"].startswith(dsn + ".")]
    paths = [p for p in paths if p.exists()]
    return paths[-1] if paths else None


class Feed:
    def __init__(self, case: str) -> None:
        self.case = case
        self.source = ROOT / "fixtures" / "xferfee" / case / "input"
        self.work = ROOT / "work" / "parity-console" / case
        if self.work.exists():
            shutil.rmtree(self.work)
        self.work.mkdir(parents=True)
        self.master = (self.source / "ACCTDATA.PS").read_bytes()
        self.xref = (self.source / "CARDXREF.PS").read_bytes()
        subprocess.run(["psql", "-v", "ON_ERROR_STOP=1", "-q", "-c", "TRUNCATE TABLE XFER_FEE_LEDGER"],
                       cwd=ROOT, check=True, capture_output=True)

    def ready(self) -> dict:
        return {
            "event": "ready",
            "case": self.case,
            "engine": subprocess.run(["cobc", "--version"], capture_output=True, text=True).stdout.splitlines()[0],
            "accounts": [
                {"id": acct_id, "book": str(row["ACCT-GROUP-ID"]).strip(), "balance": plain(row["ACCT-CURR-BAL"])}
                for acct_id, row in accounts(self.master).items()
            ],
        }

    def run(self, seq: int, record: bytes) -> dict:
        started = time.monotonic()
        tran = decode_record("CVTRA05Y", record)
        run_dir = self.work / f"{seq:04d}"
        datasets = run_dir / "datasets"
        joblog = run_dir / "joblog"
        manifest_path = run_dir / "manifest.json"
        datasets.mkdir(parents=True)
        (datasets / f"{HLQ}.ACCTDATA.PS").write_bytes(self.master)
        (datasets / f"{HLQ}.CARDXREF.PS").write_bytes(self.xref)
        (datasets / f"{HLQ}.DALYTRAN.PS").write_bytes(record)
        proc = subprocess.run(
            [sys.executable, str(ROOT / "tools" / "runjcl" / "runjcl.py"), "--chain", "xferfee",
             "--datasets", str(datasets), "--joblog-dir", str(joblog), "--manifest", str(manifest_path)],
            cwd=ROOT, capture_output=True, text=True, env=os.environ.copy(),
        )
        manifest = json.loads(manifest_path.read_text()).get("outputs", []) if manifest_path.exists() else []
        rc = parse_rc(joblog / "XFRDAILY.log")
        sysout = {}
        for step in ("STEP010", "STEP020", "STEP030"):
            path = joblog / "XFRDAILY" / f"{step}.SYSOUT"
            if path.exists():
                sysout[step] = path.read_text().splitlines()

        extract_path = newest(manifest, f"{HLQ}.XFER.EXTRACT")
        selected = bool(extract_path and extract_path.stat().st_size >= 120)
        fee = None
        fees_path = newest(manifest, f"{HLQ}.XFER.FEES")
        if fees_path and fees_path.stat().st_size >= 100:
            fee = {k: plain(v) for k, v in decode_record("CVXFR02Y", fees_path.read_bytes()[:100]).items()}

        result = {
            "event": "result",
            "seq": seq,
            "tranId": str(tran["TRAN-ID"]),
            "typeCode": str(tran["TRAN-TYPE-CD"]),
            "selected": selected,
            "rc": rc,
            "sysout": sysout,
            "extractMessages": [
                line for line in sysout.get("STEP010", []) if "NOT FOUND" in line
            ],
        }
        acct_path = newest(manifest, f"{HLQ}.ACCTDATA.XFER")
        if acct_path and rc["steps"].get("STEP020") == 0:
            self.master = acct_path.read_bytes()
        if fee:
            after = accounts(self.master)
            src, tgt = int(fee["XFE-SRC-ACCT-ID"]), int(fee["XFE-TGT-ACCT-ID"])
            result.update({
                "book": str(fee["XFE-BOOK-ID"]).strip(),
                "amount": fee["XFE-TRAN-AMT"],
                "feePct": fee["XFE-FEE-PCT"],
                "fee": fee["XFE-FEE-AMT"],
                "capApplied": fee["XFE-CAP-APPLIED"],
                "ruleEffDate": str(fee["XFE-RULE-EFF-DT"]).strip(),
                "srcAcctId": src,
                "tgtAcctId": tgt,
                "srcBalanceAfter": plain(after[src]["ACCT-CURR-BAL"]) if src in after else None,
                "tgtBalanceAfter": plain(after[tgt]["ACCT-CURR-BAL"]) if tgt in after else None,
                "ledger": ledger_row(str(tran["TRAN-ID"])),
            })
        if rc["maxcc"] > 4 or proc.returncode not in (0, 4):
            result["error"] = (proc.stdout + proc.stderr)[-2000:]
        result["elapsedMs"] = int((time.monotonic() - started) * 1000)
        return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", required=True)
    args = parser.parse_args()
    if not re.match(r"^[a-z_]+$", args.case):
        emit({"event": "error", "message": f"bad case name {args.case!r}"})
        return 2
    feed = Feed(args.case)
    emit(feed.ready())
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        message = json.loads(line)
        if message.get("cmd") == "quit":
            break
        try:
            emit(feed.run(int(message["seq"]), base64.b64decode(message["record"])))
        except Exception as exc:  # surfaced in the console instead of killing the stream
            emit({"event": "result", "seq": message.get("seq"), "error": repr(exc)})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
