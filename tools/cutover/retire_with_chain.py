#!/usr/bin/env python3
"""List the JCL members that retire with the xferfee chain at cut-over vs kept.

Builds on `make deadcode` (tools/deadcode/retire_split.py): the SMF verdict says
whether a member is idle, this script adds whether the member belongs to the
chain being replaced. A member belongs to the chain when it executes a chain
program/PROC or touches one of the GDGs the chain allocates with (+1).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "deadcode"))

from retire_split import AS_OF, inventory, programs, rows  # noqa: E402

UTILITIES = {"IDCAMS", "IEFBR14", "IEBGENER", "SORT", "IKJEFT01"}
GDG_NEW = re.compile(r"DSN=(?:&HLQ\.|AWS\.M2\.CARDDEMO\.)([\w.]+)\(\+1\)", re.IGNORECASE)
DSN_REF = re.compile(r"(?:DSN=|DEFINE\s+(?:GDG|GENERATIONDATAGROUP)\s*-?\s*\(\s*NAME\(|DELETE\s+)"
                     r"(?:&HLQ\.|AWS\.M2\.CARDDEMO\.)([\w.]+)", re.IGNORECASE)


def chain_footprint(chain: str) -> tuple[set[str], set[str]]:
    config = json.loads((ROOT / "tools/runjcl/chains.json").read_text())[chain]
    names: set[str] = set()
    gdgs: set[str] = set()
    for job in config["jobs"]:
        path = ROOT / job
        texts = [path.read_text(errors="replace")]
        for name in programs(path):
            proc = ROOT / "jcl" / "proc" / f"{name}.prc"
            if proc.exists():
                names.add(name)
                names.update(programs(proc))
                texts.append(proc.read_text(errors="replace"))
            else:
                names.add(name)
        for text in texts:
            gdgs.update(m.upper() for m in GDG_NEW.findall(text))
    return names - UTILITIES, gdgs


def member_refs(path: Path) -> tuple[set[str], set[str]]:
    text = "\n".join(line for line in path.read_text(errors="replace").splitlines()
                     if not line.startswith("//*"))
    dsns = {m.upper().rstrip(".") for m in DSN_REF.findall(text)}
    dsns = {re.sub(r"\(.*$", "", dsn) for dsn in dsns}
    return set(programs(path)), dsns


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chain", default="xferfee")
    parser.add_argument("--smf", type=Path, default=ROOT / "ops/smf/job_activity.csv")
    args = parser.parse_args()
    chain_programs, chain_gdgs = chain_footprint(args.chain)
    activity = rows(args.smf)
    print(f"Chain `{args.chain}` programs/PROCs: {', '.join(sorted(chain_programs))}")
    print(f"Chain GDGs: {', '.join(sorted(chain_gdgs))}")
    print(f"SMF as of {AS_OF.isoformat()} ({args.smf.relative_to(ROOT)})")
    print()
    print("| Job | SMF verdict | Chain link | Cut-over action |")
    print("|---|---|---|---|")
    retired: list[str] = []
    kept: list[str] = []
    for name, path in inventory().items():
        progs, dsns = member_refs(path)
        row = activity.get(name)
        if row is None:
            verdict = "RETIRE?"
        else:
            idle = (AS_OF - date.fromisoformat(row["LAST_RUN_TS"][:10])).days
            verdict = "RETIRE" if idle > 3 * 365 else "MIGRATE"
        links = sorted(progs & chain_programs) + sorted(dsns & chain_gdgs)
        if not links:
            kept.append(name)
            continue
        action = ("RETIRE WITH CHAIN (hold until rollback window closes)"
                  if verdict == "MIGRATE" else "RETIRE (already idle per SMF)")
        retired.append(name)
        print(f"| {name} | {verdict} | {', '.join(links)} | {action} |")
    print()
    print(f"RETIRED WITH CHAIN {len(retired)}: {', '.join(retired)}")
    print(f"KEPT (not part of chain; SMF verdict unchanged) {len(kept)}: {', '.join(kept)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
