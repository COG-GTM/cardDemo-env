#!/usr/bin/env python3
"""Codec parity: re-encode the recorded COBOL outputs with the Python and Java codecs.

For each xferfee case the expected datasets are decoded to JSON Lines, then encoded
into candidate directories by tools/parity/copybook.py (--codec=python) and by the
legacy-adapter CLI (--codec=java). Every candidate must pass compare.py against the
recorded fixture, and the two codecs must produce byte-identical datasets.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from decimal import Decimal
from pathlib import Path

from compare import CASES, CHAIN_ROOT, ROOT, compare_case, dataset_file
from copybook import decode_record, encode_record, parse_copybook, record_length

DEFAULT_JAR = (
    ROOT / "java" / "legacy-adapter" / "target"
    / "legacy-adapter-0.1.0-SNAPSHOT-cli.jar"
)
PASSTHROUGH = ("db2_after", "sysout", "rc.json")


def json_value(value: object, scale: int) -> object:
    if isinstance(value, Decimal):
        return format(value, f".{scale}f")
    return value


def decode_case(case: str, out: Path) -> dict:
    metadata = json.loads((CHAIN_ROOT / case / "case.json").read_text())
    expected = CHAIN_ROOT / case / "expected"
    out.mkdir(parents=True, exist_ok=True)
    for output in metadata["outputs"]:
        source = dataset_file(expected / "datasets", output["dsn"])
        if source is None:
            raise FileNotFoundError(f"{case}: no expected dataset {output['dsn']}")
        data = source.read_bytes()
        lines = []
        if output.get("text"):
            for line in data.decode("latin-1").split("\n")[:-1]:
                lines.append(json.dumps({"line": line}))
        else:
            copybook = output["copybook"]
            fields = parse_copybook(copybook)
            scales = {name: scale for name, _, _, _, scale, _ in fields}
            length = record_length(fields)
            for offset in range(0, len(data), length):
                values = decode_record(copybook, data[offset:offset + length])
                lines.append(json.dumps(
                    {k: json_value(v, scales[k]) for k, v in values.items()}
                ))
        (out / f"{output['dsn']}.jsonl").write_text(
            "".join(line + "\n" for line in lines)
        )
    for name in PASSTHROUGH:
        copy(expected / name, out / name)
    return metadata


def copy(source: Path, target: Path) -> None:
    if target.exists():
        shutil.rmtree(target) if target.is_dir() else target.unlink()
    if source.is_dir():
        shutil.copytree(source, target)
    elif source.exists():
        shutil.copy2(source, target)


def encode_python(metadata: dict, decoded: Path, candidate: Path) -> None:
    datasets = candidate / "datasets"
    datasets.mkdir(parents=True, exist_ok=True)
    for output in metadata["outputs"]:
        rows = [
            json.loads(line)
            for line in (decoded / f"{output['dsn']}.jsonl").read_text().splitlines()
            if line.strip()
        ]
        if output.get("text"):
            content = "".join(row["line"] + "\n" for row in rows).encode("latin-1")
        else:
            content = b"".join(encode_record(output["copybook"], row) for row in rows)
        (datasets / f"{output['dsn']}.G0001V00").write_bytes(content)
    for name in PASSTHROUGH:
        copy(decoded / name, candidate / name)


def encode_java(jar: Path, decoded: Path, candidate: Path, case: str) -> None:
    subprocess.run(
        [
            "java", "-jar", str(jar), "encode-candidate",
            "--copybooks", str(ROOT / "copybook"),
            "--case", str(CHAIN_ROOT / case / "case.json"),
            "--decoded", str(decoded),
            "--out", str(candidate),
            "--sign", "overpunch",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
    )


def byte_diffs(metadata: dict, left: Path, right: Path) -> list[str]:
    problems = []
    for output in metadata["outputs"]:
        name = f"{output['dsn']}.G0001V00"
        a = (left / "datasets" / name).read_bytes()
        b = (right / "datasets" / name).read_bytes()
        if a != b:
            at = next(
                (i for i, (x, y) in enumerate(zip(a, b)) if x != y),
                min(len(a), len(b)),
            )
            problems.append(f"{output['dsn']}: python and java differ at byte {at}")
    return problems


def run_case(case: str, codecs: list[str], jar: Path) -> tuple[bool, str]:
    root = ROOT / "work" / "parity" / case / "codec"
    if root.exists():
        shutil.rmtree(root)
    decoded = root / "decoded"
    metadata = decode_case(case, decoded)
    candidates = {}
    for codec in codecs:
        candidate = root / codec / "candidate"
        if codec == "python":
            encode_python(metadata, decoded, candidate)
        else:
            encode_java(jar, decoded, candidate, case)
        candidates[codec] = candidate
    results = []
    ok = True
    for codec, candidate in candidates.items():
        _, rc = compare_case(case, candidate)
        results.append(f"{codec}={'PASS' if rc == 0 else 'FAIL'}")
        ok &= rc == 0
    if len(candidates) == 2:
        problems = byte_diffs(metadata, candidates["python"], candidates["java"])
        results.append("bytes=" + ("IDENTICAL" if not problems else "DIFFERENT"))
        for problem in problems:
            results.append(f"\n    {problem}")
        ok &= not problems
    return ok, " ".join(results)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--case", action="append", choices=CASES,
                        help="case to run (repeatable); default: all cases")
    parser.add_argument("--codec", choices=("java", "python", "both"), default="both")
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    args = parser.parse_args()
    codecs = ["python", "java"] if args.codec == "both" else [args.codec]
    if "java" in codecs and not args.jar.exists():
        parser.error(f"{args.jar} not found; run `make java-build` first")
    overall = True
    for case in args.case or CASES:
        ok, summary = run_case(case, codecs, args.jar)
        overall &= ok
        print(f"{case:<13} {'PASS' if ok else 'FAIL'}  {summary}")
    print("CODEC PARITY:", "PASS" if overall else "FAIL")
    return 0 if overall else 1


if __name__ == "__main__":
    sys.exit(main())
