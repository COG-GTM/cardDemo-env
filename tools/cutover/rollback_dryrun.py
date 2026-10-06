#!/usr/bin/env python3
"""Cut-over rollback dry run on the Compose stack (docs/cutover/ROLLBACK.md).

Runs inside the `estate` container (`make cutover-rollback-dryrun`):

  A. Coexistence day N: produce an ACCTDATA.XFER generation plus ledger rows.
     Until COG-1240 (legacy-adapter egress) lands this is a STAND-IN: the COBOL
     chain itself writes the generation. Pass --adapter-generation to swap in a
     real legacy-adapter file for that generation.
  B. Rollback: locate ACCTDATA.XFER(0), reconcile it against XFER_FEE_LEDGER,
     back up ACCTDATA.PS, restore with ops/cutover/XFRRBACK.jcl, re-enable
     XFRDAILY on day N+1 and reconcile again.

Everything runs in work/cutover/ (isolated datasets). XFER_FEE_LEDGER is
truncated at the start, as `make run` does. Exit code 0 only if every step passes.
"""

from __future__ import annotations

import argparse
import csv
import json
import hashlib
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "parity"))
sys.path.insert(0, str(ROOT / "tools" / "fixtures"))

from copybook import decode_record  # noqa: E402
from gen_fixtures import transaction, transfer  # noqa: E402

HLQ = "AWS.M2.CARDDEMO"
ACCT_LRECL = 300
FEES_LRECL = 100
RUNJCL = ROOT / "tools" / "runjcl" / "runjcl.py"
RECON_SQL = ROOT / "ops" / "cutover" / "ledger_recon.sql"
ROLLBACK_JCL = ROOT / "ops" / "cutover" / "XFRRBACK.jcl"
CARDS = [f"{n:016d}" for n in range(1000000000000001, 1000000000000009)]
NEXT_DAY = "2024-07-01"


class DryRun:
    def __init__(self, work: Path, log_path: Path):
        self.work = work
        self.datasets = work / "datasets"
        self.log_path = log_path
        self.results: list[tuple[str, bool, str]] = []
        log_path.parent.mkdir(parents=True, exist_ok=True)
        self.stream = log_path.open("w")

    def log(self, message: str = "") -> None:
        stamp = datetime.now(timezone.utc).strftime("%H:%M:%S")
        for line in (message.splitlines() or [""]):
            text = f"{stamp} {line}"
            print(text, flush=True)
            self.stream.write(text + "\n")
        self.stream.flush()

    def record(self, step: str, ok: bool, detail: str) -> bool:
        self.results.append((step, ok, detail))
        self.log(f"==> {step}: {'PASS' if ok else 'FAIL'} - {detail}")
        return ok

    def run(self, args: list[str], stdin: str | None = None) -> tuple[int, str]:
        self.log("$ " + " ".join(str(a) for a in args))
        proc = subprocess.run(args, cwd=ROOT, input=stdin, capture_output=True, text=True)
        output = (proc.stdout + proc.stderr).rstrip()
        for line in output.splitlines():
            self.log("    " + line)
        return proc.returncode, output

    def runjcl(self, job: Path, *extra: str) -> int:
        rc, _ = self.run([sys.executable, str(RUNJCL), "--job", str(job.relative_to(ROOT)),
                          "--datasets", str(self.datasets.relative_to(ROOT)),
                          "--joblog-dir", str((self.work / "joblog").relative_to(ROOT)),
                          *extra])
        return rc

    def dsn(self, name: str) -> Path:
        return self.datasets / f"{HLQ}.{name}"

    def generation(self, base: str) -> tuple[int, Path]:
        meta = self.dsn(base).with_name(f"{HLQ}.{base}.gdg")
        if not meta.exists():
            return 0, self.dsn(base)
        current = int(json.loads(meta.read_text()).get("current", 0))
        return current, self.datasets / f"{HLQ}.{base}.G{current:04d}V00"

    def db_now(self) -> str:
        rc, out = self.run(["psql", "-At", "-v", "ON_ERROR_STOP=1", "-c", "SELECT LOCALTIMESTAMP"])
        if rc:
            raise SystemExit("cannot reach PostgreSQL")
        return out.strip().splitlines()[-1]

    def recon(self, label: str, base: Path, gen: Path, fees: Path, since_ts: str) -> int:
        stage = self.work / "recon" / label
        stage.mkdir(parents=True, exist_ok=True)
        write_accounts(base, stage / "acct_base.csv")
        write_accounts(gen, stage / "acct_gen.csv")
        write_fees(fees, stage / "fees_gen.csv")
        script = "\n".join([
            "\\set ON_ERROR_STOP 1",
            "\\pset footer off",
            f"\\set since_ts '{since_ts}'",
            "CREATE TEMP TABLE ACCT_BASE (ACCT_ID DECIMAL(11,0), CURR_BAL DECIMAL(12,2),"
            " CYC_CREDIT DECIMAL(12,2), CYC_DEBIT DECIMAL(12,2));",
            "CREATE TEMP TABLE ACCT_GEN (LIKE ACCT_BASE);",
            "CREATE TEMP TABLE FEES_GEN (TRAN_ID CHAR(16), TRAN_DT DATE,"
            " SRC_ACCT_ID DECIMAL(11,0), TGT_ACCT_ID DECIMAL(11,0), BOOK_ID CHAR(10),"
            " TRAN_AMT DECIMAL(11,2), FEE_AMT DECIMAL(11,2), CAP_APPLIED CHAR(1));",
            f"\\copy ACCT_BASE FROM '{stage / 'acct_base.csv'}' WITH (FORMAT csv)",
            f"\\copy ACCT_GEN FROM '{stage / 'acct_gen.csv'}' WITH (FORMAT csv)",
            f"\\copy FEES_GEN FROM '{stage / 'fees_gen.csv'}' WITH (FORMAT csv)",
            f"\\i {RECON_SQL.relative_to(ROOT)}",
            "",
        ])
        self.log(f"ledger recon [{label}]: base={base.name} gen={gen.name} "
                 f"fees={fees.name} since_ts={since_ts}")
        rc, out = self.run(["psql", "-X", "-q", "-f", "-"], stdin=script)
        if rc:
            return -1
        for line in out.splitlines():
            if "RECON_BREAKS=" in line:
                return int(line.split("RECON_BREAKS=")[1].split()[0])
        return -1

    def summary(self) -> int:
        self.log()
        self.log("| Step | Result | Detail |")
        self.log("|---|---|---|")
        for step, ok, detail in self.results:
            self.log(f"| {step} | {'PASS' if ok else 'FAIL'} | {detail} |")
        failed = [step for step, ok, _ in self.results if not ok]
        self.log()
        self.log("ROLLBACK DRY RUN: " + ("PASS" if not failed else "FAIL (" + ", ".join(failed) + ")"))
        return 1 if failed else 0


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def records(path: Path, lrecl: int) -> list[bytes]:
    data = path.read_bytes()
    if len(data) % lrecl:
        raise ValueError(f"{path.name}: {len(data)} bytes is not a multiple of LRECL {lrecl}")
    return [data[i:i + lrecl] for i in range(0, len(data), lrecl)]


