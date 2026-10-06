#!/usr/bin/env python3
"""Run the Java parity-replay for one xferfee case and lay out a compare.py candidate.

    python3 tools/parity/java_candidate.py --case rate_change --out work/parity-java/rate_change

writes ``<out>/out/`` (raw parity-replay output) and ``<out>/candidate/`` (the layout
``compare.py --candidate`` reads: datasets/, db2_after/, sysout/, rc.json). Stages the
Java build does not implement yet produce nothing, so compare.py reports their outputs as
missing records instead of crashing.
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import subprocess
import sys
from pathlib import Path

from compare import dataset_file
from copybook import decode_record, parse_copybook, record_length


ROOT = Path(__file__).resolve().parents[2]
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
JAR = ROOT / "java" / "parity-replay" / "target" / "parity-replay.jar"
EXTRACT_DSN = "AWS.M2.CARDDEMO.XFER.EXTRACT"


def write_fee_lookups(case: str, destination: Path) -> int:
    """One (book, transaction date) lookup per transfer XFERFEE reads.

    Until transfer-intake-service lands, the recorded XFER.EXTRACT stands in for STEP010
    (an input stub only; its own output stays missing from the candidate).
    """
    extract = dataset_file(CHAIN_ROOT / case / "expected" / "datasets", EXTRACT_DSN)
    rows = []
    if extract is not None:
        length = record_length(parse_copybook("CVXFR01Y"))
        data = extract.read_bytes()
        for offset in range(0, len(data), length):
            values = decode_record("CVXFR01Y", data[offset:offset + length])
            rows.append((
                str(values["XFR-TRAN-ID"]).strip(),
                str(values["XFR-BOOK-ID"]).strip(),
                str(values["XFR-TRAN-DT"]).strip(),
            ))
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("w", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(["tran_id", "book_id", "tran_dt"])
        writer.writerows(rows)
    return len(rows)


def build_candidate(case: str, raw: Path, candidate: Path) -> None:
    if candidate.exists():
        shutil.rmtree(candidate)
    for name in ("datasets", "db2_after", "sysout"):
        source = raw / name
        if source.is_dir():
            shutil.copytree(source, candidate / name)
        else:
            (candidate / name).mkdir(parents=True)
    metadata = json.loads((CHAIN_ROOT / case / "case.json").read_text())
    for table in metadata["db2"]:
        target = candidate / "db2_after" / f"{table['table']}.csv"
        if not target.exists():
            header = (CHAIN_ROOT / case / "db2_before" / f"{table['table']}.csv")
            target.write_text(header.read_text().splitlines()[0] + "\n")
    rc = raw / "rc.json"
    shutil.copyfile(rc, candidate / "rc.json")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", required=True)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    if not args.jar.exists():
        parser.error(f"{args.jar} not found; run `make java-build`")
    out = args.out.resolve()
    raw = out / "out"
    lookups = out / "input" / "fee_lookups.csv"
    write_fee_lookups(args.case, lookups)
    result = subprocess.run(
        [
            args.java, "-jar", str(args.jar),
            "--case", args.case,
            "--out", str(raw),
            "--fixtures", str(CHAIN_ROOT),
            "--lookups", str(lookups),
        ],
        cwd=ROOT,
    )
    if result.returncode != 0:
        return result.returncode
    build_candidate(args.case, raw, out / "candidate")
    return 0


if __name__ == "__main__":
    sys.exit(main())
