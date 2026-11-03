import json
import unittest
from pathlib import Path
from typing import Final

from project_harness.schema import SchemaValidationError, validate


CONTRACT: Final = (
    Path(__file__).resolve().parents[2]
    / "packages/contracts/report-content/1.0.0"
)
SCHEMA: Final = CONTRACT / "content.schema.json"
EXAMPLES: Final = CONTRACT / "examples"


class ReportContentContractTest(unittest.TestCase):
    def test_all_examples_satisfy_the_content_schema(self) -> None:
        # Given
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        paths = sorted(EXAMPLES.glob("*.example.json"))
        self.assertGreater(len(paths), 0)
        for path in paths:
            with self.subTest(example=path.name):
                example = json.loads(path.read_text(encoding="utf-8"))
                # When / Then
                validate(example, schema)

    def test_content_schema_rejects_malformed_document_shapes(self) -> None:
        # Given
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        original = (EXAMPLES / "pdf.example.json").read_text(encoding="utf-8")
        cases = (
            ("unsupported version", '"schemaVersion": "1.0.0"', '"schemaVersion": "9.0.0"'),
            ("wrong offset unit", '"UNICODE_CODE_POINT"', '"UTF16_CODE_UNIT"'),
            ("missing units", '"units":', '"sentences":'),
            ("invalid UUID", '"id": "10000000-0000-4000-8000-000000000001"', '"id": "parser_b001"'),
            ("negative start", '"start": 0', '"start": -1'),
            ("fractional offset", '"start": 0', '"start": 0.5'),
            ("zero span", '"rowspan": 2', '"rowspan": 0'),
            ("missing actual cells", '"cells":', '"rows":'),
            ("table flattened into text", '"rowCount": 3,', '"rowCount": 3, "text": "flattened", "units": [],'),
            ("missing source text", '"sourceText":', '"rawText":'),
            ("missing parent relationship", '"parentId":', '"parent":'),
            ("missing section relationship", '"sectionId":', '"section":'),
            ("partial source cell position", '"parserColumn": 0,', ''),
            ("invalid bbox", '"bbox": [', '"bbox": [0,'),
            ("missing coordinate system", '"coordinateSystem":', '"coordinates":'),
            ("invalid extraction flag", '"requiresVision": false', '"requiresVision": "false"'),
            ("management field inside content", '"schemaVersion":', '"revision": 1, "schemaVersion":'),
            ("parser schema instead of content", '"schemaVersion":', '"schema_version":'),
        )
        for name, before, after in cases:
            with self.subTest(case=name):
                changed = original.replace(before, after, 1)
                self.assertNotEqual(changed, original)
                # When / Then
                with self.assertRaises(SchemaValidationError):
                    validate(json.loads(changed), schema)

    def test_unit_kinds_are_rejected_in_blocks_and_cells(self) -> None:
        # Given
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        original = (EXAMPLES / "pdf.example.json").read_text(encoding="utf-8")
        validate(json.loads(original), schema)
        for owner_index, kind in (
            (0, "sentence"), (0, "fragment"),
            (1, "sentence"), (1, "fragment"),
        ):
            with self.subTest(owner_index=owner_index, kind=kind):
                example = json.loads(original)
                owners = (example["blocks"][1], example["blocks"][3]["cells"][0])
                owners[owner_index]["units"][0]["kind"] = kind
                # When / Then
                with self.assertRaises(SchemaValidationError):
                    validate(example, schema)

    def test_cell_source_requires_original_parser_coordinates(self) -> None:
        # Given
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        original = (EXAMPLES / "pdf.example.json").read_text(encoding="utf-8")
        for removed_fields in (("parserRow",), ("parserColumn",), ("parserRow", "parserColumn")):
            with self.subTest(removed_fields=removed_fields):
                example = json.loads(original)
                source = example["blocks"][3]["cells"][0]["sourceRefs"][0]
                for field in removed_fields:
                    del source[field]
                # When / Then
                with self.assertRaises(SchemaValidationError):
                    validate(example, schema)

    def test_direct_input_content_rejects_pdf_pages(self) -> None:
        # Given
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        original = (EXAMPLES / "pdf.example.json").read_text(encoding="utf-8")
        example = original.replace('"sourceFormat": "PDF"', '"sourceFormat": "TEXT"', 1)
        # When / Then
        with self.assertRaises(SchemaValidationError):
            validate(json.loads(example), schema)

    def test_complete_table_rejects_unresolved_positions(self) -> None:
        # Given
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        original = (EXAMPLES / "partial-table.example.json").read_text(encoding="utf-8")
        example = original.replace('"structureStatus": "partial"', '"structureStatus": "complete"', 1)
        # When / Then
        with self.assertRaises(SchemaValidationError):
            validate(json.loads(example), schema)

    def test_empty_cell_rejects_text_units(self) -> None:
        # Given
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        original = (EXAMPLES / "direct-input.example.json").read_text(encoding="utf-8")
        example = original.replace('"text": "2026. 10. 17."', '"text": ""', 1)
        # When / Then
        with self.assertRaises(SchemaValidationError):
            validate(json.loads(example), schema)

    def test_block_line_counts_accept_non_negative_integers(self) -> None:
        # Given
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        original = (EXAMPLES / "pdf.example.json").read_text(encoding="utf-8")
        for block_index in (1, 3):
            for line_count in (0, 7):
                with self.subTest(block_index=block_index, line_count=line_count):
                    example = json.loads(original)
                    example["blocks"][block_index]["lineCount"] = line_count
                    # When / Then
                    validate(example, schema)

    def test_block_line_counts_reject_invalid_values(self) -> None:
        # Given
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        original = (EXAMPLES / "pdf.example.json").read_text(encoding="utf-8")
        for block_index in (1, 3):
            for line_count in (-1, 1.5, "2", True, None):
                with self.subTest(block_index=block_index, line_count=line_count):
                    example = json.loads(original)
                    example["blocks"][block_index]["lineCount"] = line_count
                    # When / Then
                    with self.assertRaises(SchemaValidationError):
                        validate(example, schema)

    def test_existing_bodies_may_omit_block_line_counts(self) -> None:
        # Given
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        for name in ("pdf", "direct-input", "partial-table"):
            with self.subTest(example=name):
                example = json.loads(
                    (EXAMPLES / f"{name}.example.json").read_text(encoding="utf-8")
                )
                for block in example["blocks"]:
                    block.pop("lineCount", None)
                # When / Then
                validate(example, schema)

    def test_unknown_warning_codes_remain_valid(self) -> None:
        # Given
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        original = (EXAMPLES / "partial-table.example.json").read_text(encoding="utf-8")
        example = original.replace('"TABLE_STRUCTURE_UNCERTAIN"', '"FUTURE_PARSER_WARNING"', 1)
        # When / Then
        validate(json.loads(example), schema)
