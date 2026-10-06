#!/usr/bin/env python3
"""Shadow-run (migration phase 3): legacy COBOL vs Java on the same daily input.

Stages one day's DALYTRAN.PS + CARDXREF.PS + ACCTDATA.PS and a CTL_XFER_PARM
rule snapshot under ``work/shadow/<date>/``, runs the XFRDAILY chain in the
estate container and java/shadow-run on the identical files, diffs the two
output trees with ``tools/parity/compare.py`` and writes ``report.md`` and
``report.json`` (per-transfer fee, balances, ledger, report totals).

Exit status: 0 = no differences, 1 = differences, 2 = a leg did not finish.

    shadow_run.py --synthetic 250 --date 2024-06-15   # generated day
    shadow_run.py --case default                      # recorded fixture inputs
    shadow_run.py --input-dir DIR --rules CTL.csv     # a real day
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
from decimal import Decimal
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "parity"))
sys.path.insert(0, str(ROOT / "tools" / "fixtures"))

from compare import CHAIN_ROOT, compare_dirs, dataset_file  # noqa: E402
from copybook import decode_record, parse_copybook, record_length  # noqa: E402
import gen_fixtures  # noqa: E402
import java_candidate  # noqa: E402

INPUTS = ("DALYTRAN.PS", "CARDXREF.PS", "ACCTDATA.PS")
EXTRACT = "AWS.M2.CARDDEMO.XFER.EXTRACT"
FEES = "AWS.M2.CARDDEMO.XFER.FEES"
ACCOUNTS = "AWS.M2.CARDDEMO.ACCTDATA.XFER"
RECON = "AWS.M2.CARDDEMO.XFER.RECON.RPT"
LEDGER_HEADER = "tran_id,tran_dt,src_acct_id,tgt_acct_id,book_id,tran_amt,fee_amt,cap_applied\n"
TOTAL = re.compile(r"GRAND TOTAL COUNT\s+(\d+) AMOUNT\s+(-?[\d.]+)\s+FEE\s+(-?[\d.]+)")
SUBTOTAL = re.compile(r"BOOK (\S+)\s+SUBTOTAL AMOUNT\s+(-?[\d.]+)\s+FEE\s+(-?[\d.]+)")
MD_ROWS = 50


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def records(path: Path | None, copybook: str) -> list[dict[str, Any]]:
    if path is None:
        return []
    size = record_length(parse_copybook(copybook))
    data = path.read_bytes()
    return [decode_record(copybook, data[i:i + size]) for i in range(0, len(data), size)]


def money(value: Any) -> str | None:
    return None if value is None else format(Decimal(value), ".2f")


# ---------------------------------------------------------------- staging

def stage(args: argparse.Namespace, day: Path) -> dict[str, Any]:
    staged = day / "input"
    snapshot = day / "snapshot"
    staged.mkdir(parents=True)
    snapshot.mkdir()
    rules = args.rules
    ledger = args.ledger
    if args.case:
        source = CHAIN_ROOT / args.case
        for name in INPUTS:
            shutil.copyfile(source / "input" / name, staged / name)
        rules = rules or source / "db2_before" / "CTL_XFER_PARM.csv"
        ledger = ledger or source / "db2_before" / "XFER_FEE_LEDGER.csv"
        origin = f"fixture case `{args.case}`"
    elif args.input_dir:
        for name in INPUTS:
            shutil.copyfile(args.input_dir / name, staged / name)
        origin = f"input dir `{args.input_dir}`"
    else:
        gen_fixtures.write_day(
            staged, *gen_fixtures.synthetic_day(args.date, args.synthetic, args.seed)
        )
        origin = f"synthetic day ({args.synthetic} transactions, seed {args.seed})"
    if rules:
        shutil.copyfile(rules, snapshot / "CTL_XFER_PARM.csv")
    else:
        estate(args, "--dump-rules", rel(snapshot / "CTL_XFER_PARM.csv"))
        origin_rules = "live CTL_XFER_PARM (estate DB)"
    if ledger:
        shutil.copyfile(ledger, snapshot / "XFER_FEE_LEDGER.csv")
    else:
        (snapshot / "XFER_FEE_LEDGER.csv").write_text(LEDGER_HEADER)
    return {
        "origin": origin,
        "rules_origin": str(rules) if rules else origin_rules,
        "files": {
            path.name: {"bytes": path.stat().st_size, "sha256": sha256(path)}
            for path in [*(staged / n for n in INPUTS), *sorted(snapshot.iterdir())]
        },
    }


def rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        sys.exit(f"{path} must be inside the repo (mounted at /estate in the container)")


def estate(args: argparse.Namespace, *recorder_args: str) -> int:
    command = [
        *shlex.split(args.compose), "exec", "-T", "estate", "python3",
        "tools/parity/recorder.py", "--chain", "xferfee", *recorder_args,
    ]
    print("+", " ".join(command), flush=True)
    return subprocess.run(command, cwd=ROOT).returncode


# ---------------------------------------------------------------- legs

def run_legacy(args: argparse.Namespace, day: Path) -> bool:
    out = day / "legacy"
    if args.legacy_dir:
        shutil.copytree(args.legacy_dir, out)
    else:
        estate(
            args, "--input-dir", rel(day / "input"),
            "--rules", rel(day / "snapshot" / "CTL_XFER_PARM.csv"),
            "--ledger", rel(day / "snapshot" / "XFER_FEE_LEDGER.csv"),
            "--out", rel(out),
        )
    return (out / "rc.json").exists()


def run_java(args: argparse.Namespace, day: Path) -> bool:
    java_candidate.build_jar(args.build)
    rc = java_candidate.run_java(
        [
            "--input-dir", str(day / "input"),
            "--rules", str(day / "snapshot" / "CTL_XFER_PARM.csv"),
            "--ledger", str(day / "snapshot" / "XFER_FEE_LEDGER.csv"),
        ],
        day / "java",
    )
    return rc is not None


# ---------------------------------------------------------------- report

def side(root: Path) -> dict[str, Any]:
    rc = json.loads((root / "rc.json").read_text())
    report = dataset_file(root / "datasets", RECON)
    lines = report.read_text().splitlines() if report else []
    total = next((TOTAL.search(line) for line in lines if TOTAL.search(line)), None)
    ledger_file = root / "db2_after" / "XFER_FEE_LEDGER.csv"
    ledger = ledger_file.read_text().splitlines()[1:] if ledger_file.exists() else []
    return {
        "rc": rc,
        "extract": records(dataset_file(root / "datasets", EXTRACT), "CVXFR01Y"),
        "fees": {r["XFE-TRAN-ID"]: r for r in records(dataset_file(root / "datasets", FEES), "CVXFR02Y")},
        "accounts": {r["ACCT-ID"]: r for r in records(dataset_file(root / "datasets", ACCOUNTS), "CVACT01Y")},
        "subtotals": [m.groups() for m in map(SUBTOTAL.search, lines) if m],
        "total": total.groups() if total else None,
        "ledger": sorted(ledger),
    }


def transfers(legacy: dict, java: dict) -> list[dict[str, Any]]:
    rows = []
    for tran_id in dict.fromkeys([*legacy["fees"], *java["fees"]]):
        cobol, jv = legacy["fees"].get(tran_id), java["fees"].get(tran_id)
        base = cobol or jv
        row = {
            "tran_id": tran_id,
            "tran_dt": base["XFE-TRAN-DT"],
            "book": base["XFE-BOOK-ID"],
            "src": int(base["XFE-SRC-ACCT-ID"]),
            "tgt": int(base["XFE-TGT-ACCT-ID"]),
        }
        for label, rec in (("cobol", cobol), ("java", jv)):
            row[label] = None if rec is None else {
                "amount": money(rec["XFE-TRAN-AMT"]),
                "fee_pct": format(rec["XFE-FEE-PCT"], "f"),
                "fee": money(rec["XFE-FEE-AMT"]),
                "cap_applied": rec["XFE-CAP-APPLIED"],
                "rule_eff_dt": rec["XFE-RULE-EFF-DT"],
            }
        row["match"] = row["cobol"] == row["java"]
        rows.append(row)
    return rows


def balances(legacy: dict, java: dict, opening: dict) -> list[dict[str, Any]]:
    rows = []
    for acct in sorted({*legacy["accounts"], *java["accounts"]}):
        row: dict[str, Any] = {"acct_id": int(acct)}
        before = opening.get(acct)
        row["opening_bal"] = money(before["ACCT-CURR-BAL"]) if before else None
        for label, data in (("cobol", legacy), ("java", java)):
            rec = data["accounts"].get(acct)
            row[label] = None if rec is None else {
                "bal": money(rec["ACCT-CURR-BAL"]),
                "cyc_credit": money(rec["ACCT-CURR-CYC-CREDIT"]),
                "cyc_debit": money(rec["ACCT-CURR-CYC-DEBIT"]),
            }
        row["changed"] = row["cobol"] is not None and row["cobol"]["bal"] != row["opening_bal"]
        row["match"] = row["cobol"] == row["java"]
        rows.append(row)
    return rows


def ledger_summary(rows: list[str]) -> dict[str, Any]:
    parsed = [line.split(",") for line in rows]
    return {
        "rows": len(parsed),
        "amount": money(sum((Decimal(r[5]) for r in parsed), Decimal(0))),
        "fee": money(sum((Decimal(r[6]) for r in parsed), Decimal(0))),
        "capped": sum(1 for r in parsed if r[7] == "Y"),
    }


def build_report(day: Path, date: str, staged: dict, verdict: dict) -> dict[str, Any]:
    legacy, java = side(day / "legacy"), side(day / "java")
    opening = {
        r["ACCT-ID"]: r for r in records(day / "input" / "ACCTDATA.PS", "CVACT01Y")
    }
    ledger_only = sorted(set(legacy["ledger"]) ^ set(java["ledger"]))
    return {
        "date": date,
        "verdict": verdict,
        "inputs": staged,
        "rc": {"cobol": legacy["rc"], "java": java["rc"]},
        "report_totals": {
            "cobol": {"total": legacy["total"], "subtotals": legacy["subtotals"]},
            "java": {"total": java["total"], "subtotals": java["subtotals"]},
            "match": legacy["total"] == java["total"] and legacy["subtotals"] == java["subtotals"],
        },
        "ledger": {
            "cobol": ledger_summary(legacy["ledger"]),
            "java": ledger_summary(java["ledger"]),
            "rows_only_on_one_side": ledger_only,
        },
        "extract": {"cobol": len(legacy["extract"]), "java": len(java["extract"])},
        "transfers": transfers(legacy, java),
        "balances": balances(legacy, java, opening),
    }


def table(header: list[str], rows: list[list[Any]]) -> list[str]:
    out = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    out += ["| " + " | ".join("" if v is None else str(v) for v in row) + " |" for row in rows]
    return out


def capped(rows: list[dict], key: str = "match") -> tuple[list[dict], int]:
    """Every mismatch plus the first matches, up to MD_ROWS."""
    bad = [r for r in rows if not r[key]]
    good = [r for r in rows if r[key]]
    shown = bad + good[: max(0, MD_ROWS - len(bad))]
    return shown, len(rows) - len(shown)


def markdown(report: dict[str, Any], compare_md: str) -> str:
    v = report["verdict"]
    status = "PASS - 0 differences" if v["rc"] == 0 else (
        f"FAIL - {v['field_diffs']} field / {v['record_diffs']} record differences"
        if v["rc"] == 1 else "ERROR - a leg did not finish")
    lines = [f"# Shadow run {report['date']}: {status}", ""]
    lines += [f"Input: {report['inputs']['origin']}; rules: {report['inputs']['rules_origin']}.", ""]
    lines += table(["File", "Bytes", "SHA-256"], [
        [name, info["bytes"], f"`{info['sha256'][:16]}`"]
        for name, info in report["inputs"]["files"].items()])
    rc = report["rc"]
    lines += ["", "## Step return codes", ""]
    lines += table(["Step", "COBOL", "Java"], [
        [step, rc["cobol"]["steps"].get(step), rc["java"]["steps"].get(step)]
        for step in dict.fromkeys([*rc["cobol"]["steps"], *rc["java"]["steps"]])
    ] + [["MAXCC", rc["cobol"]["maxcc"], rc["java"]["maxcc"]]])
    totals = report["report_totals"]
    lines += ["", f"## Reconciliation report totals ({'match' if totals['match'] else 'MISMATCH'})", ""]
    rows = [["GRAND TOTAL", *(totals[s]["total"] or ("-", "-", "-") for s in ("cobol", "java"))]]
    lines += table(["", "COBOL count / amount / fee", "Java count / amount / fee"],
                   [[r[0], " / ".join(r[1]), " / ".join(r[2])] for r in rows])
    lines += ["", f"Book subtotal lines: COBOL {len(totals['cobol']['subtotals'])}, "
              f"Java {len(totals['java']['subtotals'])}."]
    led = report["ledger"]
    lines += ["", "## XFER_FEE_LEDGER after run", ""]
    lines += table(["", "Rows", "Sum amount", "Sum fee", "Capped"], [
        [s, led[s]["rows"], led[s]["amount"], led[s]["fee"], led[s]["capped"]] for s in ("cobol", "java")])
    lines += ["", f"Rows on one side only: {len(led['rows_only_on_one_side'])}."]
    xfers = report["transfers"]
    shown, more = capped(xfers)
    lines += ["", f"## Per-transfer fees ({len(xfers)} posted, "
              f"{sum(not r['match'] for r in xfers)} mismatched)", ""]
    def fee_cell(x: dict | None) -> str:
        return "-" if x is None else f"{x['amount']} -> {x['fee']}{' (cap)' if x['cap_applied'] == 'Y' else ''}"
    lines += table(["Tran", "Date", "Book", "Src -> Tgt", "COBOL amount -> fee", "Java amount -> fee", "Match"], [
        [r["tran_id"], r["tran_dt"], r["book"], f"{r['src']} -> {r['tgt']}",
         fee_cell(r["cobol"]), fee_cell(r["java"]), "yes" if r["match"] else "**NO**"] for r in shown])
    if more:
        lines.append(f"\n... {more} more matching transfers in report.json")
    accts = [r for r in report["balances"] if r["changed"] or not r["match"]]
    shown, more = capped(accts)
    lines += ["", f"## Balances ({len(accts)} accounts posted to, "
              f"{sum(not r['match'] for r in report['balances'])} mismatched)", ""]
    def bal_cell(x: dict | None) -> str:
        return "-" if x is None else f"{x['bal']} (cr {x['cyc_credit']} / dr {x['cyc_debit']})"
    lines += table(["Account", "Opening", "COBOL closing", "Java closing", "Match"], [
        [r["acct_id"], r["opening_bal"], bal_cell(r["cobol"]), bal_cell(r["java"]),
         "yes" if r["match"] else "**NO**"] for r in shown])
    if more:
        lines.append(f"\n... {more} more matching accounts in report.json")
    lines += ["", "## compare.py (COBOL = expected, Java = candidate)", ""]
    detail = compare_md.splitlines()[1:]
    if len(detail) > 200:
        detail = detail[:200] + ["... truncated, full report in compare.md"]
    lines += detail
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- main

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--synthetic", type=int, metavar="N", default=250,
                        help="generate a synthetic day of N transactions (default)")
    source.add_argument("--case", help="use a recorded fixture case's inputs and db2_before")
    source.add_argument("--input-dir", type=Path, help="dir with DALYTRAN.PS, CARDXREF.PS, ACCTDATA.PS")
    parser.add_argument("--date", default=dt.datetime.now(dt.timezone.utc).date().isoformat(),
                        help="business date: report dir name and synthetic transaction date")
    parser.add_argument("--seed", type=int, default=1242)
    parser.add_argument("--rules", type=Path, help="CTL_XFER_PARM CSV snapshot "
                        "(default: case db2_before, else the live estate table)")
    parser.add_argument("--ledger", type=Path, help="XFER_FEE_LEDGER rows before the run (default: empty)")
    parser.add_argument("--out-root", type=Path, default=ROOT / "work" / "shadow")
    parser.add_argument("--legacy-dir", type=Path,
                        help="use pre-recorded COBOL outputs instead of running the estate")
    parser.add_argument("--compose", default=os.environ.get("COMPOSE", "docker compose"))
    parser.add_argument("--build", action="store_true", help="rebuild java/shadow-run first")
    args = parser.parse_args()
    dt.date.fromisoformat(args.date)

    day = args.out_root / args.date
    if day.exists():
        shutil.rmtree(day)
    staged = stage(args, day)
    legacy_ok = run_legacy(args, day)
    java_ok = run_java(args, day)
    if not (legacy_ok and java_ok):
        verdict = {"rc": 2, "field_diffs": None, "record_diffs": None,
                   "legacy_finished": legacy_ok, "java_finished": java_ok}
        (day / "report.json").write_text(json.dumps({"date": args.date, "verdict": verdict,
                                                     "inputs": staged}, indent=2) + "\n")
        print(f"SHADOW: ERROR (legacy finished: {legacy_ok}, java finished: {java_ok})")
        return 2

    metadata = json.loads((CHAIN_ROOT / "default" / "case.json").read_text())
    metadata.update(description=f"Shadow run {args.date}", run_date=args.date)
    (day / "case.json").write_text(json.dumps(metadata, indent=2) + "\n")
    compare_md, rc, fields, recs = compare_dirs(
        f"shadow {args.date}", metadata, day / "legacy", day / "java", day / "compare.md")
    verdict = {"rc": rc, "field_diffs": fields, "record_diffs": recs}
    report = build_report(day, args.date, staged, verdict)
    (day / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    (day / "report.md").write_text(markdown(report, compare_md))
    xfers = report["transfers"]
    print(f"SHADOW {args.date}: {'PASS' if rc == 0 else 'FAIL'} "
          f"({fields} field / {recs} record differences; {len(xfers)} transfers, "
          f"{sum(not r['match'] for r in xfers)} mismatched) -> {day / 'report.md'}")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
