#!/usr/bin/env python3
"""Generate the JCL, COBOL, data, SQL, and MQ relationship graph."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.runjcl.runjcl import parse_overrides, parse_steps


JCL_ROOT = ROOT / "jcl"
COBOL_ROOT = ROOT / "cobol"
COPYBOOK_ROOT = ROOT / "copybook"
CHAINS = ROOT / "tools" / "runjcl" / "chains.json"
OUTPUT = ROOT / "docs" / "chain-graph.md"


KIND_PREFIX = {
    "job": "JOB",
    "step": "STEP",
    "proc": "PROC",
    "program": "PGM",
    "dd": "DD",
    "copybook": "CPY",
    "dsn": "DSN",
    "table": "TBL",
    "queue": "MQ",
}


@dataclass(frozen=True)
class GraphNode:
    kind: str
    name: str

    @property
    def identity(self) -> str:
        if self.kind in {"step", "dd"}:
            return self.name
        return f"{KIND_PREFIX[self.kind]}:{self.name}"

    @property
    def mermaid_id(self) -> str:
        return re.sub(
            r"[^A-Za-z0-9_]",
            "_",
            f"{KIND_PREFIX[self.kind]}_{self.name}",
        )


Edge = tuple[GraphNode, str, GraphNode]


def graph_node(kind: str, name: str) -> GraphNode:
    return GraphNode(kind, name.upper())


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


def proc_invocations(path: Path) -> list[tuple[str, str, str]]:
    invocations = []
    for line in path.read_text(errors="replace").splitlines():
        match = re.match(
            r"^//([\w$#@]+)\s+EXEC\s+PROC=([\w$#@.-]+)(.*)$",
            line,
            re.IGNORECASE,
        )
        if match:
            invocations.append(
                (match.group(1).upper(), match.group(2).upper(), match.group(3))
            )
    return invocations


def proc_steps(path: Path, invocation: tuple[str, str, str]) -> list:
    _, proc_name, suffix = invocation
    proc_member = proc_path(proc_name)
    if proc_member is None:
        return []
    symbols = {
        name.upper(): value
        for name, value in re.findall(
            r",\s*([A-Z][\w$#@]*)=([^,\s]+)", suffix, re.IGNORECASE
        )
    }
    proc_symbols = {}
    declaration = proc_member.read_text(errors="replace").splitlines()
    if declaration:
        proc_match = re.search(r"\bPROC\b(.*)$", declaration[0], re.IGNORECASE)
        if proc_match:
            proc_symbols.update({
                name.upper(): value
                for name, value in re.findall(
                    r"([A-Z][\w$#@]*)=([^,\s]+)",
                    proc_match.group(1),
                    re.IGNORECASE,
                )
            })
    proc_symbols.update(symbols)
    steps = parse_steps(proc_member.read_text().splitlines(True), proc_symbols)
    overrides = parse_overrides(path.read_text().splitlines(True), proc_symbols)
    for step in steps:
        for name, dd in overrides.get(step.name.upper(), {}).items():
            step.dd[name] = dd
    return steps


def job_steps(path: Path) -> list[tuple[str, object, str | None]]:
    lines = path.read_text().splitlines(True)
    direct = [
        (step.name.upper(), step, None)
        for step in parse_steps(lines, {})
    ]
    expanded = []
    for invocation in proc_invocations(path):
        invocation_name, _, _ = invocation
        expanded.extend(
            (f"{invocation_name}.{step.name.upper()}", step, invocation_name)
            for step in proc_steps(path, invocation)
        )
    return direct + expanded


def program_edges(program: str, source: Path | None) -> set[Edge]:
    if source is None:
        return set()
    program_node = graph_node("program", program)
    edges: set[Edge] = set()
    for direct_copy in copy_refs(source):
        for copy in nested_copies(direct_copy):
            edges.add((program_node, "copy", graph_node("copybook", copy)))
    for table in sql_tables(source):
        edges.add((program_node, "table", graph_node("table", table)))
    for queue in mq_queues(source):
        edges.add((program_node, "queue", graph_node("queue", queue)))
    return edges


def dd_dsn(text: str) -> str | None:
    match = re.search(r"\bDSN=([^,\s]+)", text, re.IGNORECASE)
    return match.group(1).upper() if match else None


def job_edges(path: Path) -> set[Edge]:
    job_name_value = job_name(path)
    job_node = graph_node("job", job_name_value)
    edges: set[Edge] = set()
    invocations = {
        name: proc
        for name, proc, _ in proc_invocations(path)
    }
    for step_name, step, invocation_name in job_steps(path):
        if invocation_name:
            outer_step = graph_node(
                "step",
                f"{job_name_value}.{invocation_name}",
            )
            step_node = graph_node(
                "step",
                f"{job_name_value}.{step_name}",
            )
            edges.add((job_node, "step", outer_step))
            edges.add((
                outer_step,
                "proc",
                graph_node("proc", invocations[invocation_name]),
            ))
            edges.add((outer_step, "step", step_node))
        else:
            step_node = graph_node("step", f"{job_name_value}.{step_name}")
            edges.add((job_node, "step", step_node))
        program_node = graph_node("program", step.program)
        edges.add((step_node, "program", program_node))
        edges.update(program_edges(step.program, source_for(step.program)))
        for dd_name, dd in step.dd.items():
            dd_node = graph_node(
                "dd",
                f"{job_name_value}.{step_name}/{dd_name}",
            )
            edges.add((program_node, "dd", dd_node))
            dsn = dd_dsn(dd.text)
            if dsn:
                edges.add((dd_node, "dsn", graph_node("dsn", dsn)))
    return edges


def all_edges() -> dict[str, set[Edge]]:
    paths = sorted(
        (path for path in JCL_ROOT.iterdir()
         if path.is_file() and path.suffix.lower() == ".jcl"),
        key=lambda item: item.name.upper(),
    )
    return {job_name(path): job_edges(path) for path in paths}


def edge_sort_key(edge: Edge) -> tuple[str, str, str, str, str]:
    left, relationship, right = edge
    return (
        left.kind,
        left.identity,
        relationship,
        right.kind,
        right.identity,
    )


def mermaid(name: str, edges: set[Edge]) -> list[str]:
    nodes = {node for left, _, right in edges for node in (left, right)}
    lines = [f"### {name}", "", "```mermaid", "graph TD"]
    for kind, color in (
        ("job", "#b3d9ff"),
        ("step", "#d9ead3"),
        ("proc", "#fce5cd"),
        ("program", "#eadcf8"),
        ("dd", "#fff2cc"),
        ("copybook", "#d0e0e3"),
        ("dsn", "#f4cccc"),
        ("table", "#c9daf8"),
        ("queue", "#d9d2e9"),
    ):
        lines.append(f"    classDef {kind} fill:{color},stroke:#444")
    for graph_node_value in sorted(nodes, key=lambda item: (item.kind, item.identity)):
        label = graph_node_value.identity.replace('"', "&quot;")
        lines.append(
            f'    {graph_node_value.mermaid_id}["{label}"]'
        )
    for left, relationship, right in sorted(edges, key=edge_sort_key):
        lines.append(
            f"    {left.mermaid_id} -->|{relationship}| {right.mermaid_id}"
        )
    for kind in KIND_PREFIX:
        ids = sorted(
            graph_node_value.mermaid_id
            for graph_node_value in nodes
            if graph_node_value.kind == kind
        )
        if ids:
            lines.append(f"    class {','.join(ids)} {kind}")
    lines.extend(["```", ""])
    return lines


def edge_table(edges: set[Edge]) -> list[str]:
    return [
        f"| {left.identity} | {relationship} | {right.identity} |"
        for left, relationship, right in sorted(edges, key=edge_sort_key)
    ]


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
        lines.extend([
            f"## Edges — {chain}",
            "",
            "| From | Relationship | To |",
            "|---|---|---|",
        ])
        lines.extend(edge_table(chain_edges))
        lines.append("")
    merged = set().union(*edge_map.values()) if edge_map else set()
    lines.extend([
        "## Edges — All jobs",
        "",
        "| From | Relationship | To |",
        "|---|---|---|",
    ])
    lines.extend(edge_table(merged))
    shared = defaultdict(set)
    for job_edgeset in edge_map.values():
        for left, relationship, right in job_edgeset:
            if relationship == "copy":
                shared[right.identity].add(left.identity)
    lines.extend([
        "",
        "## Shared copybooks",
        "",
        "| Copybook | Programs |",
        "|---|---|",
    ])
    for copy, programs in sorted(shared.items()):
        if len(programs) >= 2:
            lines.append(f"| {copy} | {', '.join(sorted(programs))} |")
    lines.extend(["", "## Jobs not in any chain", ""])
    lines.extend(
        f"- {graph_node('job', job).identity}"
        for job in sorted(set(edge_map) - chain_jobs)
    )
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
