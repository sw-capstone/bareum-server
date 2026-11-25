"""Validate JSON Schema headers and registered contract examples.

CI-SCHEMA-001: every ``*.schema.json`` has ``$schema``, ``$id`` and ``type``.
CI-SCHEMA-002: each registered example satisfies its JSON Schema (Draft 2020-12).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

try:
    from jsonschema import Draft202012Validator, FormatChecker
    from jsonschema.exceptions import SchemaError
except ImportError:  # pragma: no cover - reported when Schema validation is requested
    Draft202012Validator = None
    FormatChecker = None
    SchemaError = None

SKIP_PARTS = {".git", ".gradle", ".venv", "build", "dist", "node_modules", "out", "reports", "__pycache__"}

# Schema → example pairs. Add a pair here when a contract example is added.
SCHEMA_EXAMPLES = {
    "packages/contracts/report-content/1.0.0/content.schema.json": "packages/contracts/report-content/1.0.0/examples/pdf.example.json",
    "packages/contracts/pdf-parsing/1.0.0/request.schema.json": "packages/contracts/pdf-parsing/1.0.0/examples/request.example.json",
    "packages/contracts/pdf-parsing/1.0.0/success.schema.json": "packages/contracts/pdf-parsing/1.0.0/examples/success.example.json",
    "packages/contracts/pdf-parsing/1.0.0/failure.schema.json": "packages/contracts/pdf-parsing/1.0.0/examples/failure.example.json",
    "packages/contracts/report-intake/1.0.0/success.schema.json": "packages/contracts/report-intake/1.0.0/examples/success.example.json",
    "packages/contracts/report-intake/1.0.0/failure.schema.json": "packages/contracts/report-intake/1.0.0/examples/failure.example.json",
    "packages/contracts/report-intake/1.0.0/source-deletion.schema.json": "packages/contracts/report-intake/1.0.0/examples/source-deletion.example.json",
}

Finding = tuple[str, str, str]


class SchemaValidationError(ValueError):
    """Raised when a JSON value does not satisfy its JSON Schema."""


def validate(instance: object, schema: dict[str, object]) -> None:
    if not isinstance(schema, dict):
        raise SchemaValidationError("Schema는 JSON 객체여야 합니다")
    if Draft202012Validator is None or FormatChecker is None:
        raise SchemaValidationError("jsonschema 의존성이 없어 Draft 2020-12 검증을 실행할 수 없습니다")
    try:
        Draft202012Validator.check_schema(schema)
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        errors = sorted(validator.iter_errors(instance), key=lambda error: list(error.path))
    except SchemaError as error:
        raise SchemaValidationError(f"Schema가 유효하지 않습니다: {error}") from error
    except re.error as error:
        raise SchemaValidationError(f"Schema의 정규식이 유효하지 않습니다: {error}") from error
    if errors:
        raise SchemaValidationError(
            "; ".join(f"${'.'.join(str(part) for part in error.path)}: {error.message}" for error in errors)
        )


def iter_files(root: Path):
    for path in root.rglob("*"):
        if path.is_file() and not SKIP_PARTS.intersection(path.relative_to(root).parts):
            yield path


def check_schema_headers(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for path in iter_files(root):
        if not path.name.endswith(".schema.json"):
            continue
        relative = str(path.relative_to(root))
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
            findings.append(("CI-SCHEMA-001", relative, f"Schema JSON을 읽을 수 없습니다: {error}"))
            continue
        if not isinstance(data, dict) or not all(key in data for key in ("$schema", "$id", "type")):
            findings.append(("CI-SCHEMA-001", relative, "Schema 필수 헤더($schema, $id, type)가 없습니다."))
    return findings


def check_schema_examples(root: Path, pairs: dict[str, str] = SCHEMA_EXAMPLES) -> list[Finding]:
    findings: list[Finding] = []
    for schema_value, example_value in pairs.items():
        schema_path, example_path = root / schema_value, root / example_value
        missing = [value for value, path in ((schema_value, schema_path), (example_value, example_path)) if not path.is_file()]
        if missing:
            findings.extend(("CI-SCHEMA-002", value, "등록된 Schema 또는 예시 파일이 없습니다.") for value in missing)
            continue
        try:
            schema = json.loads(schema_path.read_text(encoding="utf-8"))
            example = json.loads(example_path.read_text(encoding="utf-8"))
            validate(example, schema)
        except (OSError, UnicodeDecodeError, json.JSONDecodeError, SchemaValidationError) as error:
            findings.append(("CI-SCHEMA-002", example_value, f"Schema 예시 검증 실패 ({schema_value}): {error}"))
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path.cwd())
    root = parser.parse_args(argv).root.resolve()
    findings = check_schema_headers(root) + check_schema_examples(root)
    for check_id, path, message in findings:
        print(f"ERROR {check_id} {path}: {message}")
    print(f"Contract checks: {'FAILED' if findings else 'PASSED'} ({len(SCHEMA_EXAMPLES)} examples)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
