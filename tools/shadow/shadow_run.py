#!/usr/bin/env python3
"""Shadow-run one daily input through the COBOL chain and a candidate, then diff.

Both sides read the same staged input directory and CTL_XFER_PARM snapshot.
Reports land in work/shadow/<date>/report.{md,json}. Exit codes: 0 no
differences, 1 differences found, 2 a side failed to produce outputs.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import shlex
import shutil
import subprocess
import sys
import traceback
from decimal import Decimal
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "parity"))
sys.path.insert(0, str(ROOT / "tools" / "fixtures"))
sys.path.insert(0, str(ROOT))

import compare  # noqa: E402
from copybook import decode_record, parse_copybook, record_length  # noqa: E402
from gen_fixtures import write_synthetic_day  # noqa: E402

CHAIN_ROOT = ROOT / "fixtures" / "xferfee"
INPUT_FILES = ("ACCTDATA.PS", "CARDXREF.PS", "DALYTRAN.PS")
LEDGER_COLUMNS = (
    "tran_id,tran_dt,src_acct_id,tgt_acct_id,book_id,tran_amt,fee_amt,"
    "cap_applied\n"
)
FEES_DSN = "AWS.M2.CARDDEMO.XFER.FEES"
ACCOUNTS_DSN = "AWS.M2.CARDDEMO.ACCTDATA.XFER"
RECON_DSN = "AWS.M2.CARDDEMO.XFER.RECON.RPT"
JAVA_JAR = "java/parity-replay/target/parity-replay.jar"
DEFAULT_JAVA_CMD = (
    f"java -jar {JAVA_JAR} --case-dir {{day}} --out {{candidate}}"
)
MAX_MD_ROWS = 50


class CandidateError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def input_digest(input_dir: Path) -> dict[str, str]:
    files = sorted(input_dir.iterdir())
    return {path.name: sha256(path) for path in files if path.is_file()}


def stage_inputs(args: argparse.Namespace, day: Path) -> dict[str, Any]:
    input_dir = day / "input"
    if args.case:
        subprocess.run(
            [sys.executable, str(ROOT / "tools" / "fixtures" /
                                 "gen_fixtures.py"), "--case", args.case],
            cwd=ROOT, check=True,
        )
        shutil.copytree(CHAIN_ROOT / args.case / "input", input_dir)
        return {"kind": "case", "case": args.case}
    if args.input_dir:
        input_dir.mkdir(parents=True)
        for name in INPUT_FILES:
            shutil.copyfile(args.input_dir / name, input_dir / name)
        return {"kind": "input-dir", "path": str(args.input_dir)}
    write_synthetic_day(input_dir, args.date, args.synthetic, args.seed)
    return {
        "kind": "synthetic",
        "transactions": args.synthetic,
        "seed": args.seed,
    }


def stage_rules(args: argparse.Namespace, day: Path) -> Path:
    """Freeze the rule snapshot both sides will use into db2_before/."""
    before = day / "db2_before"
    before.mkdir(parents=True, exist_ok=True)
    rules = before / "CTL_XFER_PARM.csv"
    if args.rules:
        shutil.copyfile(args.rules, rules)
    elif args.case:
        shutil.copyfile(
            CHAIN_ROOT / args.case / "db2_before" / "CTL_XFER_PARM.csv", rules
        )
    elif args.legacy_dir:
        raise SystemExit("--rules is required with --legacy-dir")
    else:
        import recorder

        recorder.dump_table("CTL_XFER_PARM", rules)
    (before / "XFER_FEE_LEDGER.csv").write_text(LEDGER_COLUMNS)
    snapshot = day / "rules" / "CTL_XFER_PARM.csv"
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(rules, snapshot)
    return snapshot


def write_metadata(day: Path, source: dict[str, Any], date: str) -> None:
    metadata = json.loads((CHAIN_ROOT / "default" / "case.json").read_text())
    metadata["description"] = f"Shadow run {date} ({source['kind']})"
    metadata["run_date"] = date
    metadata["source"] = source
    (day / "case.json").write_text(json.dumps(metadata, indent=2) + "\n")


def run_legacy(day: Path, rules: Path, legacy_dir: Path | None) -> None:
    output = day / "legacy"
    if legacy_dir is not None:
        shutil.copytree(legacy_dir, output)
        return
    import recorder

    recorder.record_inputs(day / "input", output, rules)


def run_candidate(args: argparse.Namespace, day: Path, rules: Path) -> str:
    output = day / "candidate"
    if args.candidate == "legacy":
        import recorder

        recorder.record_inputs(day / "input", output, rules)
        return "tools/parity/recorder.py --input-dir (COBOL self-shadow)"
    if args.candidate == "naive":
        template = (
            f"{shlex.quote(sys.executable)} tools/parity/naive_ref.py "
            "--input-dir {input} --out {candidate}"
        )
    elif args.candidate == "java":
        template = args.candidate_cmd or DEFAULT_JAVA_CMD
        if args.candidate_cmd is None and not (ROOT / JAVA_JAR).exists():
            raise CandidateError(
                f"{JAVA_JAR} not found; the Java parity-replay harness "
                "(COG-1234) must be built first, or pass --candidate-cmd"
            )
    else:
        template = args.candidate_cmd
    values = {
        "day": day,
        "input": day / "input",
        "db2_before": day / "db2_before",
        "rules": rules,
        "candidate": output,
        "date": args.date,
    }
    command = template.format(
        **{key: shlex.quote(str(value)) for key, value in values.items()}
    )
    log = day / "candidate.log"
    with log.open("w") as stream:
        result = subprocess.run(
            command, shell=True, cwd=ROOT, stdout=stream,
            stderr=subprocess.STDOUT,
        )
    if result.returncode != 0:
        raise CandidateError(
            f"candidate command exited {result.returncode}: {command} "
            f"(see {log.relative_to(ROOT) if log.is_relative_to(ROOT) else log})"
        )
    return command


def dataset_rows(root: Path, dsn: str, copybook: str) -> list[dict[str, Any]]:
    path = compare.dataset_file(root / "datasets", dsn)
    if path is None:
        return []
    length = record_length(parse_copybook(copybook))
    data = path.read_bytes()
    return [
        decode_record(copybook, data[index:index + length])
        for index in range(0, len(data), length)
    ]


def money(value: Any) -> str | None:
    if value is None:
        return None
    return format(Decimal(str(value)).quantize(Decimal("0.01")), "f")


def keyed_section(
    legacy: dict[str, Any], candidate: dict[str, Any], key_name: str
) -> dict[str, Any]:
    rows = []
    for key in sorted(set(legacy) | set(candidate)):
        left, right = legacy.get(key), candidate.get(key)
        rows.append({
            key_name: key,
            "legacy": left,
            "candidate": right,
            "match": left == right,
        })
    return {
        "count": {"legacy": len(legacy), "candidate": len(candidate)},
        "mismatches": sum(not row["match"] for row in rows),
        "rows": rows,
    }


def fees_section(day: Path) -> dict[str, Any]:
    def load(root: Path) -> dict[str, Any]:
        return {
            str(row["XFE-TRAN-ID"]).strip(): {
                "fee": money(row["XFE-FEE-AMT"]),
                "amount": money(row["XFE-TRAN-AMT"]),
                "book": str(row["XFE-BOOK-ID"]).strip(),
                "cap_applied": str(row["XFE-CAP-APPLIED"]).strip(),
            }
            for row in dataset_rows(root, FEES_DSN, "CVXFR02Y")
        }

    legacy, candidate = load(day / "legacy"), load(day / "candidate")
    section = keyed_section(legacy, candidate, "tran_id")
    section["total_fee"] = {
        side: money(sum((Decimal(row["fee"]) for row in rows.values()),
                        Decimal("0")))
        for side, rows in (("legacy", legacy), ("candidate", candidate))
    }
    return section


def balances_section(day: Path) -> dict[str, Any]:
    def load(root: Path) -> dict[str, Any]:
        return {
            f"{int(row['ACCT-ID']):011d}": {
                "balance": money(row["ACCT-CURR-BAL"]),
                "cycle_credit": money(row["ACCT-CURR-CYC-CREDIT"]),
                "cycle_debit": money(row["ACCT-CURR-CYC-DEBIT"]),
            }
            for row in dataset_rows(root, ACCOUNTS_DSN, "CVACT01Y")
        }

    legacy, candidate = load(day / "legacy"), load(day / "candidate")
    section = keyed_section(legacy, candidate, "account")
    section["total_balance"] = {
        side: money(sum((Decimal(row["balance"]) for row in rows.values()),
                        Decimal("0")))
        for side, rows in (("legacy", legacy), ("candidate", candidate))
    }
    return section


def ledger_section(day: Path) -> dict[str, Any]:
    def load(root: Path) -> dict[str, Any]:
        path = root / "db2_after" / "XFER_FEE_LEDGER.csv"
        if not path.exists():
            return {}
        rows = {}
        for row in compare.csv_rows(path):
            rows[row["TRAN_ID"].strip()] = {
                "tran_dt": row["TRAN_DT"],
                "src": int(row["SRC_ACCT_ID"]),
                "tgt": int(row["TGT_ACCT_ID"]),
                "book": row["BOOK_ID"].strip(),
                "amount": money(row["TRAN_AMT"]),
                "fee": money(row["FEE_AMT"]),
                "cap_applied": row["CAP_APPLIED"].strip(),
            }
        return rows

    legacy, candidate = load(day / "legacy"), load(day / "candidate")
    section = keyed_section(legacy, candidate, "tran_id")
    section["total_fee"] = {
        side: money(sum((Decimal(row["fee"]) for row in rows.values()),
                        Decimal("0")))
        for side, rows in (("legacy", legacy), ("candidate", candidate))
    }
    return section


def recon_totals(root: Path) -> dict[str, Any]:
    path = compare.dataset_file(root / "datasets", RECON_DSN)
    if path is None:
        return {}
    totals: dict[str, Any] = {}
    for line in path.read_text(errors="replace").splitlines():
        match = re.match(
            r"\s*BOOK (\S+)\s+SUBTOTAL AMOUNT\s+(\S+)\s+FEE\s+(\S+)", line
        )
        if match:
            book = f"BOOK {match.group(1)}"
            # A book can reappear when input is not grouped by book.
            index = sum(name.startswith(book) for name in totals)
            name = book if index == 0 else f"{book} #{index + 1}"
            totals[name] = {"amount": match.group(2), "fee": match.group(3)}
        match = re.match(
            r"\s*GRAND TOTAL COUNT\s+(\d+)\s+AMOUNT\s+(\S+)\s+FEE\s+(\S+)",
            line,
        )
        if match:
            totals["GRAND TOTAL"] = {
                "count": int(match.group(1)),
                "amount": match.group(2),
                "fee": match.group(3),
            }
    return totals


def report_totals_section(day: Path) -> dict[str, Any]:
    return keyed_section(
        recon_totals(day / "legacy"), recon_totals(day / "candidate"), "line"
    )


def parse_verdict(report: str) -> dict[str, int]:
    match = re.search(
        r"PARITY: FAIL \((\d+) field differences, (\d+) record differences\)",
        report,
    )
    if not match:
        return {"field_differences": 0, "record_differences": 0}
    return {
        "field_differences": int(match.group(1)),
        "record_differences": int(match.group(2)),
    }


def cell(value: Any) -> str:
    if value is None:
        return "—"
    if isinstance(value, dict):
        value = ", ".join(f"{key}={item}" for key, item in value.items())
    return str(value).replace("|", "\\|")


def section_markdown(title: str, section: dict[str, Any], key: str,
                     totals: str | None) -> list[str]:
    lines = [f"## {title}", ""]
    count = section["count"]
    lines.append(
        f"Rows: legacy {count['legacy']}, candidate {count['candidate']}; "
        f"mismatches: **{section['mismatches']}**"
    )
    if totals:
        total = section[totals]
        lines.append(
            f"Total: legacy {total['legacy']}, candidate {total['candidate']}"
        )
    lines.append("")
    mismatches = [row for row in section["rows"] if not row["match"]]
    if not mismatches:
        lines.extend(["(no differences)", ""])
        return lines
    lines.extend([f"| {key} | Legacy | Candidate |", "|---|---|---|"])
    for row in mismatches[:MAX_MD_ROWS]:
        lines.append(
            f"| {row[key]} | {cell(row['legacy'])} | {cell(row['candidate'])} |"
        )
    if len(mismatches) > MAX_MD_ROWS:
        lines.append(
            f"\n…{len(mismatches) - MAX_MD_ROWS} more in report.json"
        )
    lines.append("")
    return lines


def write_reports(day: Path, result: dict[str, Any]) -> None:
    (day / "report.json").write_text(json.dumps(result, indent=2) + "\n")
    lines = [
        f"# Shadow run {result['date']} — {result['status']}",
        "",
        f"- Source: `{json.dumps(result['source'], sort_keys=True)}`",
        f"- Candidate: `{result['candidate']['name']}`"
        + (f" (`{result['candidate']['command']}`)"
           if result["candidate"].get("command") else ""),
        f"- Rule snapshot sha256: `{result['rules_sha256']}`",
    ]
    for name, digest in result["inputs"].items():
        lines.append(f"- Input `{name}` sha256: `{digest}`")
    lines.append(f"- Exit code: {result['exit_code']}")
    if result.get("error"):
        lines.extend(["", "## Error", "", "```", result["error"], "```"])
    if "compare" in result:
        compare_info = result["compare"]
        lines.extend([
            "",
            f"**{compare_info['verdict']}** — full field diff in "
            "[compare.md](compare.md)",
            "",
        ])
        for title, name, key, totals in (
            ("Per-transfer fees", "fees", "tran_id", "total_fee"),
            ("Account balances", "balances", "account", "total_balance"),
            ("XFER_FEE_LEDGER", "ledger", "tran_id", "total_fee"),
            ("Reconciliation report totals", "report_totals", "line", None),
        ):
            lines.extend(section_markdown(
                title, result["sections"][name], key, totals
            ))
    (day / "report.md").write_text("\n".join(lines).rstrip() + "\n")


def shadow(args: argparse.Namespace) -> int:
    day = args.out / args.date
    if day.exists():
        shutil.rmtree(day)
    day.mkdir(parents=True)
    source = stage_inputs(args, day)
    write_metadata(day, source, args.date)
    rules = stage_rules(args, day)
    digest = input_digest(day / "input")
    result: dict[str, Any] = {
        "date": args.date,
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(
            timespec="seconds"
        ),
        "source": source,
        "inputs": digest,
        "rules_sha256": sha256(rules),
        "candidate": {"name": args.candidate},
    }
    try:
        run_legacy(day, rules, args.legacy_dir)
    except Exception:
        result["error"] = "legacy run failed\n" + traceback.format_exc()
    if "error" not in result:
        try:
            result["candidate"]["command"] = run_candidate(args, day, rules)
        except Exception as exc:
            result["error"] = f"candidate run failed: {exc}"
    if "error" not in result and input_digest(day / "input") != digest:
        result["error"] = "staged inputs changed during the run"
    if "error" in result:
        result.update(status="ERROR", exit_code=2)
        write_reports(day, result)
        print(result["error"], file=sys.stderr)
        print(f"shadow report: {day / 'report.md'}")
        return 2

    metadata = json.loads((day / "case.json").read_text())
    report, rc = compare.compare_dirs(
        f"shadow {args.date}", day / "legacy", day / "candidate", metadata
    )
    (day / "compare.md").write_text(report)
    sections = {
        "fees": fees_section(day),
        "balances": balances_section(day),
        "ledger": ledger_section(day),
        "report_totals": report_totals_section(day),
    }
    if any(section["mismatches"] for section in sections.values()):
        rc = 1
    result["compare"] = {
        "verdict": report.strip().splitlines()[-1],
        **parse_verdict(report),
    }
    result["sections"] = sections
    result.update(status="FAIL" if rc else "PASS", exit_code=1 if rc else 0)
    write_reports(day, result)
    print((day / "report.md").read_text(), end="")
    print(f"shadow report: {day / 'report.md'}")
    return result["exit_code"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--case", help="committed fixture case to replay")
    source.add_argument("--input-dir", type=Path,
                        help="directory with ACCTDATA.PS, CARDXREF.PS, "
                             "DALYTRAN.PS")
    source.add_argument("--synthetic", type=int, metavar="COUNT",
                        help="generate a synthetic day of COUNT transactions")
    parser.add_argument("--seed", type=int,
                        help="synthetic seed (default: derived from --date)")
    parser.add_argument("--rules", type=Path,
                        help="CTL_XFER_PARM snapshot CSV; defaults to the "
                             "case's db2_before or the live table")
    parser.add_argument("--date", default=dt.date.today().isoformat(),
                        help="business date; names work/shadow/<date>/")
    parser.add_argument("--candidate", default="java",
                        choices=("java", "legacy", "naive", "cmd"))
    parser.add_argument("--candidate-cmd",
                        help="shell template; placeholders {day} {input} "
                             "{db2_before} {rules} {candidate} {date}")
    parser.add_argument("--legacy-dir", type=Path,
                        help="use pre-recorded legacy outputs instead of "
                             "running the COBOL chain")
    parser.add_argument("--out", type=Path, default=ROOT / "work" / "shadow")
    args = parser.parse_args()
    if not (args.case or args.input_dir or args.synthetic):
        parser.error("one of --case, --input-dir, --synthetic is required")
    if args.synthetic is not None and args.synthetic < 1:
        parser.error("--synthetic must be positive")
    if args.candidate == "cmd" and not args.candidate_cmd:
        parser.error("--candidate cmd requires --candidate-cmd")
    try:
        dt.date.fromisoformat(args.date)
    except ValueError:
        parser.error("--date must be YYYY-MM-DD")
    if args.seed is None:
        args.seed = int(args.date.replace("-", ""))
    args.out = args.out.resolve()
    return shadow(args)


if __name__ == "__main__":
    raise SystemExit(main())
