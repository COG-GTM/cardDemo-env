#!/usr/bin/env python3
"""Generate deterministic SMF-shaped job activity for the JCL inventory."""

from __future__ import annotations

import csv
import random
import re
from datetime import datetime, timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
AS_OF = datetime(2026, 9, 15)
OUTPUT = ROOT / "ops" / "smf" / "job_activity.csv"
SEED = 20260915
RECENT_JOBS = {
    "POSTTRAN",
    "TRANBKP",
    "INTCALC",
    "COMBTRAN",
    "CREASTMT",
}
FIELDS = (
    "SMF_TYPE",
    "SUBTYPE",
    "SYSTEM_ID",
    "JOB_NAME",
    "JOB_ID",
    "USER_ID",
    "JOB_CLASS",
    "READER_START_TS",
    "LAST_RUN_TS",
    "COMPLETION_CODE",
    "CPU_SECONDS",
    "STEP_COUNT",
)


def jcl_members() -> list[Path]:
    return sorted(
        (path for path in (ROOT / "jcl").iterdir()
         if path.is_file() and path.suffix.lower() == ".jcl"),
        key=lambda path: path.name.upper(),
    )


def job_name(path: Path) -> str:
    for line in path.read_text(errors="replace").splitlines():
        match = re.match(r"^//([\w$#@]+)\s+JOB\b", line, re.IGNORECASE)
        if match:
            return match.group(1).upper()
    return path.stem.upper()


def step_count(path: Path) -> int:
    return len(re.findall(r"^//[\w$#@]+\s+EXEC\b", path.read_text(
        errors="replace"), re.IGNORECASE | re.MULTILINE))


def timestamp(value: datetime) -> str:
    return value.strftime("%Y-%m-%dT%H:%M:%S")


def activity_date(name: str, old: bool, rng: random.Random) -> datetime:
    if name == "XFRPURGE":
        return datetime(2019, 7, 19, 3, 14, 0)
    if old:
        return datetime(2018, 1, 1) + timedelta(
            days=rng.randrange((datetime(2023, 8, 1) - datetime(2018, 1, 1)).days)
        )
    return AS_OF - timedelta(days=rng.randrange(20, 900))


def main() -> None:
    paths = jcl_members()
    rng = random.Random(SEED)
    names = [job_name(path) for path in paths]
    recent = {
        index for index, name in enumerate(names)
        if name.startswith("XFR") or name in RECENT_JOBS
    }
    forced_old = {
        index for index, name in enumerate(names) if name == "XFRPURGE"
    }
    target_old = round(len(paths) * 0.5)
    old = set(forced_old)
    candidates = [
        index for index in range(len(paths))
        if index not in recent and index not in forced_old
    ]
    rng.shuffle(candidates)
    old.update(candidates[:max(0, target_old - len(old))])

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        for index, path in enumerate(paths, 1):
            name = names[index - 1]
            last_run = activity_date(name, index - 1 in old, rng)
            reader_start = last_run - timedelta(minutes=rng.randrange(1, 45))
            writer.writerow({
                "SMF_TYPE": "30",
                "SUBTYPE": "5",
                "SYSTEM_ID": "CARD",
                "JOB_NAME": name,
                "JOB_ID": f"JOB{index:05d}",
                "USER_ID": "CARDUSR",
                "JOB_CLASS": "A",
                "READER_START_TS": timestamp(reader_start),
                "LAST_RUN_TS": timestamp(last_run),
                "COMPLETION_CODE": "0000",
                "CPU_SECONDS": rng.randrange(1, 121),
                "STEP_COUNT": step_count(path),
            })


if __name__ == "__main__":
    main()
