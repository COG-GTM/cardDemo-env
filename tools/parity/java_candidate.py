#!/usr/bin/env python3
"""Run the Java xferfee chain (java/shadow-run) and diff it against COBOL.

    java_candidate.py --case default          # one recorded case
    java_candidate.py --all                   # every recorded case
    java_candidate.py --input-dir D --rules R [--ledger L] --out O   # any day

Case mode writes ``work/parity-java/<case>/candidate`` (same layout as a recorded
``expected/`` tree) and ``work/parity-java/<case>/report.md`` and exits non-zero
on any difference.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from compare import CASES, CHAIN_ROOT, compare_dirs  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "java" / "shadow-run" / "target" / "shadow-run.jar"


def java_binary() -> str:
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin" / "java") if home else "java"


def build_jar(force: bool = False) -> None:
    if JAR.exists() and not force:
        return
    mvn = os.environ.get("MVN", "mvn -B -q").split()
    subprocess.run(
        [*mvn, "-f", str(ROOT / "java" / "pom.xml"), "package", "-DskipTests"],
        check=True,
    )


def run_java(args: list[str], out: Path) -> dict | None:
    """Run the shadow-run jar; returns rc.json, or None if Java did not finish."""
    if out.exists():
        shutil.rmtree(out)
    result = subprocess.run(
        [java_binary(), "-jar", str(JAR), *args, "--out", str(out)],
        cwd=ROOT,
    )
    rc_file = out / "rc.json"
    if not rc_file.exists():
        print(f"java leg failed (exit {result.returncode}), no {rc_file}",
              file=sys.stderr)
        return None
    return json.loads(rc_file.read_text())


def parity_case(case: str, root: Path) -> tuple[int, int, int]:
    """Java candidate vs recorded COBOL outputs: (rc, field diffs, record diffs)."""
    candidate = root / case / "candidate"
    if run_java(["--case", case, "--fixtures", str(CHAIN_ROOT)], candidate) is None:
        return 2, 0, 0
    metadata = json.loads((CHAIN_ROOT / case / "case.json").read_text())
    report, rc, fields, records = compare_dirs(
        case, metadata, CHAIN_ROOT / case / "expected", candidate,
        root / case / "report.md",
    )
    print(report.rstrip().splitlines()[-1])
    return rc, fields, records


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--case", choices=CASES)
    mode.add_argument("--all", action="store_true")
    mode.add_argument("--input-dir", type=Path)
    parser.add_argument("--rules", type=Path)
    parser.add_argument("--ledger", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--build", action="store_true",
                        help="rebuild java/shadow-run before running")
    args = parser.parse_args()
    build_jar(args.build)

    if args.input_dir:
        if not args.rules or not args.out:
            parser.error("--input-dir needs --rules and --out")
        extra = ["--ledger", str(args.ledger)] if args.ledger else []
        rc = run_java(
            ["--input-dir", str(args.input_dir), "--rules", str(args.rules), *extra],
            args.out,
        )
        return 0 if rc is not None else 2

    root = args.out or ROOT / "work" / "parity-java"
    cases = CASES if args.all else (args.case,)
    rows = []
    worst = 0
    for case in cases:
        print(f"== parity-java {case}")
        rc, fields, records = parity_case(case, root)
        worst = max(worst, rc)
        status = {0: "PASS", 1: "FAIL", 2: "ERROR"}[rc]
        rows.append(f"| {case} | {status} | {fields} | {records} |")
    summary = "\n".join([
        "# Java parity summary (java/shadow-run vs COBOL recordings)",
        "",
        "| Case | Result | Field diffs | Record diffs |",
        "|---|---|---|---|",
        *rows,
    ]) + "\n"
    root.mkdir(parents=True, exist_ok=True)
    (root / "summary.md").write_text(summary)
    print(summary)
    return worst


if __name__ == "__main__":
    raise SystemExit(main())
