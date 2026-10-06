"""Shadow-run the legacy error and encoding paths the synthetic day never takes.

Each edge day is derived from fixtures/xferfee/synthetic_day and run through
shadow_run.py (real COBOL leg vs Java). Exit status is the worst shadow exit.

Overlapping CTL_XFER_PARM rows are deliberately not an edge day: ocesql's SELECT INTO
takes whichever row PostgreSQL returns first (index vs seq scan), so the legacy
result is not deterministic (decision register, COG-1249).

    python3 tools/shadow/edge_days.py [--only plain,dup] [--out-root work/shadow-edge]
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "fixtures" / "xferfee" / "synthetic_day"
TRAN, XREF, ACCT = 350, 50, 300
OVERPUNCH = {c: str(i + 1) for i, c in enumerate("ABCDEFGHI")}


def records(name: str, size: int) -> list[bytearray]:
    data = (BASE / "input" / name).read_bytes()
    return [bytearray(data[i:i + size]) for i in range(0, len(data), size)]


def base_day() -> tuple[list[bytearray], list[bytearray], list[bytearray]]:
    return records("DALYTRAN.PS", TRAN), records("CARDXREF.PS", XREF), records("ACCTDATA.PS", ACCT)


def is_transfer(rec: bytearray) -> bool:
    return rec[16:18] == b"08"


def plain(tx, xr, ac):
    """Half the overpunched transfer amounts rewritten with a plain ASCII last digit."""
    hits = 0
    for rec in tx:
        last = chr(rec[142])
        if is_transfer(rec) and last in OVERPUNCH:
            hits += 1
            if hits % 2:
                rec[142] = ord(OVERPUNCH[last])
    return tx, xr, ac


def negative(tx, xr, ac):
    """Every balance 0.10, so posted source balances go negative with non-zero cents."""
    for rec in ac:
        rec[12:24] = b"00000000001{"
    return tx, xr, ac


def unmatched(tx, xr, ac):
    """One transfer card missing from CARDXREF: STEP010 RC 4."""
    card = min(bytes(rec[262:278]) for rec in tx if is_transfer(rec))
    return tx, [rec for rec in xr if bytes(rec[0:16]) != card], ac


def dup(tx, xr, ac):
    """A later transfer reuses an earlier TRAN-ID: ledger unique violation, STEP020 RC 8."""
    ids = [i for i, rec in enumerate(tx) if is_transfer(rec)]
    tx[ids[5]][0:16] = tx[ids[2]][0:16]
    return tx, xr, ac


def rules_without(book: str):
    def build(out: Path) -> Path:
        lines = (BASE / "db2_before" / "CTL_XFER_PARM.csv").read_text().splitlines()
        path = out / "CTL_XFER_PARM.csv"
        path.write_text("\n".join(line for line in lines if not line.startswith(book)) + "\n")
        return path
    return build


EDGES = {
    "plain": (plain, None),
    "negative": (negative, None),
    "unmatched": (unmatched, None),
    "dup": (dup, None),
    "norule": (None, rules_without("RETAIL")),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--only", help="comma-separated subset of " + ",".join(EDGES))
    parser.add_argument("--out-root", type=Path, default=ROOT / "work" / "shadow-edge")
    parser.add_argument("--date", default="2024-06-15")
    args = parser.parse_args()
    names = args.only.split(",") if args.only else list(EDGES)
    worst = 0
    for name in names:
        mutate, rules = EDGES[name]
        stage = args.out_root / "_inputs" / name
        if stage.exists():
            shutil.rmtree(stage)
        stage.mkdir(parents=True)
        tx, xr, ac = base_day()
        if mutate:
            tx, xr, ac = mutate(tx, xr, ac)
        for filename, recs in (("DALYTRAN.PS", tx), ("CARDXREF.PS", xr), ("ACCTDATA.PS", ac)):
            (stage / filename).write_bytes(b"".join(recs))
        rules_csv = rules(stage) if rules else BASE / "db2_before" / "CTL_XFER_PARM.csv"
        rc = subprocess.run([
            sys.executable, str(ROOT / "tools" / "shadow" / "shadow_run.py"),
            "--input-dir", str(stage), "--rules", str(rules_csv),
            "--date", args.date, "--out-root", str(args.out_root / name),
        ], cwd=ROOT, check=False).returncode
        print(f"EDGE {name}: {'PASS' if rc == 0 else f'FAIL (exit {rc})'}")
        worst = max(worst, rc)
    return worst


if __name__ == "__main__":
    raise SystemExit(main())
