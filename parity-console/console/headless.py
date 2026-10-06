#!/usr/bin/env python3
"""Run parity streams without the UI and print a per-stream verdict (used for CI-style checks)."""

from __future__ import annotations

import argparse
import sys
from typing import Any

from java_client import JavaEngineClient
from session import ParitySession
from streams import FIXTURE_CASES, GENERATED


def quiet(event: str, data: dict[str, Any]) -> None:
    if event == "row" and not data["verdict"]["match"]:
        tran = data["tran"]
        print(f"  DIFF {tran['tranId']}: " + ", ".join(
            f"{f['key']} cobol={f['cobol']} java={f['java']}"
            for f in data["verdict"]["fields"] if not f["match"]))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stream", action="append", help="fixture case or 'generated'")
    parser.add_argument("--all", action="store_true", help="all 7 fixtures plus the generated stream")
    parser.add_argument("--break-java", action="store_true", help="run Java with HALF_EVEN")
    parser.add_argument("--count", type=int, default=60)
    parser.add_argument("--seed", type=int, default=1250)
    args = parser.parse_args()
    streams = list(FIXTURE_CASES) + [GENERATED] if args.all else (args.stream or ["default"])
    java = JavaEngineClient()
    java.wait_ready()
    java.set_rounding("HALF_EVEN" if args.break_java else "HALF_UP")
    failed = 0
    try:
        for stream in streams:
            print(f"== {stream}  (java rounding {java.rounding()})")
            summary = ParitySession(quiet, java=java).run(stream, pace_ms=0,
                                                          count=args.count, seed=args.seed)
            totals, state = summary["totals"], summary["state"]
            print(f"  transactions {totals['transactions']}  transfers {totals['transfers']}  "
                  f"ignored {totals['ignored']}  MATCH {totals['matches']}  DIFF {totals['diffs']}  "
                  f"fees cobol {totals['cobolFees']} java {totals['javaFees']}")
            print(f"  end state: {state['accounts']} accounts, {state['ledgerRows']} ledger rows, "
                  f"{'equal' if state['equal'] else 'DIFFERENT'}")
            for name, result in summary["baseline"].items():
                if isinstance(result, dict):
                    print(f"  compare.py [{name}] vs recorded baseline: {result['verdict']}")
                else:
                    print(f"  java diffs == parity-naive diffs: {result}")
            print(f"  VERDICT: {summary['verdict']}")
            if args.break_java:
                # A broken Java must be caught, with exactly parity-naive's differences when recorded.
                caught = summary["verdict"] == "DIFF" and summary["totals"]["feeDiffs"] > 0
                failed += not caught or summary["baseline"].get("javaMatchesNaive") is False
            else:
                failed += summary["verdict"] != "PARITY"
    finally:
        java.set_rounding("HALF_UP")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
