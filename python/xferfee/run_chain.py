#!/usr/bin/env python3
"""Job ``XFRDAILY`` -> PROC ``XFERFEEP``: run the three steps in JCL order (BR-18).

Reproduces what ``tools/runjcl/runjcl.py`` does for this chain:

* ``DEFGDGX``: define the four GDG bases under the datasets directory;
* ``STEP010``/``STEP020``/``STEP030`` with the DD -> DSN mapping of the PROC,
  ``(+1)`` allocating and cataloguing a new ``.Gnnnn V00`` generation and
  ``(0)`` reading the current one;
* ``COND=(4,LT,STEP020)`` on STEP030, and the estate runner's behaviour of
  ending the job after the first non-zero step (spec Q9);
* per-step SYSOUT capture and a ``rc.json`` with ``steps``/``maxcc``.

With ``--candidate DIR`` the outputs are also laid out in the shape
``tools/parity/compare.py`` expects (``datasets/``, ``db2_after/``,
``sysout/``, ``rc.json``).
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import subprocess
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from xferfee import extract, post_fees, reconcile  # noqa: E402
from xferfee.extract import StepResult  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
HLQ = "AWS.M2.CARDDEMO"

GDG_BASES = (  # jcl/DEFGDGX.jcl
    f"{HLQ}.XFER.EXTRACT",
    f"{HLQ}.ACCTDATA.XFER",
    f"{HLQ}.XFER.FEES",
    f"{HLQ}.XFER.RECON.RPT",
)
INPUT_DSNS = {  # runjcl.load_fixtures
    "ACCTDATA.PS": f"{HLQ}.ACCTDATA.PS",
    "CARDXREF.PS": f"{HLQ}.CARDXREF.PS",
    "DALYTRAN.PS": f"{HLQ}.DALYTRAN.PS",
}
TABLES = {  # tools/parity/recorder.py dump_table
    "CTL_XFER_PARM": ("BOOK_ID, FEE_PCT, FEE_CAP, EFF_DT, EXP_DT", "BOOK_ID, EFF_DT"),
    "XFER_FEE_LEDGER": (
        "TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, BOOK_ID, TRAN_AMT, FEE_AMT, CAP_APPLIED",
        "TRAN_ID",
    ),
}


class Catalog:
    """GDG bookkeeping compatible with runjcl (``<base>.gdg`` -> ``{"current": n}``)."""

    def __init__(self, datasets: Path):
        self.datasets = datasets
        datasets.mkdir(parents=True, exist_ok=True)

    def _meta(self, base: str) -> Path:
        return self.datasets / f"{base}.gdg"

    def define(self, base: str) -> None:
        if not self._meta(base).exists():
            self._meta(base).write_text('{"current": 0}\n')

    def current(self, base: str) -> int:
        meta = self._meta(base)
        return int(json.loads(meta.read_text()).get("current", 0)) if meta.exists() else 0

    def generation_path(self, base: str, generation: int) -> Path:
        return self.datasets / f"{base}.G{generation:04d}V00"

    def new(self, base: str) -> tuple[Path, int]:
        """``DSN=base(+1),DISP=(NEW,CATLG,DELETE)``."""
        generation = self.current(base) + 1
        path = self.generation_path(base, generation)
        path.write_bytes(b"")
        return path, generation

    def catalog(self, base: str, generation: int) -> None:
        self._meta(base).write_text(json.dumps({"current": generation}) + "\n")

    def current_path(self, base: str) -> Path:
        """``DSN=base(0),DISP=SHR``."""
        return self.generation_path(base, self.current(base))

    def flat(self, dsn: str) -> Path:
        return self.datasets / dsn


def load_fixtures(case: str, datasets: Path) -> None:
    subprocess.run(
        [sys.executable, str(ROOT / "tools" / "fixtures" / "gen_fixtures.py"), "--case", case],
        cwd=ROOT, check=True,
    )
    source = ROOT / "fixtures" / "xferfee" / case / "input"
    datasets.mkdir(parents=True, exist_ok=True)
    for filename, dsn in INPUT_DSNS.items():
        shutil.copyfile(source / filename, datasets / dsn)


def reset_db() -> None:
    with post_fees.connect() as conn:
        conn.cursor().execute("TRUNCATE TABLE XFER_FEE_LEDGER")
        conn.commit()


def dump_tables(destination: Path) -> None:
    """Same shape as ``psql \\copy ... CSV HEADER`` used by the recorder."""
    destination.mkdir(parents=True, exist_ok=True)
    with post_fees.connect() as conn:
        for table, (columns, order) in TABLES.items():
            cursor = conn.cursor()
            cursor.execute(f"SELECT {columns} FROM {table} ORDER BY {order}")
            with (destination / f"{table}.csv").open("w", newline="") as handle:
                writer = csv.writer(handle, lineterminator="\n")
                writer.writerow(d.name for d in cursor.description)
                for row in cursor.fetchall():
                    writer.writerow("" if v is None else str(v) for v in row)


def cond_skips(threshold: int, operator: str, prior_rc: int) -> bool:
    """JCL ``COND=(threshold,operator,step)``: step is bypassed when the test is true."""
    return {
        "EQ": prior_rc == threshold, "NE": prior_rc != threshold,
        "GT": threshold > prior_rc, "GE": threshold >= prior_rc,
        "LT": threshold < prior_rc, "LE": threshold <= prior_rc,
    }[operator]


def run_job(datasets: Path, joblog: Path, log) -> tuple[dict[str, int], int, list[dict[str, str]]]:
    catalog = Catalog(datasets)
    for base in GDG_BASES:  # DEFGDGX
        catalog.define(base)
    joblog.mkdir(parents=True, exist_ok=True)

    results: dict[str, int] = {}
    outputs: list[dict[str, str]] = []
    maxcc = 0

    def finish(step: str, result: StepResult, new_generations: list[tuple[str, int]]) -> bool:
        nonlocal maxcc
        (joblog / f"{step}.SYSOUT").write_text("".join(f"{line}\n" for line in result.sysout))
        for line in result.sysout:
            log(line)
        log(f"IEF142I XFRDAILY {step} - STEP WAS EXECUTED - COND CODE {result.rc:04d}")
        results[step] = result.rc
        maxcc = max(maxcc, result.rc)
        if result.rc != 0:
            return False
        for base, generation in new_generations:
            catalog.catalog(base, generation)
            outputs.append({"dsn": f"{base}.G{generation:04d}V00",
                            "path": str(catalog.generation_path(base, generation))})
            log(f"IEF285I   {base}.G{generation:04d}V00   CATALOGED")
        return True

    # STEP010 EXEC PGM=CBXFR01C
    log("IEF236I ALLOC. FOR XFRDAILY STEP010")
    extract_out, gen = catalog.new(f"{HLQ}.XFER.EXTRACT")
    result = extract.run(
        dalytran=catalog.flat(INPUT_DSNS["DALYTRAN.PS"]),
        xreffile=catalog.flat(INPUT_DSNS["CARDXREF.PS"]),
        acctfile=catalog.flat(INPUT_DSNS["ACCTDATA.PS"]),
        xferextr=extract_out,
    )
    if not finish("STEP010", result, [(f"{HLQ}.XFER.EXTRACT", gen)]):
        return results, maxcc, outputs

    # STEP020 EXEC PGM=XFERFEE
    log("IEF236I ALLOC. FOR XFRDAILY STEP020")
    acct_out, acct_gen = catalog.new(f"{HLQ}.ACCTDATA.XFER")
    fees_out, fees_gen = catalog.new(f"{HLQ}.XFER.FEES")
    result = post_fees.run(
        xferextr=catalog.current_path(f"{HLQ}.XFER.EXTRACT"),
        acctfile=catalog.flat(INPUT_DSNS["ACCTDATA.PS"]),
        acctout=acct_out,
        xferfee=fees_out,
    )
    if not finish("STEP020", result,
                  [(f"{HLQ}.ACCTDATA.XFER", acct_gen), (f"{HLQ}.XFER.FEES", fees_gen)]):
        return results, maxcc, outputs

    # STEP030 EXEC PGM=CBXFR03C,COND=(4,LT,STEP020)
    log("IEF236I ALLOC. FOR XFRDAILY STEP030")
    if cond_skips(4, "LT", results["STEP020"]):
        log("IEF202I XFRDAILY STEP030 - STEP WAS NOT RUN BECAUSE OF CONDITION CODES")
        results["STEP030"] = 0
        return results, maxcc, outputs
    rpt_out, rpt_gen = catalog.new(f"{HLQ}.XFER.RECON.RPT")
    result = reconcile.run(xferfee=catalog.current_path(f"{HLQ}.XFER.FEES"), xferrpt=rpt_out)
    finish("STEP030", result, [(f"{HLQ}.XFER.RECON.RPT", rpt_gen)])
    return results, maxcc, outputs


def write_candidate(candidate: Path, joblog: Path, outputs: list[dict[str, str]],
                    results: dict[str, int], maxcc: int) -> None:
    if candidate.exists():
        shutil.rmtree(candidate)
    (candidate / "datasets").mkdir(parents=True)
    for entry in outputs:
        shutil.copyfile(entry["path"], candidate / "datasets" / entry["dsn"])
    (candidate / "sysout").mkdir()
    for step in results:
        source = joblog / f"{step}.SYSOUT"
        if source.exists():
            shutil.copyfile(source, candidate / "sysout" / f"{step}.txt")
    (candidate / "rc.json").write_text(json.dumps({"steps": results, "maxcc": maxcc}, indent=2) + "\n")
    dump_tables(candidate / "db2_after")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--case", help="fixture case to load into the datasets directory")
    parser.add_argument("--datasets", type=Path, default=ROOT / "datasets")
    parser.add_argument("--joblog-dir", type=Path, default=ROOT / "work" / "python" / "joblog")
    parser.add_argument("--candidate", type=Path,
                        help="write comparator candidate tree (datasets/, db2_after/, sysout/, rc.json)")
    parser.add_argument("--db-reset", action="store_true", help="TRUNCATE XFER_FEE_LEDGER first")
    parser.add_argument("--fresh", action="store_true", help="delete the datasets directory first")
    args = parser.parse_args()

    datasets = args.datasets if args.datasets.is_absolute() else ROOT / args.datasets
    joblog = args.joblog_dir if args.joblog_dir.is_absolute() else ROOT / args.joblog_dir
    if args.fresh and datasets.exists():
        shutil.rmtree(datasets)
    if args.case:
        load_fixtures(args.case, datasets)
    if args.db_reset:
        reset_db()

    log_lines: list[str] = []

    def log(message: str) -> None:
        print(message)
        log_lines.append(message)

    results, maxcc, outputs = run_job(datasets, joblog, log)
    log(f"$HASP395 XFRDAILY ENDED - MAXCC={maxcc:04d}")
    (joblog / "XFRDAILY.log").write_text("\n".join(log_lines) + "\n")
    if args.candidate:
        candidate = args.candidate if args.candidate.is_absolute() else ROOT / args.candidate
        write_candidate(candidate, joblog, outputs, results, maxcc)
    return maxcc


if __name__ == "__main__":
    raise SystemExit(main())
