#!/usr/bin/env python3
"""Compare Java counter snapshots and return codes with the recorded COBOL SYSOUT."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
CATALOG = ROOT / "ops" / "observability" / "counter-catalog.json"
LINE = re.compile(r"^(?P<program>[A-Z0-9]+): (?P<label>.+?) (?P<value>[+-]?\d+)\s*$")


def load_catalog() -> dict[tuple[str, str], dict]:
    counters = json.loads(CATALOG.read_text())["counters"]
    return {(c["program"], c["label"]): c for c in counters}


def expected_counters(sysout: Path, catalog: dict) -> dict[tuple[str, str], str]:
    values: dict[tuple[str, str], str] = {}
    for path in sorted(sysout.glob("STEP*.txt")):
        for line in path.read_text().splitlines():
            match = LINE.match(line)
            if not match:
                continue
            key = (match["program"], match["label"])
            if key in catalog:
                values[key] = match["value"]
    return values


def candidate_counters(sysout: Path) -> dict[tuple[str, str], str]:
    values: dict[tuple[str, str], str] = {}
    for path in sorted(sysout.glob("STEP*.json")):
        snapshot = json.loads(path.read_text())
        for counter in snapshot.get("counters", []):
            values[(snapshot["program"], counter["label"])] = counter["sysout"]
    return values


def compare_case(case: str, candidate: Path, catalog: dict) -> tuple[bool, list[str]]:
    expected_root = CHAIN_ROOT / case / "expected"
    rows: list[str] = []
    ok = True
    expected = expected_counters(expected_root / "sysout", catalog)
    actual = candidate_counters(candidate / "sysout")
    for key in sorted(set(expected) | set(actual), key=lambda k: catalog[k]["id"]):
        entry = catalog[key]
        want = expected.get(key, "(not displayed)")
        got = actual.get(key, "(not displayed)")
        status = "PASS" if want == got else "FAIL"
        ok &= status == "PASS"
        rows.append(f"| {case} | {entry['step']} | {key[0]}: {key[1]} "
                    f"| `{entry['metric']}` | {want} | {got} | {status} |")
    rc_path = candidate / "rc.json"
    actual_rc = json.loads(rc_path.read_text()) if rc_path.exists() else {}
    expected_rc = json.loads((expected_root / "rc.json").read_text())
    steps = dict(expected_rc.get("steps", {}))
    for step in actual_rc.get("steps", {}):
        steps.setdefault(step, None)
    for step in sorted(steps):
        want = expected_rc.get("steps", {}).get(step, "(not run)")
        got = actual_rc.get("steps", {}).get(step, "(not run)")
        status = "PASS" if want == got else "FAIL"
        ok &= status == "PASS"
        rows.append(f"| {case} | {step} | return code | `xfer.step.return_code` "
                    f"| {want} | {got} | {status} |")
    want = expected_rc.get("maxcc")
    got = actual_rc.get("maxcc", "(missing)")
    status = "PASS" if want == got else "FAIL"
    ok &= status == "PASS"
    rows.append(f"| {case} | - | MAXCC | - | {want} | {got} | {status} |")
    return ok, rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case")
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--candidate-root", type=Path,
                        default=ROOT / "work" / "parity-java")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if bool(args.case) == args.all:
        parser.error("pass exactly one of --case NAME or --all")
    cases = ([args.case] if args.case else sorted(
        p.name for p in CHAIN_ROOT.iterdir() if (p / "case.json").exists()))
    catalog = load_catalog()
    lines = [
        "# Java counter and return-code parity",
        "",
        "| Case | Step | Counter | Metric | COBOL | Java | Status |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    failed = []
    for case in cases:
        ok, rows = compare_case(case, args.candidate_root / case, catalog)
        lines.extend(rows)
        if not ok:
            failed.append(case)
    lines.append("")
    lines.append(f"**Result:** {len(cases) - len(failed)}/{len(cases)} cases match"
                 + (f"; failing: {', '.join(failed)}" if failed else ""))
    report = "\n".join(lines) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report)
    print(report, end="")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
