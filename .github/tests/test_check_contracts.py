from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from check_contracts import SCHEMA_EXAMPLES, check_schema_examples, check_schema_headers, main

ROOT = Path(__file__).resolve().parents[2]
HEADER = {"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://example.invalid/x", "type": "object"}


class ContractChecksTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, relative: str, value: object) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value if isinstance(value, str) else json.dumps(value), encoding="utf-8")

    def test_repository_contracts_pass(self) -> None:
        self.assertEqual(check_schema_headers(ROOT), [])
        self.assertEqual(check_schema_examples(ROOT), [])
        self.assertEqual(main(["--root", str(ROOT)]), 0)

    def test_registered_pairs_exist(self) -> None:
        for schema, example in SCHEMA_EXAMPLES.items():
            with self.subTest(schema=schema):
                self.assertTrue((ROOT / schema).is_file())
                self.assertTrue((ROOT / example).is_file())

    def test_missing_header_is_reported(self) -> None:
        self.write("a/x.schema.json", {"type": "object"})
        self.assertEqual([f[0] for f in check_schema_headers(self.root)], ["CI-SCHEMA-001"])

    def test_invalid_schema_json_is_reported_without_crashing(self) -> None:
        self.write("a/x.schema.json", "{")
        self.assertEqual([f[0] for f in check_schema_headers(self.root)], ["CI-SCHEMA-001"])

    def test_build_outputs_are_skipped(self) -> None:
        self.write("build/x.schema.json", {"type": "object"})
        self.assertEqual(check_schema_headers(self.root), [])

    def test_example_type_and_enum_mismatch_is_reported(self) -> None:
        schema = {**HEADER, "properties": {"status": {"enum": ["OK"]}, "count": {"type": "integer"}}}
        self.write("c/x.schema.json", schema)
        self.write("c/x.example.json", {"status": "NO", "count": "1"})
        findings = check_schema_examples(self.root, {"c/x.schema.json": "c/x.example.json"})
        self.assertEqual([f[0] for f in findings], ["CI-SCHEMA-002"])
        self.assertIn("count", findings[0][2])
        self.assertIn("status", findings[0][2])

    def test_missing_registered_file_is_reported(self) -> None:
        self.write("c/x.schema.json", HEADER)
        findings = check_schema_examples(self.root, {"c/x.schema.json": "c/missing.example.json"})
        self.assertEqual(findings, [("CI-SCHEMA-002", "c/missing.example.json", "등록된 Schema 또는 예시 파일이 없습니다.")])

    def test_invalid_schema_definition_is_reported(self) -> None:
        self.write("c/x.schema.json", {**HEADER, "type": "no-such-type"})
        self.write("c/x.example.json", {})
        findings = check_schema_examples(self.root, {"c/x.schema.json": "c/x.example.json"})
        self.assertEqual([f[0] for f in findings], ["CI-SCHEMA-002"])

    def test_failure_returns_non_zero(self) -> None:
        self.write("a/x.schema.json", {"type": "object"})
        self.assertEqual(main(["--root", str(self.root)]), 1)


if __name__ == "__main__":
    unittest.main()
