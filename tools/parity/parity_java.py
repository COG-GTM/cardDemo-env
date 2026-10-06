#!/usr/bin/env python3
"""make parity-java: replay fixtures through the Java port and diff against the COBOL recording.

For each case: java_candidate.py decode -> parity-replay -> java_candidate.py encode -> compare.py.
Cases default to every fixtures/xferfee/<case>/ that has a case.json and an expected/ recording.
"""

from __future__ import annotations

import argparse
import os
import shlex
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools" / "parity"
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
JAR = ROOT / "java" / "parity-replay" / "target" / "parity-replay.jar"
ORIGINAL = ("default", "under_cap", "at_cap", "rate_change", "zero_amount", "non_transfer", "half_cent")


def discover() -> list[str]:
    found = sorted(path.parent.name for path in CHAIN_ROOT.glob("*/case.json")
                   if (path.parent / "expected" / "rc.json").exists())
    return [case for case in ORIGINAL if case in found] + [case for case in found if case not in ORIGINAL]


def replay_command(runner: str, args: list[str]) -> list[str]:
    if runner == "docker":
        return ["docker", "compose", "--profile", "java", "run", "--rm", "-T", "parity-replay", *args]
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else "java"
    return [java, "-jar", str(JAR), *args]


def run(command: list[str], **kwargs) -> subprocess.CompletedProcess:
    print("+ " + " ".join(shlex.quote(part) for part in command), flush=True)
    return subprocess.run(command, cwd=ROOT, **kwargs)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", action="append", help="case name (repeatable; 'all' or omitted = every case)")
    parser.add_argument("--mode", default=os.environ.get("JAVA_MODE", "inproc"), choices=("inproc", "events"))
    parser.add_argument("--posting-mode", default=os.environ.get("POSTING_MODE", "batch-atomic"),
                        choices=("batch-atomic", "per-transfer"))
    parser.add_argument("--runner", default=os.environ.get("PARITY_JAVA_RUNNER", "docker"),
                        choices=("docker", "host"))
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    cases = args.case or ["all"]
    if "all" in cases:
        cases = discover()
    root = Path("work") / "parity-java" / args.mode / args.posting_mode
    summary = [f"# make parity-java — mode={args.mode} posting-mode={args.posting_mode}", ""]
    failures = 0
    for case in cases:
        work = root / case
        steps = [
            [sys.executable, str(TOOLS / "java_candidate.py"), "decode", "--case", case, "--work", str(work)],
            replay_command(args.runner, ["--case", case, "--work", str(work), f"--mode={args.mode}",
                                         f"--posting-mode={args.posting_mode}"]),
            [sys.executable, str(TOOLS / "java_candidate.py"), "encode", "--case", case, "--work", str(work)],
        ]
        if any(run(step).returncode != 0 for step in steps):
            summary.append(f"# Parity: xferfee / {case} — ERROR (replay did not complete)")
            failures += 1
            continue
        compared = run([sys.executable, str(TOOLS / "compare.py"), "--chain", "xferfee", "--case", case,
                        "--candidate", str(work / "candidate")], capture_output=True, text=True)
        print(compared.stdout, end="")
        verdict = next((line for line in reversed(compared.stdout.splitlines()) if line.startswith("PARITY:")),
                       "PARITY: ERROR")
        summary.append(f"# Parity: xferfee / {case} — {'PASS' if compared.returncode == 0 else 'FAIL'}")
        summary.append(verdict)
        failures += compared.returncode != 0
    text = "\n".join(summary) + "\n"
    report = args.report or ROOT / root / "report.md"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(text)
    print(text, end="")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
