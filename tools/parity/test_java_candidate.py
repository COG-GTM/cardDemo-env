#!/usr/bin/env python3
"""Round-trip checks: fixture datasets -> JSON-lines -> java_candidate encode -> identical bytes."""

from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path

import java_candidate
from compare import CASES, CHAIN_ROOT, compare_case


class JavaCandidateRoundTrip(unittest.TestCase):

    def setUp(self) -> None:
        self.work = Path(tempfile.mkdtemp())

    def tearDown(self) -> None:
        shutil.rmtree(self.work)

    def raw_from_expected(self, case: str) -> Path:
        expected = CHAIN_ROOT / case / "expected"
        raw = self.work / case / "raw"
        metadata = java_candidate.case_metadata(case)
        for output in metadata["outputs"]:
            recorded = java_candidate.dataset_file(expected / "datasets", output["dsn"])
            if recorded is None:
                continue
            target = raw / "datasets" / f"{output['dsn']}.jsonl"
            if output.get("text"):
                rows = [{"line": line} for line in recorded.read_text().splitlines()]
            else:
                rows = java_candidate.decode_dataset(recorded, output["copybook"])
            java_candidate.write_jsonl(target, rows)
        shutil.copytree(expected / "db2_after", raw / "db2_after")
        shutil.copytree(expected / "sysout", raw / "sysout")
        shutil.copy2(expected / "rc.json", raw / "rc.json")
        return raw

    def test_encoded_expected_outputs_pass_compare_unchanged(self) -> None:
        for case in CASES:
            with self.subTest(case=case):
                candidate = self.work / case / "candidate"
                java_candidate.encode_candidate(case, self.raw_from_expected(case), candidate)
                expected = CHAIN_ROOT / case / "expected" / "datasets"
                self.assertEqual(
                    sorted(path.name for path in expected.iterdir()),
                    sorted(path.name for path in (candidate / "datasets").iterdir()),
                )
                report, rc = compare_case(case, candidate)
                self.assertEqual(rc, 0, report)

    def test_empty_candidate_reports_diffs_instead_of_crashing(self) -> None:
        raw = self.work / "default" / "raw"
        raw.mkdir(parents=True)
        (raw / "rc.json").write_text(json.dumps({"steps": {}, "maxcc": 0}))
        candidate = self.work / "default" / "candidate"
        java_candidate.encode_candidate("default", raw, candidate)
        report, rc = compare_case("default", candidate)
        self.assertEqual(rc, 1)
        self.assertIn("AWS.M2.CARDDEMO.XFER.FEES: missing dataset", report)
        self.assertIn("XFER_FEE_LEDGER: missing table", report)

    def test_only_restricts_compare_to_selected_dsn(self) -> None:
        raw = self.raw_from_expected("default")
        (raw / "rc.json").unlink()
        candidate = self.work / "default" / "candidate"
        java_candidate.encode_candidate("default", raw, candidate)
        _, rc = compare_case("default", candidate, {"AWS.M2.CARDDEMO.XFER.FEES"})
        self.assertEqual(rc, 0)

    def test_stage_inputs_decodes_fixture_inputs_as_strings(self) -> None:
        java_candidate.stage_inputs("default", self.work / "input")
        rows = java_candidate.read_jsonl(self.work / "input" / "DALYTRAN.jsonl")
        self.assertTrue(rows)
        self.assertTrue(all(isinstance(v, str) for row in rows for v in row.values()))
        self.assertTrue((self.work / "input" / "stub" / "AWS.M2.CARDDEMO.XFER.FEES.jsonl").exists())


if __name__ == "__main__":
    unittest.main()
