#!/usr/bin/env python3
"""Compare recorded chain outputs and database state."""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
from decimal import Decimal
from pathlib import Path
from typing import Any

from copybook import decode_record, parse_copybook, record_length


ROOT = Path(__file__).resolve().parents[2]
CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
CASES = (
    "default",
    "under_cap",
    "at_cap",
    "rate_change",
    "zero_amount",
    "non_transfer",
    "half_cent",
)


def scalar(value: Any) -> str:
    if isinstance(value, Decimal):
        return format(value, "f")
    return str(value)


def normalized(value: str) -> str | Decimal:
    value = value.strip()
    try:
        return Decimal(value)
    except Exception:
        return value


def dataset_file(root: Path, dsn: str) -> Path | None:
    candidates = sorted(root.glob(dsn + ".G*V00"))
    if candidates:
        return candidates[-1]
    exact = root / dsn
    return exact if exact.exists() else None


def keyed_records(
    path: Path, copybook: str, keys: list[str]
) -> dict[tuple[str, ...], dict[str, Any]]:
    fields = parse_copybook(copybook)
    length = record_length(fields)
    data = path.read_bytes()
    records = {}
    for index in range(0, len(data), length):
        values = decode_record(copybook, data[index:index + length])
        key = tuple(scalar(values[name]) for name in keys)
        records[key] = values
    return records


def append_dataset_diffs(
    lines: list[str], expected: Path, actual: Path, metadata: dict[str, Any]
) -> tuple[int, int]:
    label = metadata["dsn"]
    if metadata.get("text"):
        expected_lines = expected.read_bytes().splitlines()
        actual_lines = actual.read_bytes().splitlines()
        field_diffs = 0
        record_diffs = 0
        for index, (left, right) in enumerate(
            zip(expected_lines, actual_lines), 1
        ):
            if left != right:
                lines.append(
                    f"| {label} | line {index} | text | "
                    "<different> | <different> |"
                )
                field_diffs += 1
        if len(expected_lines) != len(actual_lines):
            record_diffs += abs(len(expected_lines) - len(actual_lines))
            lines.append(
                f"- {label}: expected {len(expected_lines)} lines, "
                f"actual {len(actual_lines)} lines"
            )
        return field_diffs, record_diffs

    keys = metadata["key"]
    expected_rows = keyed_records(expected, metadata["copybook"], keys)
    actual_rows = keyed_records(actual, metadata["copybook"], keys)
    field_diffs = 0
    record_diffs = 0
    for key in sorted(set(expected_rows) | set(actual_rows)):
        key_text = ", ".join(key)
        if key not in expected_rows:
            lines.append(f"- {label}: extra record `{key_text}`")
            record_diffs += 1
            continue
        if key not in actual_rows:
            lines.append(f"- {label}: missing record `{key_text}`")
            record_diffs += 1
            continue
        left, right = expected_rows[key], actual_rows[key]
        for field in left:
            if left[field] != right.get(field):
                lines.append(
                    f"| {label} | {key_text} | {field} | "
                    f"{scalar(left[field])} | {scalar(right.get(field))} |"
                )
                field_diffs += 1
    return field_diffs, record_diffs


def csv_rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="") as stream:
        return [
            {key.upper(): value for key, value in row.items()}
            for row in csv.DictReader(stream)
        ]


def append_db_diffs(
    lines: list[str],
    expected_root: Path,
    actual_root: Path,
    metadata: dict[str, Any],
) -> tuple[int, int]:
    table = metadata["table"]
    expected_rows = csv_rows(expected_root / f"{table}.csv")
    actual_rows = csv_rows(actual_root / f"{table}.csv")
    keys = metadata["key"]
    expected_map = {
        tuple(normalized(row[key]) for key in keys): row for row in expected_rows
    }
    actual_map = {
        tuple(normalized(row[key]) for key in keys): row for row in actual_rows
    }
    field_diffs = 0
    record_diffs = 0
    for key in sorted(set(expected_map) | set(actual_map)):
        key_text = ", ".join(key)
        if key not in expected_map:
            lines.append(f"- {table}: extra record `{key_text}`")
            record_diffs += 1
            continue
        if key not in actual_map:
            lines.append(f"- {table}: missing record `{key_text}`")
            record_diffs += 1
            continue
        for column, value in expected_map[key].items():
            if normalized(value) != normalized(actual_map[key].get(column, "")):
                lines.append(
                    f"| {table} | {key_text} | {column} | "
                    f"{value} | {actual_map[key].get(column)} |"
                )
                field_diffs += 1
    return field_diffs, record_diffs