def write_accounts(path: Path, out: Path) -> None:
    with out.open("w", newline="") as stream:
        writer = csv.writer(stream)
        for raw in records(path, ACCT_LRECL):
            row = decode_record("CVACT01Y", raw)
            writer.writerow([row["ACCT-ID"], row["ACCT-CURR-BAL"],
                             row["ACCT-CURR-CYC-CREDIT"], row["ACCT-CURR-CYC-DEBIT"]])


def write_fees(path: Path, out: Path) -> None:
    with out.open("w", newline="") as stream:
        writer = csv.writer(stream)
        for raw in records(path, FEES_LRECL):
            row = decode_record("CVXFR02Y", raw)
            writer.writerow([row["XFE-TRAN-ID"], row["XFE-TRAN-DT"], row["XFE-SRC-ACCT-ID"],
                             row["XFE-TGT-ACCT-ID"], row["XFE-BOOK-ID"], row["XFE-TRAN-AMT"],
                             row["XFE-FEE-AMT"], row["XFE-CAP-APPLIED"] or "N"])


def next_day_dalytran() -> bytes:
    return b"".join([
        transfer("TRN0000000000101", 250.00, 1, 3, CARDS[0], NEXT_DAY),
        transaction("TRN0000000000102", "01", 12.34, CARDS[2], NEXT_DAY, "POS PURCHASE"),
        transfer("TRN0000000000103", 40000.00, 6, 8, CARDS[5], NEXT_DAY),
    ])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="default", help="fixture used as coexistence day N")
    parser.add_argument("--adapter-generation", type=Path,
                        help="legacy-adapter ACCTDATA.XFER file to use instead of the stand-in")
    parser.add_argument("--work", type=Path, default=ROOT / "work" / "cutover")
    parser.add_argument("--log", type=Path)
    args = parser.parse_args()
    work = args.work if args.work.is_absolute() else ROOT / args.work
    if work.exists():
        shutil.rmtree(work)
    dry = DryRun(work, args.log or work / "rollback-dryrun.log")
    rev = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT,
                         capture_output=True, text=True).stdout.strip() or "unknown"
    source = (f"legacy-adapter file {args.adapter_generation}" if args.adapter_generation
              else "STAND-IN (COBOL chain output; legacy-adapter egress = COG-1240, not built yet)")
    dry.log(f"XFRDAILY cut-over rollback dry run  rev={rev}  case={args.case}")
    dry.log(f"ACCTDATA.XFER generation source: {source}")
    dry.log(f"work dir: {work.relative_to(ROOT)}")
    dry.log()

    dry.log("---- A. coexistence day N (Java owns posting; XFRDAILY held) ----")
    rc = dry.runjcl(ROOT / "jcl" / "DEFGDGX.jcl", "--load-fixtures", args.case, "--db-reset")
    if not dry.record("A1 stage day-N inputs + GDG bases", rc == 0, f"DEFGDGX MAXCC={rc:04d}"):
        return dry.summary()
    baseline = work / "backup" / "ACCTDATA.PS.day-N-open"
    baseline.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(dry.dsn("ACCTDATA.PS"), baseline)
    since_n = dry.db_now()
    rc = dry.runjcl(ROOT / "jcl" / "XFRDAILY.jcl")
    gen_no, gen_path = dry.generation("ACCTDATA.XFER")
    if args.adapter_generation:
        shutil.copyfile(args.adapter_generation, gen_path)
    dry.record("A2 day-N posting -> ACCTDATA.XFER(+1) + ledger", rc == 0 and gen_no > 0,
               f"MAXCC={rc:04d}; cataloged {gen_path.name}")
    if rc:
        return dry.summary()

    dry.log()
    dry.log("---- B. rollback ----")
    gen_no, gen_path = dry.generation("ACCTDATA.XFER")
    try:
        accts = records(gen_path, ACCT_LRECL)
        for raw in accts:
            decode_record("CVACT01Y", raw)
        ok = bool(accts)
        detail = f"{gen_path.name} records={len(accts)} sha256={sha256(gen_path)[:16]}"
    except (ValueError, FileNotFoundError) as exc:
        ok, detail = False, str(exc)
    if not dry.record("B1 locate ACCTDATA.XFER(0)", ok, detail):
        return dry.summary()

    _, fees_path = dry.generation("XFER.FEES")
    breaks = dry.recon("pre-restore", baseline, gen_path, fees_path, since_n)
    if not dry.record("B2 ledger reconciliation (pre-restore)", breaks == 0,
                      f"RECON_BREAKS={breaks}"):
        return dry.summary()

    tampered = work / "recon" / "selftest.ACCTDATA.XFER"
    tampered.parent.mkdir(parents=True, exist_ok=True)
    tampered.write_bytes(b"".join(accts[:-1]))
    breaks_neg = dry.recon("self-test-dropped-record", baseline, tampered, fees_path, since_n)
    dry.record("B2a recon self-test (generation with last record dropped)", breaks_neg > 0,
               f"RECON_BREAKS={breaks_neg} (expected > 0)")

    backup = work / "backup" / "ACCTDATA.PS.pre-rollback"
    shutil.copyfile(dry.dsn("ACCTDATA.PS"), backup)
    dry.record("B3 back up ACCTDATA.PS", sha256(backup) == sha256(dry.dsn("ACCTDATA.PS")),
               f"{backup.relative_to(ROOT)} sha256={sha256(backup)[:16]}")

    rc = dry.runjcl(ROLLBACK_JCL)
    restored = sha256(dry.dsn("ACCTDATA.PS")) == sha256(gen_path)
    if not dry.record("B4 XFRRBACK restore ACCTDATA.PS <- ACCTDATA.XFER(0)", rc == 0 and restored,
                      f"MAXCC={rc:04d}; ACCTDATA.PS sha256 == {gen_path.name}: {restored}"):
        return dry.summary()

    dry.log()
    dry.log(f"---- C. re-enable XFRDAILY (day N+1 = {NEXT_DAY}) ----")
    dry.dsn("DALYTRAN.PS").write_bytes(next_day_dalytran())
    reopen = work / "backup" / "ACCTDATA.PS.day-N+1-open"
    shutil.copyfile(dry.dsn("ACCTDATA.PS"), reopen)
    since_n1 = dry.db_now()
    rc = dry.runjcl(ROOT / "jcl" / "XFRDAILY.jcl")
    new_no, new_gen = dry.generation("ACCTDATA.XFER")
    if not dry.record("C1 XFRDAILY re-enabled", rc == 0 and new_no == gen_no + 1,
                      f"MAXCC={rc:04d}; cataloged {new_gen.name}"):
        return dry.summary()
    _, new_fees = dry.generation("XFER.FEES")
    breaks = dry.recon("post-reenable", reopen, new_gen, new_fees, since_n1)
    dry.record("C2 ledger reconciliation (day N+1)", breaks == 0, f"RECON_BREAKS={breaks}")
    _, rpt = dry.generation("XFER.RECON.RPT")
    if rpt.exists():
        dry.log(f"{rpt.name}:")
        for line in rpt.read_text(errors="replace").rstrip().splitlines():
            dry.log("    " + line)
    return dry.summary()


if __name__ == "__main__":
    raise SystemExit(main())
