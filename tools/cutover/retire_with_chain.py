#!/usr/bin/env python3
"""List the JCL members retired with the xferfee chain at cut-over vs kept.

Runs the `make deadcode` split (tools/deadcode/retire_split.py), selects the members that
belong to the xferfee chain (they run CBXFR01C / XFERFEE / CBXFR03C, the XFERFEEP proc,
or touch the chain's GDGs) and assigns each a cut-over action. Everything else in the
inventory is untouched by COG-1252 and is only counted.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHAIN_PROGRAMS = {"CBXFR01C", "XFERFEE", "CBXFR03C", "XFERFEEP"}
CHAIN_DATASETS = re.compile(r"AWS\.M2\.CARDDEMO\.(XFER\.|ACCTDATA\.XFER)", re.IGNORECASE)

# member -> (cut-over action, rationale)
ACTIONS = {
    "XFRDAILY": (
        "RETIRE WITH CHAIN (hold at T0, delete at window close)",
        "Daily chain job; replaced by the Java services. Scheduler-held, not deleted, "
        "until the rollback window closes: it is the rollback path.",
    ),
    "XFREXTR": (
        "RETIRE WITH CHAIN",
        "Standalone CBXFR01C extract (Control-M DAILY-TransferFeePosting); replaced by "
        "transfer-intake-service. Not needed for rollback (XFRDAILY runs STEP010 itself).",
    ),
    "XFRRECON": (
        "RETIRE WITH CHAIN",
        "Standalone CBXFR03C report; replaced by reconciliation-service. Not needed for "
        "rollback (XFRDAILY runs STEP030 itself).",
    ),
    "XFERFEE": (
        "RETIRE WITH CHAIN (see Decisions needed)",
        "Standalone XFERFEE posting job between XFREXTR and XFRRECON in the same Control-M "
        "folder; replaced by fee-policy + account-posting-service. Not in the ticket's list.",
    ),
    "DEFGDGX": (
        "KEEP until window close",
        "Defines the chain GDG bases. Idle per SMF, but ACCTDATA.XFER/XFER.FEES must stay "
        "defined: the legacy-adapter keeps cataloguing (+1) and rollback reads (0).",
    ),
    "XFRPURGE": (
        "RETIRE (already dead, independent of cut-over)",
        "IEFBR14 purge of XFER.FEES(-1)/(-2); idle since 2019, GDG LIMIT rolls generations "
        "off. Not used by rollback.",
    ),
}
EXTRA_KEPT = [
    ("jcl/proc/XFERFEEP.prc", "KEEP until window close",
     "Procedure executed by XFRDAILY; procs are not in the dead-code inventory."),
    ("ops/cutover/XFRRBACK.jcl", "KEEP (new, rollback only)",
     "Restores ACCTDATA.PS from ACCTDATA.XFER(0). Lives outside jcl/ so the dead-code "
     "split and chain graph are unchanged."),
]
ROW = re.compile(r"^\| (\S+) \| ([^|]+) \| ([^|]+) \| ([^|]*) \| ([^|]+) \|$")


def deadcode() -> tuple[dict[str, tuple[str, str, str, str]], str]:
    subprocess.run([sys.executable, str(ROOT / "tools" / "deadcode" / "gen_smf.py")],
                   cwd=ROOT, check=True, capture_output=True)
    out = subprocess.run([sys.executable, str(ROOT / "tools" / "deadcode" / "retire_split.py")],
                         cwd=ROOT, check=True, capture_output=True, text=True).stdout
    rows = {}
    for line in out.splitlines():
        match = ROW.match(line.strip())
        if match and match.group(1) not in ("Job", "---"):
            rows[match.group(1)] = tuple(group.strip() for group in match.groups()[1:])
    summary = next((line for line in out.splitlines() if line.startswith("RETIRE ")), "")
    return rows, summary


def chain_members() -> set[str]:
    members = set()
    for path in (ROOT / "jcl").glob("*.jcl"):
        text = "\n".join(line for line in path.read_text(errors="replace").splitlines()
                         if not line.startswith("//*"))
        job = re.search(r"^//([\w$#@]+)\s+JOB\b", text, re.MULTILINE)
        name = (job.group(1) if job else path.stem).upper()
        programs = {p.upper() for p in re.findall(r"\bEXEC\s+(?:PGM=|PROC=)?([\w$#@]+)", text)}
        if programs & CHAIN_PROGRAMS or CHAIN_DATASETS.search(text):
            members.add(name)
    return members


def main() -> int:
    rows, summary = deadcode()
    members = chain_members()
    unknown = members - ACTIONS.keys()
    print("## xferfee chain members (`make deadcode` + cut-over action)")
    print()
    print("| Member | Last run | Days idle | Programs | `make deadcode` | Cut-over action | Why |")
    print("|---|---|---:|---|---|---|---|")
    for name in sorted(members, key=lambda n: (not ACTIONS.get(n, ("",))[0].startswith("RETIRE WITH"), n)):
        last, idle, programs, verdict = rows.get(name, ("?", "?", "?", "?"))
        action, why = ACTIONS.get(name, ("UNCLASSIFIED", "chain member without a cut-over decision"))
        print(f"| {name} | {last} | {idle} | {programs} | {verdict} | {action} | {why} |")
    for member, action, why in EXTRA_KEPT:
        print(f"| `{member}` | — | — | — | not scanned | {action} | {why} |")
    others = {name: row for name, row in rows.items() if name not in members}
    other_retire = sum(1 for row in others.values() if row[3].startswith("RETIRE"))
    print()
    print(f"Retired with the chain: "
          f"{', '.join(n for n in sorted(members) if ACTIONS.get(n, ('',))[0].startswith('RETIRE WITH'))}")
    print(f"Kept through the rollback window: "
          f"{', '.join(n for n in sorted(members) if ACTIONS.get(n, ('',))[0].startswith('KEEP'))}, "
          f"XFERFEEP (proc), XFRRBACK (new)")
    print(f"Other {len(others)} inventory members: unaffected by COG-1252 "
          f"({other_retire} RETIRE / {len(others) - other_retire} MIGRATE per `make deadcode`).")
    print(f"`make deadcode` totals: {summary}")
    if unknown:
        print(f"ERROR: unclassified chain members: {', '.join(sorted(unknown))}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
