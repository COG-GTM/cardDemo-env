#!/usr/bin/env python3
"""Split JCL inventory into idle jobs to retire and jobs to migrate."""

from __future__ import annotations

import argparse
import csv
import re
from datetime import date
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
AS_OF = date(2026, 9, 15)
SMF_FIELDS = ("JOB_NAME", "LAST_RUN_TS")


def inventory() -> dict[str, Path]:
    result = {}
    paths = sorted(
        (path for path in (ROOT / "jcl").iterdir()
         if path.is_file() and path.suffix.lower() == ".jcl"),
        key=lambda item: item.name.upper(),
    )
    for path in paths:
        text = path.read_text(errors="replace")
        match = re.search(r"^//([\w$#@]+)\s+JOB\b", text, re.IGNORECASE | re.MULTILINE)
        result[(match.group(1) if match else path.stem).upper()] = path
    return result


def programs(path: Path) -> list[str]:
    values = []
    for line in path.read_text(errors="replace").splitlines():
        if line.startswith("//*"):
            continue
        values.extend(re.findall(
            r"\bEXEC\s+(?:PGM|PROC)=([\w$#@.-]+)",
            line,
            re.IGNORECASE,
        ))
    return sorted({value.upper() for value in values})


def rows(path: Path) -> dict[str, dict[str, str]]:
    with path.open(newline="") as stream:
        return {
            row["JOB_NAME"].upper(): row
            for row in csv.DictReader(stream)
        }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--smf", type=Path, default=ROOT / "ops/smf/job_activity.csv")
    args = parser.parse_args()
    jobs = inventory()
    activity = rows(args.smf)
    print("| Job | Last run | Days idle | Programs referenced | Verdict |")
    print("|---|---|---:|---|---|")
    retire = migrate = 0
    for name, path in jobs.items():
        row = activity.get(name)
        if row is None:
            print(f"| {name} | NEVER-RUN | — | {', '.join(programs(path))} | RETIRE? |")
            retire += 1
            continue
        last = date.fromisoformat(row["LAST_RUN_TS"][:10])
        idle = (AS_OF - last).days
        verdict = "RETIRE" if idle > 3 * 365 else "MIGRATE"
        if verdict == "RETIRE":
            retire += 1
        else:
            migrate += 1
        print(f"| {name} | {last.isoformat()} | {idle} | "
              f"{', '.join(programs(path))} | {verdict} |")
    for name, row in sorted(activity.items()):
        if name not in jobs:
            print(f"| {name} | {row['LAST_RUN_TS'][:10]} | — | — | NO MEMBER |")
    total = retire + migrate
    retire_pct = 100 * retire / total if total else 0
    migrate_pct = 100 * migrate / total if total else 0
    print()
    print(f"RETIRE {retire} ({retire_pct:.1f}%) / "
          f"MIGRATE {migrate} ({migrate_pct:.1f}%)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
