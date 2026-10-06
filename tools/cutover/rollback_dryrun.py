#!/usr/bin/env python3
"""Rehearse the COG-1252 xferfee rollback on the compose stack.

Simulates "Java is system of record, roll back to XFRDAILY":

1. Stage the state after a Java-posted business day: the legacy account master is the
   one frozen at cut-over, the legacy-adapter has catalogued ACCTDATA.XFER(+1) and
   XFER.FEES(+1), and the Java ledger rows are mirrored into XFER_FEE_LEDGER.
2. Pre-flight: generation integrity, ops/cutover/ledger_recon.sql (snapshot vs ledger
   vs fees file, next-day TRAN_ID collision guard) and two negative drills that must
   trip the query.
3. Restore ACCTDATA.PS from ACCTDATA.XFER(0) with ops/cutover/XFRRBACK.jcl.
4. Re-enable XFRDAILY for the next business day and run it.
5. Post-run reconciliation of the first legacy day after rollback.

Writes work/cutover/rollback/{rollback.log,rollback.json}. Exit 0 only if every check
passed. Truncates XFER_FEE_LEDGER, so it refuses to run unless PGHOST is the compose
`db` service (as runjcl --db-reset does); never point it at a shared database.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "parity"))
sys.path.insert(0, str(ROOT / "tools" / "fixtures"))

from copybook import decode_record, parse_copybook, record_length  # noqa: E402
from gen_fixtures import transaction, transfer  # noqa: E402

CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
HLQ = "AWS.M2.CARDDEMO"
ACCT_GDG = f"{HLQ}.ACCTDATA.XFER"
FEES_GDG = f"{HLQ}.XFER.FEES"
RECON_GDG = f"{HLQ}.XFER.RECON.RPT"
MASTER = f"{HLQ}.ACCTDATA.PS"
RECON_SQL = ROOT / "ops" / "cutover" / "ledger_recon.sql"
RESTORE_JCL = ROOT / "ops" / "cutover" / "XFRRBACK.jcl"
SETUP_JCL = ROOT / "jcl" / "DEFGDGX.jcl"
DAILY_JCL = ROOT / "jcl" / "XFRDAILY.jcl"
LEDGER_HEADER = ["tran_id", "tran_dt", "src_acct_id", "tgt_acct_id", "book_id",
                 "tran_amt", "fee_amt", "cap_applied"]


class DryRun:
    def __init__(self, out: Path) -> None:
        self.out = out
        self.datasets = out / "datasets"
        self.staging = out / "staging"
        self.joblogs = out / "joblog"
        self.lines: list[str] = []
        self.checks: list[dict[str, Any]] = []

    def log(self, message: str = "") -> None:
        stamp = dt.datetime.now(dt.timezone.utc).strftime("%H:%M:%SZ")
        for line in (message.splitlines() or [""]):
            entry = f"{stamp} {line}"
            print(entry, flush=True)
            self.lines.append(entry)

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        self.checks.append({"name": name, "ok": bool(ok), "detail": detail})
        self.log(f"  [{'OK  ' if ok else 'FAIL'}] {name}" + (f" - {detail}" if detail else ""))
        return ok

    def gdg_path(self, base: str, generation: int) -> Path:
        return self.datasets / f"{base}.G{generation:04d}V00"

    def catalog(self, base: str) -> int:
        return json.loads((self.datasets / f"{base}.gdg").read_text())["current"]

    def set_catalog(self, base: str, generation: int) -> None:
        (self.datasets / f"{base}.gdg").write_text(json.dumps({"current": generation}) + "\n")

    def run_job(self, jcl: Path) -> int:
        self.log(f"$ runjcl --job {jcl.relative_to(ROOT)}")
        proc = subprocess.run(
            [sys.executable, str(ROOT / "tools" / "runjcl" / "runjcl.py"), "--job", str(jcl),
             "--datasets", str(self.datasets), "--joblog-dir", str(self.joblogs)],
            cwd=ROOT, capture_output=True, text=True,
        )
        job = jcl.stem
        joblog = self.joblogs / f"{job}.log"
        if joblog.exists():
            for line in joblog.read_text().splitlines():
                self.log(f"    | {line}")
        if proc.stderr.strip():
            self.log("    stderr: " + proc.stderr.strip())
        return proc.returncode

    def recon(self, label: str, acct_before: Path, acct_after: Path, fees: Path,
              incoming: Path, ledger_before: Path) -> dict[str, tuple[str, str, bool]]:
        self.log(f"$ psql -f ops/cutover/ledger_recon.sql   ({label})")
        load = self.staging / "load.sql"
        load.write_text("".join(
            f"\\copy {table} FROM '{path}' CSV HEADER\n"
            for table, path in (("rb_acct_before", acct_before), ("rb_acct_after", acct_after),
                                ("rb_fees", fees), ("rb_incoming", incoming),
                                ("rb_ledger_before", ledger_before))
        ))
        proc = subprocess.run(
            ["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At", "-F", "|",
             "-v", f"load={load}", "-f", str(RECON_SQL)],
            cwd=ROOT, capture_output=True, text=True, check=False,
        )
        if proc.returncode:
            raise RuntimeError(f"ledger_recon.sql failed: {proc.stderr.strip()}")
        rows: dict[str, tuple[str, str, bool]] = {}
        self.log("    check                                   expected     actual       ok")
        for line in proc.stdout.strip().splitlines():
            name, expected, actual, ok = line.split("|")
            rows[name] = (expected, actual, ok == "t")
            self.log(f"    {name:<40}{expected:<13}{actual:<13}{ok}")
        return rows


def records(path: Path, copybook: str) -> list[dict[str, Any]]:
    length = record_length(parse_copybook(copybook))
    data = path.read_bytes()
    if len(data) % length:
        raise ValueError(f"{path.name}: {len(data)} bytes is not a multiple of LRECL {length}")
    return [decode_record(copybook, data[i:i + length]) for i in range(0, len(data), length)]


def write_csv(path: Path, header: list[str], rows: list[list[Any]]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows(rows)
    return path


def accounts_csv(dataset: Path, destination: Path,
                 tamper: Callable[[list[list[Any]]], None] | None = None) -> Path:
    rows = [[int(r["ACCT-ID"]), r["ACCT-CURR-BAL"], r["ACCT-CURR-CYC-CREDIT"],
             r["ACCT-CURR-CYC-DEBIT"]] for r in records(dataset, "CVACT01Y")]
    if tamper:
        tamper(rows)
    return write_csv(destination, ["acct_id", "curr_bal", "cyc_credit", "cyc_debit"], rows)


def fees_csv(dataset: Path, destination: Path) -> Path:
    rows = [[r["XFE-TRAN-ID"], r["XFE-TRAN-DT"], int(r["XFE-SRC-ACCT-ID"]),
             int(r["XFE-TGT-ACCT-ID"]), r["XFE-BOOK-ID"], r["XFE-TRAN-AMT"],
             r["XFE-FEE-AMT"], r["XFE-CAP-APPLIED"]]
            for r in (records(dataset, "CVXFR02Y") if dataset.stat().st_size else [])]
    return write_csv(destination, LEDGER_HEADER, rows)


def transfer_ids(dalytran: Path) -> list[str]:
    return [r["TRAN-ID"] for r in records(dalytran, "CVTRA05Y") if r["TRAN-TYPE-CD"] == "08"]


def ledger_keys(destination: Path) -> Path:
    """Snapshot of XFER_FEE_LEDGER keys, taken before a run posts."""
    keys = [k for k in psql("SELECT TRAN_ID FROM XFER_FEE_LEDGER ORDER BY 1").splitlines() if k]
    return write_csv(destination, ["tran_id"], [[k] for k in keys])


def release_commit() -> str | None:
    proc = subprocess.run(["git", "-c", f"safe.directory={ROOT}", "rev-parse", "HEAD"],
                          cwd=ROOT, capture_output=True, text=True, check=False)
    return proc.stdout.strip() or None if proc.returncode == 0 else None


def psql(sql: str) -> str:
    return subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At", "-c", sql],
                          cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()


def choose_source(case: str, explicit: Path | None) -> tuple[Path, str, str]:
    """Generation to roll back from, its label, and its kind (java / explicit / fixture).

    By default only java rehearsals satisfy cut-over gate G3. explicit (an operator-supplied
    directory outside fixtures/) needs --accept-explicit-rehearsal at the gate, because the
    path itself proves nothing about who produced it. fixture never counts for production.
    """
    if explicit:
        kind = "fixture" if explicit.is_relative_to(ROOT / "fixtures") else "explicit"
        return explicit, f"--source {explicit}", kind
    for java, report in (
        (ROOT / "work" / "parity-java" / case / "candidate",
         ROOT / "work" / "parity-java" / case / "report.md"),
        (ROOT / "work" / "parity" / case / "java" / "candidate",
         ROOT / "work" / "parity" / case / "java-report.md"),
    ):
        if (java / "datasets" / f"{ACCT_GDG}.G0001V00").exists() and report.exists() \
                and f"/ {case} \u2014 PASS" in report.read_text():
            return java, "Java parity-replay candidate (legacy-adapter egress, parity PASS)", "java"
    return (CHAIN_ROOT / case / "expected",
            "COBOL-recorded fixture outputs as stand-in for legacy-adapter egress "
            "(no Java candidate with parity PASS for this case yet; COG-1240/P1-P6 not merged)",
            "fixture")


def next_day_dalytran(run_date: dt.date) -> bytes:
    day = (run_date + dt.timedelta(days=1)).isoformat()
    cards = [f"{n:016d}" for n in range(1000000000000001, 1000000000000009)]
    return b"".join([
        transfer("TRN0000000000101", 50.00, 3, 4, cards[2], day),
        transaction("TRN0000000000102", "01", 19.99, cards[4], day, "POS purchase"),
        transfer("TRN0000000000103", 2500.00, 7, 8, cards[6], day),
    ])


def dry_run(args: argparse.Namespace) -> int:
    run = DryRun(args.out)
    if args.out.exists():
        shutil.rmtree(args.out)
    for path in (run.datasets, run.staging, run.joblogs):
        path.mkdir(parents=True)
    started = dt.datetime.now(dt.timezone.utc)
    case_dir = CHAIN_ROOT / args.case
    metadata = json.loads((case_dir / "case.json").read_text())
    run_date = dt.date.fromisoformat(metadata["run_date"])
    source, source_label, source_kind = choose_source(args.case, args.source)
    commit = release_commit()
    status = "FAIL"
    try:
        run.log("=" * 78)
        run.log("COG-1252 xferfee ROLLBACK DRY RUN (compose stack)")
        run.log(f"started      {started.isoformat(timespec='seconds')}")
        run.log(f"case         {args.case}  (last Java-posted business day {run_date})")
        run.log(f"commit       {commit or 'unknown'}")
        run.log(f"generation   {source_label}  [{source_kind}]")
        run.log(f"             {source.relative_to(ROOT) if source.is_relative_to(ROOT) else source}")
        run.log("=" * 78)

        run.log("")
        run.log("STEP 1  stage state after the last Java-posted day")
        if run.run_job(SETUP_JCL):
            raise RuntimeError("DEFGDGX failed")
        for name in ("CARDXREF.PS", "ACCTDATA.PS"):
            shutil.copyfile(case_dir / "input" / name, run.datasets / f"{HLQ}.{name}")
        frozen_master = run.staging / "ACCTDATA.PS.frozen"
        shutil.copyfile(case_dir / "input" / "ACCTDATA.PS", frozen_master)
        for base in (ACCT_GDG, FEES_GDG):
            shutil.copyfile(source / "datasets" / f"{base}.G0001V00", run.gdg_path(base, 1))
            run.set_catalog(base, 1)
            run.log(f"  legacy-adapter catalogued {base}(+1) -> G0001V00")
        psql("TRUNCATE TABLE XFER_FEE_LEDGER")
        java_before = ledger_keys(run.staging / "ledger_before_java_day.csv")
        ledger_src = source / "db2_after" / "XFER_FEE_LEDGER.csv"
        psql(f"\\copy XFER_FEE_LEDGER (TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, BOOK_ID, "
             f"TRAN_AMT, FEE_AMT, CAP_APPLIED) FROM '{ledger_src}' CSV HEADER")
        java_rows = int(psql("SELECT COUNT(*) FROM XFER_FEE_LEDGER"))
        run.log(f"  mirrored {java_rows} Java ledger rows into XFER_FEE_LEDGER")

        run.log("")
        run.log("STEP 2  pre-flight on ACCTDATA.XFER(0)")
        generation = run.gdg_path(ACCT_GDG, run.catalog(ACCT_GDG))
        run.check("catalog_points_at_generation", generation.exists(),
                  f"{ACCT_GDG}(0) = {generation.name}")
        gen_rows = records(generation, "CVACT01Y")
        master_rows = records(frozen_master, "CVACT01Y")
        gen_ids = [int(r["ACCT-ID"]) for r in gen_rows]
        run.check("generation_decodes", True, f"{len(gen_rows)} x 300-byte CVACT01Y records")
        run.check("generation_unique_acct_ids", len(gen_ids) == len(set(gen_ids)))
        run.check("generation_is_full_master",
                  set(gen_ids) == {int(r["ACCT-ID"]) for r in master_rows},
                  "same ACCT-ID set as the master frozen at cut-over (BR-16)")
        tomorrow = run.staging / "DALYTRAN.next"
        tomorrow.write_bytes(next_day_dalytran(run_date))
        incoming = write_csv(run.staging / "incoming.csv", ["tran_id"],
                             [[t] for t in transfer_ids(tomorrow)])
        before_csv = accounts_csv(frozen_master, run.staging / "acct_frozen.csv")
        gen_csv = accounts_csv(generation, run.staging / "acct_gen.csv")
        gen_fees = fees_csv(run.gdg_path(FEES_GDG, run.catalog(FEES_GDG)), run.staging / "fees_gen.csv")
        rows = run.recon("pre-flight", before_csv, gen_csv, gen_fees, incoming, java_before)
        for name, (expected, actual, ok) in rows.items():
            run.check(f"preflight.{name}", ok, f"expected {expected} actual {actual}")

        run.log("")
        run.log("STEP 2b negative drills (query must refuse)")
        replay = write_csv(run.staging / "incoming_replay.csv", ["tran_id"],
                           [[t] for t in transfer_ids(case_dir / "input" / "DALYTRAN.PS")])
        rows = run.recon("drill: re-run of the Java day's DALYTRAN", before_csv, gen_csv,
                         gen_fees, replay, java_before)
        _, actual, ok = rows["incoming_already_in_ledger"]
        run.check("drill.duplicate_post_detected", not ok and int(actual) == java_rows,
                  f"{actual} TRAN_IDs already posted would be rejected")

        def nudge(table: list[list[Any]]) -> None:
            table[0][1] = Decimal(table[0][1]) + Decimal("0.01")

        tampered = accounts_csv(generation, run.staging / "acct_gen_tampered.csv", nudge)
        rows = run.recon("drill: generation off by 0.01 on one account", before_csv, tampered,
                         gen_fees, incoming, java_before)
        run.check("drill.balance_drift_detected", not rows["accounts_not_explained_by_ledger"][2],
                  f"{rows['accounts_not_explained_by_ledger'][1]} account(s) not explained")

        no_fees = write_csv(run.staging / "fees_empty.csv", LEDGER_HEADER, [])
        rows = run.recon("drill: partial day (ledger rows, empty XFER.FEES)", before_csv, before_csv,
                         no_fees, incoming, java_before)
        _, actual, ok = rows["ledger_rows_not_in_fees_file"]
        run.check("drill.partial_day_detected", not ok and int(actual) == java_rows,
                  f"{actual} ledger rows posted by the run not explained by an empty fees file")

        preflight_ok = all(c["ok"] for c in run.checks)
        if not preflight_ok:
            raise RuntimeError("pre-flight failed; rollback must not proceed")

        run.log("")
        run.log("STEP 3  restore ACCTDATA.PS from ACCTDATA.XFER(0)")
        rc = run.run_job(RESTORE_JCL)
        run.check("XFRRBACK_maxcc_0", rc == 0, f"MAXCC={rc:04d}")
        run.check("master_equals_generation",
                  (run.datasets / MASTER).read_bytes() == generation.read_bytes(),
                  f"{MASTER} == {generation.name}")

        run.log("")
        run.log(f"STEP 4  re-enable XFRDAILY for {run_date + dt.timedelta(days=1)}")
        shutil.copyfile(tomorrow, run.datasets / f"{HLQ}.DALYTRAN.PS")
        legacy_before = ledger_keys(run.staging / "ledger_before_legacy_day.csv")
        rc = run.run_job(DAILY_JCL)
        run.check("XFRDAILY_maxcc_0", rc == 0, f"MAXCC={rc:04d}")

        run.log("")
        run.log("STEP 5  post-run reconciliation (first legacy day after rollback)")
        new_gen = run.gdg_path(ACCT_GDG, run.catalog(ACCT_GDG))
        new_fees = run.gdg_path(FEES_GDG, run.catalog(FEES_GDG))
        run.check("new_generation_catalogued", run.catalog(ACCT_GDG) == 2, new_gen.name)
        restored_csv = accounts_csv(run.datasets / MASTER, run.staging / "acct_restored.csv")
        after_csv = accounts_csv(new_gen, run.staging / "acct_after.csv")
        day_fees = fees_csv(new_fees, run.staging / "fees_day1.csv")
        empty = write_csv(run.staging / "incoming_none.csv", ["tran_id"], [])
        rows = run.recon("post-run", restored_csv, after_csv, day_fees, empty, legacy_before)
        for name, (expected, actual, ok) in rows.items():
            run.check(f"postrun.{name}", ok, f"expected {expected} actual {actual}")
        fee_total = sum((Decimal(r[6]) for r in csv.reader(day_fees.open()) if r[0] != "tran_id"),
                        Decimal("0"))
        report = run.gdg_path(RECON_GDG, run.catalog(RECON_GDG)).read_text()
        match = re.search(r"GRAND TOTAL COUNT\s+(\d+)\s+AMOUNT\s+([\d.]+)\s+FEE\s+([\d.]+)", report)
        run.check("recon_report_total_equals_fees",
                  bool(match) and Decimal(match.group(3)) == fee_total,
                  f"report FEE {match.group(3) if match else '?'} vs fees file {fee_total}")
        new_rows = len(transfer_ids(tomorrow))
        total = int(psql("SELECT COUNT(*) FROM XFER_FEE_LEDGER"))
        run.check("ledger_continuity", total == java_rows + new_rows,
                  f"{java_rows} Java-day rows + {new_rows} legacy rows = {total}")
        status = "PASS" if all(c["ok"] for c in run.checks) else "FAIL"
    except Exception as exc:  # the log must record why the rehearsal stopped
        run.log(f"ABORT: {exc}")
        run.check("rehearsal_completed", False, str(exc))
    finished = dt.datetime.now(dt.timezone.utc)
    passed = sum(c["ok"] for c in run.checks)
    run.log("")
    run.log("=" * 78)
    run.log(f"ROLLBACK DRY RUN: {status}  ({passed}/{len(run.checks)} checks)  "
            f"finished {finished.isoformat(timespec='seconds')}")
    run.log("=" * 78)
    (args.out / "rollback.log").write_text("\n".join(run.lines) + "\n")
    (args.out / "rollback.json").write_text(json.dumps({
        "status": status,
        "case": args.case,
        "generation_source": source_label,
        "source_kind": source_kind,
        "commit": commit,
        "started_at": started.isoformat(timespec="seconds"),
        "finished_at": finished.isoformat(timespec="seconds"),
        "checks": run.checks,
    }, indent=2, default=str) + "\n")
    return 0 if status == "PASS" else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--case", default="default",
                        help="fixture case whose run is treated as the last Java-posted day")
    parser.add_argument("--source", type=Path,
                        help="directory with datasets/ and db2_after/ to use as the "
                             "legacy-adapter output (default: Java candidate if it passed "
                             "parity, else the COBOL-recorded expected outputs)")
    parser.add_argument("--out", type=Path, default=ROOT / "work" / "cutover" / "rollback")
    parser.add_argument("--allow-non-compose-db", action="store_true",
                        help="run even if PGHOST is not the compose 'db' service (truncates XFER_FEE_LEDGER)")
    args = parser.parse_args()
    if os.environ.get("PGHOST") != "db" and not args.allow_non_compose_db:
        parser.error(f"PGHOST={os.environ.get('PGHOST')!r} is not the compose 'db' service; this "
                     "rehearsal truncates XFER_FEE_LEDGER (use --allow-non-compose-db for a disposable DB)")
    args.out = args.out.resolve()
    if args.source:
        args.source = args.source.resolve()
    return dry_run(args)


if __name__ == "__main__":
    raise SystemExit(main())