def append_sysout_diffs(
    lines: list[str], expected_root: Path, actual_root: Path
) -> tuple[int, int]:
    field_diffs = 0
    record_diffs = 0
    expected_files = {path.name for path in expected_root.glob("*.txt")}
    actual_files = {path.name for path in actual_root.glob("*.txt")}
    for name in sorted(expected_files | actual_files):
        expected = expected_root / name
        actual = actual_root / name
        if not expected.exists():
            lines.append(f"- SYSOUT/{name}: extra file")
            record_diffs += 1
            continue
        if not actual.exists():
            lines.append(f"- SYSOUT/{name}: missing file")
            record_diffs += 1
            continue
        left = expected.read_text().splitlines()
        right = actual.read_text().splitlines()
        for index, (expected_line, actual_line) in enumerate(
            zip(left, right), 1
        ):
            if expected_line != actual_line:
                lines.append(
                    f"| SYSOUT/{name} | line {index} | text | "
                    f"{expected_line} | {actual_line} |"
                )
                field_diffs += 1
        if len(left) != len(right):
            lines.append(
                f"- SYSOUT/{name}: expected {len(left)} lines, "
                f"actual {len(right)} lines"
            )
            record_diffs += abs(len(left) - len(right))
    return field_diffs, record_diffs


def compare_case(case: str, candidate: Path | None = None) -> tuple[str, int]:
    expected_root = CHAIN_ROOT / case / "expected"
    if candidate is None:
        candidate = ROOT / "work" / "parity" / case / "candidate"
        subprocess.run(
            [
                sys.executable,
                str(ROOT / "tools" / "parity" / "recorder.py"),
                "--chain",
                "xferfee",
                "--case",
                case,
                "--out",
                str(candidate),
            ],
            cwd=ROOT,
            check=True,
        )
    metadata = json.loads(
        (CHAIN_ROOT / case / "case.json").read_text()
    )
    lines = [
        f"# Parity: xferfee / {case}",
        "",
        "| Dataset/Table | Record key | Field | Expected | Actual |",
        "|---|---|---|---|---|",
    ]
    field_diffs = 0
    record_diffs = 0
    for output in metadata["outputs"]:
        expected = dataset_file(expected_root / "datasets", output["dsn"])
        actual = dataset_file(candidate / "datasets", output["dsn"])
        if expected is None and actual is None:
            continue
        if expected is None:
            lines.append(f"- {output['dsn']}: extra dataset")
            record_diffs += 1
            continue
        if actual is None:
            lines.append(f"- {output['dsn']}: missing dataset")
            record_diffs += 1
            continue
        fields, records = append_dataset_diffs(
            lines, expected, actual, output
        )
        field_diffs += fields
        record_diffs += records

    for table in metadata["db2"]:
        fields, records = append_db_diffs(
            lines,
            expected_root / "db2_after",
            candidate / "db2_after",
            table,
        )
        field_diffs += fields
        record_diffs += records
    fields, records = append_sysout_diffs(
        lines, expected_root / "sysout", candidate / "sysout"
    )
    field_diffs += fields
    record_diffs += records

    expected_rc = json.loads((expected_root / "rc.json").read_text())
    actual_rc = json.loads((candidate / "rc.json").read_text())
    if expected_rc != actual_rc:
        lines.append(
            f"- RC: expected `{json.dumps(expected_rc, sort_keys=True)}`, "
            f"actual `{json.dumps(actual_rc, sort_keys=True)}`"
        )
        field_diffs += 1

    if field_diffs or record_diffs:
        verdict = f"PARITY: FAIL ({field_diffs} field differences, "
        verdict += f"{record_diffs} record differences)"
        rc = 1
    else:
        verdict = "PARITY: PASS"
        rc = 0
    details = lines[4:]
    field_lines = [line for line in details if line.startswith("| ")]
    record_lines = [
        line for line in details
        if line.startswith("- ") and not line.startswith("- RC:")
    ]
    rc_lines = [line for line in details if line.startswith("- RC:")]
    lines = [
        f"# Parity: xferfee / {case} — {'FAIL' if rc else 'PASS'}",
        "",
        "| Dataset/Table | Record key | Field | Expected | Actual |",
        "|---|---|---|---|---|",
    ]
    lines.extend(field_lines)
    lines.extend(["", "## Missing/extra records"])
    lines.extend(record_lines or ["(none)"])
    lines.extend(["", "## RC differences"])
    lines.extend(rc_lines or ["(none)"])
    lines.extend(["", verdict])
    report = "\n".join(lines) + "\n"
    report_path = candidate.parent / "report.md"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report)
    return report, rc


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chain", default="xferfee")
    parser.add_argument("--case", default=None)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.chain != "xferfee":
        parser.error("only the xferfee chain is supported")
    cases = CASES if args.all else (args.case,)
    if cases[0] is None:
        parser.error("--case is required unless --all is used")
    aggregate: list[str] = []
    overall = 0
    for case in cases:
        candidate = args.candidate if len(cases) == 1 else None
        report, rc = compare_case(case, candidate)
        aggregate.append(report)
        overall = max(overall, rc)
    if args.all:
        destination = args.report or ROOT / "work" / "parity" / "report.md"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text("\n".join(aggregate))
        print(destination.read_text(), end="")
    elif args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(aggregate[0])
        print(aggregate[0], end="")
    else:
        print(aggregate[0], end="")
    return overall


if __name__ == "__main__":
    raise SystemExit(main())
