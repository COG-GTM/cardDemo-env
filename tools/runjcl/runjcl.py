#!/usr/bin/env python3
"""Small, deliberately readable JCL runner for the CardDemo estate."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


@dataclass
class DD:
    name: str
    text: str
    inline: list[str] = field(default_factory=list)


@dataclass
class Step:
    name: str
    program: str
    cond: tuple[int, str, str | None] | None = None
    dd: dict[str, DD] = field(default_factory=dict)


def substitute(text: str, symbols: dict[str, str]) -> str:
    for key, value in symbols.items():
        text = text.replace(f"&{key}", value)
    return text


def parse_cond(text: str) -> tuple[int, str, str | None] | None:
    match = re.search(r"COND=\(\s*(\d+)\s*,\s*(\w+)(?:\s*,\s*([\w$#@]+))?", text)
    if not match:
        return None
    return int(match.group(1)), match.group(2).upper(), match.group(3)


def parse_dd(text: str, name: str, inline: list[str] | None = None) -> DD:
    return DD(name, text.strip(), inline or [])


def parse_steps(lines: list[str], symbols: dict[str, str]) -> list[Step]:
    steps: list[Step] = []
    current: Step | None = None
    i = 0
    while i < len(lines):
        raw = lines[i].rstrip("\n")
        line = substitute(raw, symbols)
        if line.startswith("//*") or not line.strip():
            i += 1
            continue
        step_match = re.match(r"^//([\w$#@]+)\s+EXEC\s+(.*)$", line)
        if step_match:
            name, body = step_match.groups()
            pgm = re.search(r"(?:^|,)\s*PGM=([\w$#@.-]+)", body)
            if pgm:
                program = pgm.group(1)
                current = Step(name, program, parse_cond(body))
                steps.append(current)
            i += 1
            continue
        if re.match(r"^//[\w$#@]+(?:\.[\w$#@]+)?\s+PROC\b", line):
            i += 1
            continue
        dd_match = re.match(r"^//([\w$#@]+)(?:\.([\w$#@]+))?\s+DD\s*(.*)$", line)
        if dd_match and current:
            step_name, dd_name, body = dd_match.groups()
            if dd_name and step_name != current.name:
                i += 1
                continue
            name = dd_name or step_name
            body = body.strip()
            if body.endswith(","):
                body = body[:-1]
            continuation: list[str] = []
            while i + 1 < len(lines) and re.match(
                r"^//\s+", substitute(lines[i + 1].rstrip("\n"), symbols)
            ):
                i += 1
                continuation.append(substitute(lines[i].rstrip("\n"), symbols)
                         .lstrip("/ ").strip())
            body = ",".join([body] + continuation)
            inline: list[str] = []
            if body.upper().startswith("*") or body.upper().startswith("DATA"):
                while i + 1 < len(lines):
                    candidate = lines[i + 1].rstrip("\n")
                    if candidate == "/*" or candidate.startswith("//"):
                        break
                    i += 1
                    inline.append(candidate)
                if i + 1 < len(lines) and lines[i + 1].rstrip("\n") == "/*":
                    i += 1
            current.dd[name.upper()] = parse_dd(body, name.upper(), inline)
        i += 1
    return steps


def load_steps(path: Path, symbols: dict[str, str]) -> list[Step]:
    lines = path.read_text().splitlines(True)
    proc = None
    proc_line = ""
    for line in lines:
        proc = re.search(r"EXEC\s+PROC=([\w$#@.-]+)", line)
        if proc:
            proc_line = line
            break
    if proc:
        proc_symbols = symbols.copy()
        for name, value in re.findall(
            r",\s*([A-Z][\w$#@]*)=([^,\s]+)", proc_line, re.IGNORECASE
        ):
            proc_symbols[name.upper()] = value
        proc_path = path.parent / "proc" / f"{proc.group(1)}.prc"
        steps = parse_steps(proc_path.read_text().splitlines(True), proc_symbols)
        overrides = parse_overrides(lines, proc_symbols)
        for step in steps:
            for name, dd in overrides.get(step.name.upper(), {}).items():
                step.dd[name] = dd
        return steps
    return parse_steps(lines, symbols)


def parse_overrides(lines: list[str], symbols: dict[str, str]) -> dict[str, dict[str, DD]]:
    result: dict[str, dict[str, DD]] = {}
    for line in lines:
        match = re.match(r"^//([\w$#@]+)\.([\w$#@]+)\s+DD\s+(.*)$",
                        substitute(line.rstrip("\n"), symbols))
        if match:
            step, name, body = match.groups()
            result.setdefault(step.upper(), {})[name.upper()] = parse_dd(body, name.upper())
    return result


def compare_condition(code: int, operator: str, actual: int) -> bool:
    return {
        "EQ": actual == code,
        "NE": actual != code,
        "GT": code > actual,
        "GE": code >= actual,
        "LT": code < actual,
        "LE": code <= actual,
    }.get(operator, False)


def dsn_parts(dsn: str) -> tuple[str, int | None]:
    match = re.match(r"^(.*)\(([+-]?\d+)\)$", dsn.strip())
    if not match:
        return dsn.strip(), None
    return match.group(1), int(match.group(2))


class Runner:
    def __init__(self, datasets: Path):
        self.datasets = datasets
        self.datasets.mkdir(parents=True, exist_ok=True)
        self.pending_gdg: dict[Path, int] = {}
        self.joblog_root = ROOT / "work" / "joblog"
        self.joblog_root.mkdir(parents=True, exist_ok=True)

    def gdg_file(self, base: Path) -> Path:
        return base.with_name(base.name + ".gdg")

    def gdg_current(self, base: Path) -> int:
        meta = self.gdg_file(base)
        if not meta.exists():
            return 0
        return int(json.loads(meta.read_text()).get("current", 0))

    def dataset_path(self, dsn: str, allocate: bool = False) -> tuple[Path, Path | None]:
        base_name, relative = dsn_parts(dsn)
        base = self.datasets / base_name
        if relative is None:
            return base, None
        current = self.pending_gdg.get(base, self.gdg_current(base))
        if relative == 0:
            generation = current
        elif relative == 1:
            generation = current + 1
            self.pending_gdg[base] = generation
        else:
            generation = current + relative
        if generation <= 0:
            return base, None
        return (self.datasets / f"{base_name}.G{generation:04d}V00"), base

    def catalog_pending(self) -> None:
        for base, generation in self.pending_gdg.items():
            self.gdg_file(base).write_text(json.dumps({"current": generation}) + "\n")
        self.pending_gdg.clear()

    def allocate_dd(self, dd: DD, job: str, step: str) -> tuple[dict[str, str], Path | None]:
        text = dd.text
        env_name = f"DD_{dd.name}"
        if text.upper().startswith("SYSOUT"):
            return {env_name: os.devnull}, None
        if text.upper().startswith("DUMMY"):
            return {env_name: os.devnull}, None
        dsn_match = re.search(r"(?:^|,)DSN=([^,\s]+)", text, re.IGNORECASE)
        if not dsn_match:
            if dd.inline:
                path = ROOT / "work" / "instream" / job / f"{step}.{dd.name}"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("\n".join(dd.inline) + "\n")
                return {env_name: str(path)}, None
            return {}, None
        path, base = self.dataset_path(dsn_match.group(1), allocate=True)
        path.parent.mkdir(parents=True, exist_ok=True)
        if "NEW" in text.upper() and path.exists():
            print(f"IEF2xxI WARNING: DISP=NEW truncates existing {dsn_match.group(1)}")
            path.write_bytes(b"")
        return {env_name: str(path)}, base

    def utility(self, step: Step, allocations: dict[str, str]) -> int:
        if step.program.upper() == "IEFBR14":
            for dd in step.dd.values():
                if "DELETE" in dd.text.upper():
                    path = allocations.get(f"DD_{dd.name}")
                    if path and path != os.devnull:
                        Path(path).unlink(missing_ok=True)
            return 0
        if step.program.upper() != "IDCAMS":
            return -1
        sysin = allocations.get("DD_SYSIN")
        if not sysin:
            return 0
        text = Path(sysin).read_text()
        for match in re.finditer(
            r"DEFINE\s+GENERATIONDATAGROUP.*?NAME\(([^)]+)\)", text,
            re.IGNORECASE | re.DOTALL,
        ):
            base = self.datasets / match.group(1).strip()
            base.parent.mkdir(parents=True, exist_ok=True)
            if not self.gdg_file(base).exists():
                self.gdg_file(base).write_text('{"current": 0}\n')
        for match in re.finditer(r"REPRO\s+INFILE\((\w+)\)\s+OUTFILE\((\w+)\)",
                                 text, re.IGNORECASE):
            source = allocations.get(f"DD_{match.group(1).upper()}")
            target = allocations.get(f"DD_{match.group(2).upper()}")
            if source and target:
                shutil.copyfile(source, target)
        for match in re.finditer(r"DELETE\s+([A-Z0-9.$#@+-]+)", text, re.IGNORECASE):
            path, _ = self.dataset_path(match.group(1), allocate=False)
            path.unlink(missing_ok=True)
        return 0

    def run_job(self, path: Path, symbols: dict[str, str] | None = None) -> int:
        symbols = symbols or {}
        job_match = next((re.match(r"^//([\w$#@]+)\s+JOB\b", line)
                          for line in path.read_text().splitlines()), None)
        job = job_match.group(1) if job_match else path.stem.upper()
        steps = load_steps(path, symbols)
        log_dir = self.joblog_root / job
        log_dir.mkdir(parents=True, exist_ok=True)
        log_lines: list[str] = []

        def emit(message: str) -> None:
            print(message)
            log_lines.append(message)

        results: dict[str, int] = {}
        maxcc = 0
        for step in steps:
            skip = False
            if step.cond:
                threshold, operator, prior = step.cond
                candidates = [results[prior]] if prior and prior in results else list(results.values())
                skip = any(compare_condition(threshold, operator, code)
                           for code in candidates)
            emit(f"IEF236I ALLOC. FOR {job} {step.name}")
            if skip:
                emit(f"IEF202I {job} {step.name} - STEP WAS NOT RUN "
                     "BECAUSE OF CONDITION CODES")
                results[step.name] = 0
                continue
            environment = os.environ.copy()
            allocated_bases: list[Path] = []
            for dd in step.dd.values():
                mapping, base = self.allocate_dd(dd, job, step.name)
                environment.update(mapping)
                if base:
                    allocated_bases.append(base)
                if mapping:
                    emit(f"IEF237I JES2 ALLOCATED TO {dd.name}")
            utility_rc = self.utility(step, environment)
            if utility_rc >= 0:
                rc = utility_rc
                output = ""
            else:
                process = subprocess.run(
                    ["cobcrun", step.program],
                    cwd=ROOT,
                    env=environment,
                    capture_output=True,
                    text=True,
                )
                output = process.stdout + process.stderr
                rc = process.returncode
            sysout = log_dir / f"{step.name}.SYSOUT"
            sysout.write_text(output)
            if output:
                for line in output.rstrip().splitlines():
                    emit(line)
            if rc < 0:
                emit(f"IEF202I {job} {step.name} - ABEND S0C7")
                rc = 12
            else:
                emit(f"IEF142I {job} {step.name} - STEP WAS EXECUTED - "
                     f"COND CODE {rc:04d}")
            results[step.name] = rc
            maxcc = max(maxcc, rc)
            if rc != 0:
                break
            self.catalog_pending()
            for base in allocated_bases:
                current = self.gdg_current(base)
                generation = current
                if generation:
                    emit(f"IEF285I   {base.name}.G{generation:04d}V00   CATALOGED")
        emit(f"$HASP395 {job} ENDED - MAXCC={maxcc:04d}")
        (self.joblog_root / f"{job}.log").write_text("\n".join(log_lines) + "\n")
        return maxcc


def load_fixtures(case: str, datasets: Path) -> None:
    generator = ROOT / "tools" / "fixtures" / "gen_fixtures.py"
    subprocess.run([sys.executable, str(generator), "--case", case],
                   cwd=ROOT, check=True)
    source = ROOT / "fixtures" / "xferfee" / case / "input"
    mapping = {
        "ACCTDATA.PS": "AWS.M2.CARDDEMO.ACCTDATA.PS",
        "CARDXREF.PS": "AWS.M2.CARDDEMO.CARDXREF.PS",
        "DALYTRAN.PS": "AWS.M2.CARDDEMO.DALYTRAN.PS",
    }
    datasets.mkdir(parents=True, exist_ok=True)
    for filename, dsn in mapping.items():
        shutil.copyfile(source / filename, datasets / dsn)


def reset_db() -> None:
    subprocess.run(
        ["psql", "-v", "ON_ERROR_STOP=1", "-c",
         "TRUNCATE TABLE XFER_FEE_LEDGER"],
        cwd=ROOT, check=True,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chain")
    parser.add_argument("--job")
    parser.add_argument("--datasets", type=Path, default=ROOT / "datasets")
    parser.add_argument("--load-fixtures")
    parser.add_argument("--db-reset", action="store_true")
    args = parser.parse_args()
    datasets = args.datasets if args.datasets.is_absolute() else ROOT / args.datasets
    if args.load_fixtures:
        load_fixtures(args.load_fixtures, datasets)
    if args.db_reset:
        reset_db()
    runner = Runner(datasets)
    jobs: list[Path] = []
    if args.chain:
        config = json.loads((ROOT / "tools" / "runjcl" / "chains.json").read_text())
        selected = config[args.chain]
        for setup in selected.get("setup", []):
            rc = runner.run_job(ROOT / setup)
            if rc:
                return rc
        jobs = [ROOT / job for job in selected["jobs"]]
    elif args.job:
        jobs = [Path(args.job)]
        if not jobs[0].is_absolute():
            jobs[0] = ROOT / jobs[0]
    else:
        parser.error("one of --chain or --job is required")
    rc = 0
    for job in jobs:
        rc = runner.run_job(job)
        if rc:
            break
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
