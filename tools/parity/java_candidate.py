#!/usr/bin/env python3
"""Run parity-replay for one case and lay its output out as a compare.py candidate.

parity-replay writes JSON lines keyed by copybook field names; this script encodes
them back into fixed-width datasets so compare.py diffs them exactly as it diffs
a COBOL recording.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from pathlib import Path

from copybook import encode_record


ROOT = Path(__file__).resolve().parents[2]
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
DEFAULT_JAR = ROOT / "java" / "parity-replay" / "target" / "parity-replay.jar"


def run_replay(java: str, jar: Path, case: str, raw: Path) -> None:
    if raw.exists():
        shutil.rmtree(raw)
    subprocess.run(
        [java, "-jar", str(jar), "--case", case,
         "--fixtures", str(CHAIN_ROOT), "--out", str(raw)],
        cwd=ROOT,
        check=True,
    )


def encode_datasets(case: str, raw: Path, out: Path) -> None:
    metadata = json.loads((CHAIN_ROOT / case / "case.json").read_text())
    copybooks = {
        output["dsn"]: output["copybook"]
        for output in metadata["outputs"]
        if "copybook" in output
    }
    datasets = out / "datasets"
    datasets.mkdir(parents=True, exist_ok=True)
    for jsonl in sorted((raw / "jsonl").glob("*.jsonl")):
        dsn = jsonl.stem
        if dsn not in copybooks:
            raise SystemExit(f"{dsn}: no copybook in {case}/case.json")
        with (datasets / f"{dsn}.G0001V00").open("wb") as stream:
            for line in jsonl.read_text().splitlines():
                if line.strip():
                    stream.write(encode_record(copybooks[dsn], json.loads(line)))


def build_candidate(case: str, out: Path, java: str = "java", jar: Path = DEFAULT_JAR) -> Path:
    raw = out.parent / (out.name + "-raw")
    run_replay(java, jar, case, raw)
    if out.exists():
        shutil.rmtree(out)
    encode_datasets(case, raw, out)
    shutil.copytree(raw / "sysout", out / "sysout")
    shutil.copy2(raw / "rc.json", out / "rc.json")
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    build_candidate(args.case, args.out, args.java, args.jar)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
