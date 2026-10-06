#!/usr/bin/env python3
"""Check that --codec=python and --codec=java parity-replay candidates are identical.

Datasets are compared record by record, in order, on every decoded copybook
field (fillers excluded, as in compare.py); reports, DB2 dumps, SYSOUT and
rc.json must match exactly. It also reports which Java-codec datasets are
byte-identical to the GnuCOBOL recording.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from copybook import decode_record, parse_copybook, record_length


ROOT = Path(__file__).resolve().parents[2]
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
WORK = ROOT / "work" / "parity-java"


def decoded(path: Path, copybook: str) -> list[dict]:
    length = record_length(parse_copybook(copybook))
    data = path.read_bytes()
    return [decode_record(copybook, data[i:i + length]) for i in range(0, len(data), length)]


def files(root: Path) -> set[str]:
    return {str(p.relative_to(root)) for p in root.rglob("*") if p.is_file()}


def check(case: str) -> int:
    meta = json.loads((CHAIN_ROOT / case / "case.json").read_text())
    copybooks = {o["dsn"]: o.get("copybook") for o in meta["outputs"]}
    python_root = WORK / case / "candidate"
    java_root = WORK / "java-codec" / case / "candidate"
    expected_root = CHAIN_ROOT / case / "expected" / "datasets"
    diffs: list[str] = []
    names = files(python_root) | files(java_root)
    for name in sorted(names):
        left, right = python_root / name, java_root / name
        if not left.exists() or not right.exists():
            diffs.append(f"{name}: only in {'java' if right.exists() else 'python'} candidate")
            continue
        dsn = Path(name).name.rsplit(".G", 1)[0]
        copybook = copybooks.get(dsn) if name.startswith("datasets/") else None
        if copybook:
            if decoded(left, copybook) != decoded(right, copybook):
                diffs.append(f"{name}: decoded records differ")
        elif name == "rc.json":
            if json.loads(left.read_text()) != json.loads(right.read_text()):
                diffs.append("rc.json differs")
        elif left.read_bytes() != right.read_bytes():
            diffs.append(f"{name}: content differs")
    byte_identical = []
    for dataset in sorted(p for p in java_root.glob("datasets/*")):
        expected = expected_root / dataset.name
        same = expected.exists() and expected.read_bytes() == dataset.read_bytes()
        byte_identical.append(f"  {dataset.name}: {'byte-identical' if same else 'BYTES DIFFER'} to recording")
    print(f"## {case}")
    for line in diffs:
        print(f"- {line}")
    print("\n".join(byte_identical))
    print(f"CODEC IDENTITY: {'PASS' if not diffs else 'FAIL'} ({len(names)} files)")
    return 0 if not diffs else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--case", action="append", required=True)
    args = parser.parse_args()
    return max(check(case) for case in args.case)


if __name__ == "__main__":
    raise SystemExit(main())
