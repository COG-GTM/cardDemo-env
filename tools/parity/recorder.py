#!/usr/bin/env python3
"""Record deterministic COBOL chain outputs for parity comparisons."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from tools.runjcl.runjcl import reset_db as runjcl_reset_db


CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
CASES = (
    "default",
    "under_cap",
    "at_cap",
    "rate_change",
    "zero_amount",
    "non_transfer",
    "half_cent",
)
INPUT_DSNS = {
    "ACCTDATA.PS": "AWS.M2.CARDDEMO.ACCTDATA.PS",
    "CARDXREF.PS": "AWS.M2.CARDDEMO.CARDXREF.PS",
    "DALYTRAN.PS": "AWS.M2.CARDDEMO.DALYTRAN.PS",
}
TABLES = {
    "CTL_XFER_PARM": (
        "BOOK_ID, FEE_PCT, FEE_CAP, EFF_DT, EXP_DT",
        "BOOK_ID, EFF_DT",
    ),
    "XFER_FEE_LEDGER": (
        "TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, BOOK_ID, "
        "TRAN_AMT, FEE_AMT, CAP_APPLIED",
        "TRAN_ID",
    ),
}


def run(command: list[str]) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def dump_table(table: str, destination: Path) -> None:
    columns, order = TABLES[table]
    destination.parent.mkdir(parents=True, exist_ok=True)
    escaped = str(destination).replace("'", "''")
    query = (
        f"\\copy (SELECT {columns} FROM {table} ORDER BY {order}) "
        f"TO '{escaped}' CSV HEADER"
    )
    run(["psql", "-v", "ON_ERROR_STOP=1", "-c", query])


def load_inputs(case: str, datasets: Path) -> None:
    run([
        sys.executable,
        str(ROOT / "tools" / "fixtures" / "gen_fixtures.py"),
        "--case",
        case,
    ])
    source = CHAIN_ROOT / case / "input"
    datasets.mkdir(parents=True, exist_ok=True)
    for filename, dsn in INPUT_DSNS.items():
        shutil.copyfile(source / filename, datasets / dsn)


def parse_rc(joblog: Path) -> dict[str, object]:
    steps: dict[str, int] = {}
    maxcc = 0
    for line in joblog.read_text().splitlines():
        match = re.search(
            r"IEF142I\s+\S+\s+(\S+)\s+-\s+STEP WAS EXECUTED"
            r"\s+-\s+COND CODE\s+(\d+)",
            line,
        )
        if match:
            steps[match.group(1)] = int(match.group(2))
        match = re.search(r"\$HASP395\s+\S+\s+ENDED\s+-\s+MAXCC=(\d+)", line)
        if match:
            maxcc = int(match.group(1))
    return {"steps": steps, "maxcc": maxcc}


def record_case(case: str, output: Path | None = None) -> Path:
    if case not in CASES:
        raise ValueError(f"unsupported case: {case}")
    expected = output or CHAIN_ROOT / case / "expected"
    if expected.exists():
        shutil.rmtree(expected)
    expected.mkdir(parents=True)
    if output is None:
        run_root = ROOT / "work" / "record" / case
    else:
        run_root = output.parent
    datasets = run_root / "datasets"
    joblog = run_root / "joblog"
    manifest = run_root / "manifest.json"
    if datasets.exists():
        shutil.rmtree(datasets)
    if joblog.exists():
        shutil.rmtree(joblog)
    manifest.unlink(missing_ok=True)

    load_inputs(case, datasets)
    before = expected.parent / "db2_before"
    if before.exists():
        shutil.rmtree(before)
    runjcl_reset_db()
    for table in TABLES:
        dump_table(table, before / f"{table}.csv")

    run([
        sys.executable,
        str(ROOT / "tools" / "runjcl" / "runjcl.py"),
        "--chain",
        "xferfee",
        "--datasets",
        str(datasets),
        "--joblog-dir",
        str(joblog),
        "--manifest",
        str(manifest),
    ])

    entries = json.loads(manifest.read_text()).get("outputs", [])
    dataset_dir = expected / "datasets"
    dataset_dir.mkdir(parents=True)
    for entry in entries:
        shutil.copyfile(entry["path"], dataset_dir / entry["dsn"])

    sysout_dir = expected / "sysout"
    sysout_dir.mkdir(parents=True)
    for step in ("STEP010", "STEP020", "STEP030"):
        source = joblog / "XFRDAILY" / f"{step}.SYSOUT"
        if source.exists():
            shutil.copyfile(source, sysout_dir / f"{step}.txt")
    rc = parse_rc(joblog / "XFRDAILY.log")
    (expected / "rc.json").write_text(json.dumps(rc, indent=2) + "\n")
    after = expected / "db2_after"
    for table in TABLES:
        dump_table(table, after / f"{table}.csv")
    return expected


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chain", default="xferfee")
    parser.add_argument("--case", default=None)
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    if args.chain != "xferfee":
        parser.error("only the xferfee chain is supported")
    cases = CASES if args.all else (args.case,)
    if cases[0] is None:
        parser.error("--case is required unless --all is used")
    for case in cases:
        output = args.out if len(cases) == 1 else None
        record_case(case, output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
