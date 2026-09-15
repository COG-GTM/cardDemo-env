#!/usr/bin/env python3
"""Generate the JCL, COBOL, data, SQL, and MQ relationship graph."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JCL_ROOT = ROOT / "jcl"
COBOL_ROOT = ROOT / "cobol"
COPYBOOK_ROOT = ROOT / "copybook"
CHAINS = ROOT / "tools" / "runjcl" / "chains.json"
OUTPUT = ROOT / "docs" / "chain-graph.md"


def node(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", "_", value)


def source_for(name: str) -> Path | None:
    wanted = name.upper()
    for path in COBOL_ROOT.iterdir():
        if path.is_file() and path.stem.upper() == wanted:
            return path
    return None


def copybook_for(name: str) -> Path | None:
    wanted = name.upper()
    for path in COPYBOOK_ROOT.iterdir():
        if path.is_file() and path.stem.upper() == wanted:
            return path
    return None


def copy_refs(path: Path) -> set[str]:
    refs = set()
    for line in path.read_text(errors="replace").splitlines():
        if line.lstrip().startswith("*"):
            continue
        refs.update(
            match.upper()
            for match in re.findall(r"\bCOPY\s+([A-Z0-9_$#@-]+)", line, re.IGNORECASE)
        )
    return refs


def nested_copies(name: str, seen: set[str] | None = None) -> set[str]:
    seen = seen or set()
    if name.upper() in seen:
        return seen
    seen.add(name.upper())
    path = copybook_for(name)
    if path:
        for ref in copy_refs(path):
            nested_copies(ref, seen)
    return seen


def sql_tables(path: Path) -> set[str]:
    text = path.read_text(errors="replace")
    values = set()
    blocks = re.findall(r"EXEC\s+SQL(.*?)END-EXEC", text, re.IGNORECASE | re.DOTALL)
    for block in blocks:
        for match in re.finditer(
            r"\b(?:FROM|INTO|UPDATE|DELETE\s+FROM)\s+([A-Z][A-Z0-9_$#@.]*)",
            block,
            re.IGNORECASE,
        ):
            value = match.group(1).upper()
            if not value.startswith((":", "WS-", "SQL-")):
                values.add(value)
    return values


def mq_queues(path: Path) -> set[str]:
    lines = path.read_text(errors="replace").splitlines()
    values = set()
    for index, line in enumerate(lines):
        if not re.search(r"MQOPEN|QUEUE|MQOD", line, re.IGNORECASE):
            continue
        context = " ".join(lines[max(0, index - 2):index + 3])
        values.update(
            match.upper()
            for match in re.findall(
                r"'([A-Z0-9_$#@-]+(?:\.[A-Z0-9_$#@-]+)+)'",
                context,
                re.IGNORECASE,
            )
        )
    return values


def job_name(path: Path) -> str:
    match = re.search(
        r"^//([\w$#@]+)\s+JOB\b",
        path.read_text(errors="replace"),
        re.IGNORECASE | re.MULTILINE,
    )
    return (match.group(1) if match else path.stem).upper()


def proc_path(name: str) -> Path | None:
    wanted = name.upper()
    for path in (JCL_ROOT / "proc").glob("*"):
        if path.is_file() and path.stem.upper() == wanted:
            return path
    return None


def exec_programs(path: Path) -> list[tuple[str, str, str | None]]:
    results = []
    current_step = None
    for line in path.read_text(errors="replace").splitlines():
        if line.startswith("//*"):
            continue
        step = re.match(r"^//([\w$#@]+)\s+EXEC\b", line, re.IGNORECASE)
        if step:
            current_step = step.group(1).upper()
            pgm = re.search(r"\bPGM=([\w$#@.-]+)", line, re.IGNORECASE)
            proc = re.search(r"\bPROC=([\w$#@.-]+)", line, re.IGNORECASE)
            if pgm:
                results.append((current_step, pgm.group(1).upper(), None))
            elif proc:
                proc_name = proc.group(1).upper()
                proc_member = proc_path(proc_name)
                results.append((current_step, proc_name, proc_name))
                if proc_member:
                    for _, program, _ in exec_programs(proc_member):
                        results.append((current_step, program, proc_name))
    return results


def dds(path: Path) -> list[tuple[str, str, str]]:
    results = []
    current_step = None
    current_dd = None
    body = ""
    lines = path.read_text(errors="replace").splitlines()

    def flush() -> None:
        nonlocal body, current_dd
        if current_step and current_dd:
            for dsn in re.findall(r"\bDSN=([^,\s]+)", body, re.IGNORECASE):
                results.append((current_step, current_dd, dsn.upper()))
        body = ""
        current_dd = None

    for line in lines:
        if line.startswith("//*"):
            continue
        step = re.match(r"^//([\w$#@]+)\s+EXEC\b", line, re.IGNORECASE)
        if step:
            flush()
            current_step = step.group(1).upper()
            continue
        match = re.match(
            r"^//(?:(\w+)\.)?([\w$#@]+)\s+DD\s+(.*)$",
            line,
            re.IGNORECASE,
        )
        if match:
            flush()
            qualified_step, current_dd, body = match.groups()
            if qualified_step:
                current_step = qualified_step.upper()
            current_dd = current_dd.upper()
            continue
        if current_dd and re.match(r"^//\s+", line):
            body += " " + line[2:].strip()
        else:
            flush()
    flush()
    return results


def job_edges(path: Path) -> set[tuple[str, str, str]]:
    job = job_name(path)
    edges: set[tuple[str, str, str]] = set()
    programs = exec_programs(path)
    for step, program, proc in programs:
        edges.add((job, "step", step))
        if proc:
            edges.add((step, "proc", proc))
            if program == proc:
                continue
        edges.add((step, "program", program))
        source = source_for(program)
        if source:
            for direct_copy in copy_refs(source):
                for copy in nested_copies(direct_copy):
                    edges.add((program, "copy", copy))
            for table in sql_tables(source):
                edges.add((program, "table", table))
            for queue in mq_queues(source):
                edges.add((program, "queue", queue))
    for step, ddname, dsn in dds(path):
        edges.add((job, "step", step))
        edges.add((step, "dd", ddname))
        edges.add((ddname, "dsn", dsn))
    return edges


def all_edges() -> dict[str, set[tuple[str, str, str]]]:
    paths = sorted(
        (path for path in JCL_ROOT.iterdir()
         if path.is_file() and path.suffix.lower() == ".jcl"),
        key=lambda item: item.name.upper(),
    )
    return {job_name(path): job_edges(path) for path in paths}


def mermaid(name: str, edges: set[tuple[str, str, str]]) -> list[str]:
    lines = [f"### {name}", "", "```mermaid", "graph TD"]
    for left, kind, right in sorted(edges):
        lines.append(f"    {node(left)}[{left}] -->|{kind}| {node(right)}[{right}]")
    lines.extend(["```", ""])
    return lines


def render() -> str:
    edge_map = all_edges()
    config = json.loads(CHAINS.read_text())
    chain_jobs = {
        job_name(ROOT / job)
        for chain in config.values()
        for job in chain.get("setup", []) + chain.get("jobs", [])
    }
    lines = ["# Chain graph", ""]
    for chain, definition in sorted(config.items()):
        jobs = {
            job_name(ROOT / job)
            for job in definition.get("setup", []) + definition.get("jobs", [])
        }
        chain_edges = set().union(*(edge_map.get(job, set()) for job in jobs))
        lines.extend(mermaid(chain, chain_edges))
    merged = set().union(*edge_map.values()) if edge_map else set()
    lines.extend(["## Edges", "", "| From | Relationship | To |", "|---|---|---|"])
    lines.extend(f"| {left} | {kind} | {right} |"
                 for left, kind, right in sorted(merged))
    shared = defaultdict(set)
    for job_edgeset in edge_map.values():
        for left, kind, right in job_edgeset:
            if kind == "copy":
                shared[right].add(left)
    lines.extend(["", "## Shared copybooks", "", "| Copybook | Programs |", "|---|---|"])
    for copy, programs in sorted(shared.items()):
        if len(programs) >= 2:
            lines.append(f"| {copy} | {', '.join(sorted(programs))} |")
    lines.extend(["", "## Jobs not in any chain", ""])
    lines.extend(f"- {job}" for job in sorted(set(edge_map) - chain_jobs))
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    rendered = render()
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text() != rendered:
            print(f"stale generated file: {OUTPUT}", file=sys.stderr)
            return 1
    else:
        OUTPUT.write_text(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
