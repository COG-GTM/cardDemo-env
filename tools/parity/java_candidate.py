#!/usr/bin/env python3
"""Build a parity candidate for one xferfee case from the Java parity-replay module.

Runs ``org.carddemo.xferfee.replay.ParityReplay`` (whose XFE-FEE-AMT / XFE-CAP-APPLIED come from the
``fee-policy`` library), then encodes its JSON-lines output into the same fixed-width datasets the COBOL
chain writes so ``compare.py --candidate ... --only ...`` can diff them field by field.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

from copybook import encode_record


ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOT = ROOT / "java"
MODULES = ("contracts", "fee-policy", "parity-replay")
MAIN_CLASS = "org.carddemo.xferfee.replay.ParityReplay"
GENERATION = ".G0001V00"
COPYBOOKS = {"AWS.M2.CARDDEMO.XFER.FEES": "CVXFR02Y"}


def classpath() -> str:
    configured = os.environ.get("XFERFEE_JAVA_CLASSPATH")
    if configured:
        return configured
    entries = [JAVA_ROOT / module / "target" / "classes" for module in MODULES]
    missing = [str(path) for path in entries if not path.is_dir()]
    if missing:
        sys.exit(
            "java_candidate: build java/ first (make java-build); missing "
            + ", ".join(missing)
        )
    return os.pathsep.join(str(path) for path in entries)


def java_binary() -> str:
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin" / "java").exists():
        return str(Path(home) / "bin" / "java")
    return shutil.which("java") or "java"


def build(case: str, out: Path) -> int:
    jsonl = out.parent / "jsonl"
    shutil.rmtree(out, ignore_errors=True)
    shutil.rmtree(jsonl, ignore_errors=True)
    result = subprocess.run(
        [
            java_binary(), "-cp", classpath(), MAIN_CLASS,
            "--fixtures", str(ROOT / "fixtures" / "xferfee"),
            "--case", case,
            "--out", str(jsonl),
        ],
        cwd=ROOT,
    )
    if result.returncode:
        return result.returncode
    datasets = out / "datasets"
    datasets.mkdir(parents=True, exist_ok=True)
    for dsn, copybook in COPYBOOKS.items():
        source = jsonl / f"{dsn}.jsonl"
        rows = [
            json.loads(line)
            for line in source.read_text().splitlines() if line.strip()
        ]
        (datasets / (dsn + GENERATION)).write_bytes(
            b"".join(encode_record(copybook, r) for r in rows)
        )
        print(f"java_candidate: {case}: {dsn} <- {len(rows)} records")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    return build(args.case, args.out)


if __name__ == "__main__":
    raise SystemExit(main())
