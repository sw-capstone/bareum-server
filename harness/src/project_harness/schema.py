from __future__ import annotations

import re

try:
    from jsonschema import Draft202012Validator, FormatChecker
    from jsonschema.exceptions import SchemaError
except ImportError:  # pragma: no cover - reported when Schema validation is requested
    Draft202012Validator = None
    FormatChecker = None
    SchemaError = None


class SchemaValidationError(ValueError):
    """Raised when a JSON value does not satisfy its JSON Schema."""


def validate(instance: object, schema: dict[str, object]) -> None:
    if not isinstance(schema, dict):
        raise SchemaValidationError("Schema는 JSON 객체여야 합니다")
    if Draft202012Validator is None or FormatChecker is None:
        raise SchemaValidationError(
            "jsonschema 의존성이 없어 Draft 2020-12 검증을 실행할 수 없습니다"
        )

    try:
        Draft202012Validator.check_schema(schema)
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        errors = sorted(validator.iter_errors(instance), key=lambda error: list(error.path))
    except SchemaError as error:
        raise SchemaValidationError(f"Schema가 유효하지 않습니다: {error}") from error
    except re.error as error:
        raise SchemaValidationError(f"Schema의 정규식이 유효하지 않습니다: {error}") from error

    if errors:
        detail = "; ".join(
            f"${'.'.join(str(part) for part in error.path)}: {error.message}"
            for error in errors
        )
        raise SchemaValidationError(detail)
