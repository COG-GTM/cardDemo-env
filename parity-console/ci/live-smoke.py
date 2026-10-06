#!/usr/bin/env python3
"""Drive a running parity console over SSE against the real GnuCOBOL feed and assert the verdicts."""
import json
import sys
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
CHECKS = [
    ("default", False, "parity"),
    ("half_cent", False, "parity"),
    ("rate_change", False, "parity"),
    ("at_cap", False, "parity"),
    ("half_cent", True, "diff"),
    ("at_cap", True, "parity"),
]


def run(case, break_it):
    url = f"{BASE}/api/run?case={case}&breakIt={str(break_it).lower()}&delayMs=0"
    event, done, engine = None, None, None
    with urllib.request.urlopen(url, timeout=600) as resp:
        for raw in resp:
            line = raw.decode().rstrip("\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data = json.loads(line[5:])
                if event == "start":
                    engine = data.get("cobolEngine")
                elif event == "txn":
                    print(f"  {data['tranId']} {data['verdict']}")
                elif event == "failed":
                    raise SystemExit(f"{case}: run failed: {data}")
                elif event == "done":
                    done = data
                    break
    return engine, done


failures = 0
for case, break_it, want in CHECKS:
    print(f"{case} breakIt={break_it}")
    engine, done = run(case, break_it)
    got = "parity" if done["diff"] == 0 and done["match"] == done["count"] else "diff"
    ok = got == want and done["count"] > 0
    failures += not ok
    print(f"  legacy={engine} -> {done} [{'OK' if ok else 'UNEXPECTED'}: want {want}]")
sys.exit(1 if failures else 0)
