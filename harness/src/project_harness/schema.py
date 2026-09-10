from __future__ import annotations

import re
from datetime import datetime
from urllib.parse import urlparse

try:
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError:  # pragma: no cover - local fallback for a dependency-free checkout
    Draft202012Validator = None
    FormatChecker = None


class SchemaValidationError(ValueError):
    """Raised when a JSON value does not satisfy the supported Schema subset."""


def validate(instance: object, schema: dict[str, object], *, root: dict[str, object] | None = None) -> None:
    if not isinstance(schema, dict):
        raise SchemaValidationError("Schema는 JSON 객체여야 합니다")
    if Draft202012Validator is not None:
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        errors = sorted(validator.iter_errors(instance), key=lambda error: list(error.path))
        if errors:
            detail = "; ".join(f"${'.'.join(str(part) for part in error.path)}: {error.message}" for error in errors)
            raise SchemaValidationError(detail)
        return

    errors: list[str] = []
    _validate(instance, schema, root or schema, "$", errors)
    if errors:
        raise SchemaValidationError("; ".join(errors))


def _validate(
    instance: object,
    schema: dict[str, object],
    root: dict[str, object],
    location: str,
    errors: list[str],
) -> None:
    if "anyOf" in schema:
        options = schema["anyOf"]
        if not isinstance(options, list) or not options:
            errors.append(f"{location}: anyOf는 하나 이상의 Schema 배열이어야 합니다")
            return
        option_errors: list[list[str]] = []
        for subschema in options:
            current_errors: list[str] = []
            if isinstance(subschema, dict):
                _validate(instance, subschema, root, location, current_errors)
            else:
                current_errors.append(f"{location}: anyOf 항목은 Schema 객체여야 합니다")
            option_errors.append(current_errors)
        if all(current_errors for current_errors in option_errors):
            errors.append(f"{location}: anyOf 조건 중 하나를 만족해야 합니다")

    if "oneOf" in schema:
        options = schema["oneOf"]
        if not isinstance(options, list) or not options:
            errors.append(f"{location}: oneOf는 하나 이상의 Schema 배열이어야 합니다")
            return
        match_count = 0
        for subschema in options:
            current_errors: list[str] = []
            if isinstance(subschema, dict):
                _validate(instance, subschema, root, location, current_errors)
            else:
                current_errors.append(f"{location}: oneOf 항목은 Schema 객체여야 합니다")
            if not current_errors:
                match_count += 1
        if match_count != 1:
            errors.append(f"{location}: oneOf 조건을 정확히 하나 만족해야 합니다")

    if "$ref" in schema:
        reference = schema["$ref"]
        if not isinstance(reference, str) or not reference.startswith("#/"):
            errors.append(f"{location}: 지원하지 않는 $ref")
            return
        target: object = root
        for part in reference[2:].split("/"):
            if not isinstance(target, dict) or part not in target:
                errors.append(f"{location}: 해석할 수 없는 $ref {reference}")
                return
            target = target[part]
        if isinstance(target, dict):
            _validate(instance, target, root, location, errors)
        return

    if "allOf" in schema:
        for index, subschema in enumerate(schema["allOf"]):
            if isinstance(subschema, dict):
                _validate(instance, subschema, root, f"{location}.allOf[{index}]", errors)

    if "if" in schema and isinstance(schema["if"], dict):
        condition_errors: list[str] = []
        _validate(instance, schema["if"], root, location, condition_errors)
        branch = schema.get("then") if not condition_errors else schema.get("else")
        if isinstance(branch, dict):
            _validate(instance, branch, root, location, errors)

    if "not" in schema and isinstance(schema["not"], dict):
        condition_errors: list[str] = []
        _validate(instance, schema["not"], root, location, condition_errors)
        if not condition_errors:
            errors.append(f"{location}: not 조건을 만족하면 안 됩니다")

    if "const" in schema and instance != schema["const"]:
        errors.append(f"{location}: const 값이 {schema['const']!r}이어야 합니다")

    if "enum" in schema and instance not in schema["enum"]:
        errors.append(f"{location}: 허용되지 않은 enum 값 {instance!r}")

    expected_types = schema.get("type")
    if expected_types is not None:
        allowed_types = expected_types if isinstance(expected_types, list) else [expected_types]
        if not any(_matches_type(instance, value) for value in allowed_types):
            errors.append(f"{location}: type이 {allowed_types!r}이어야 합니다")
            return

    if isinstance(instance, dict):
        _validate_object(instance, schema, root, location, errors)
    elif isinstance(instance, list):
        _validate_array(instance, schema, root, location, errors)
    elif isinstance(instance, str):
        _validate_string(instance, schema, location, errors)
    elif isinstance(instance, (int, float)) and not isinstance(instance, bool):
        _validate_number(instance, schema, location, errors)


def _matches_type(value: object, expected: object) -> bool:
    return {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "number": isinstance(value, (int, float)) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
        "null": value is None,
    }.get(expected, False)


def _validate_object(
    instance: dict[str, object],
    schema: dict[str, object],
    root: dict[str, object],
    location: str,
    errors: list[str],
) -> None:
    required = schema.get("required", [])
    if isinstance(required, list):
        for key in required:
            if key not in instance:
                errors.append(f"{location}: 필수 필드 {key!r}가 없습니다")

    properties = schema.get("properties", {})
    if not isinstance(properties, dict):
        properties = {}
    for key, subschema in properties.items():
        if key in instance and isinstance(subschema, dict):
            _validate(instance[key], subschema, root, f"{location}.{key}", errors)

    additional = schema.get("additionalProperties", True)
    if additional is False:
        for key in instance:
            if key not in properties:
                errors.append(f"{location}: 허용되지 않은 필드 {key!r}가 있습니다")
    elif isinstance(additional, dict):
        for key, value in instance.items():
            if key not in properties:
                _validate(value, additional, root, f"{location}.{key}", errors)


def _validate_array(
    instance: list[object],
    schema: dict[str, object],
    root: dict[str, object],
    location: str,
    errors: list[str],
) -> None:
    if isinstance(schema.get("minItems"), int) and len(instance) < schema["minItems"]:
        errors.append(f"{location}: 항목 수가 최소 {schema['minItems']}개여야 합니다")
    if isinstance(schema.get("maxItems"), int) and len(instance) > schema["maxItems"]:
        errors.append(f"{location}: 항목 수가 최대 {schema['maxItems']}개여야 합니다")
    if schema.get("uniqueItems") and len({repr(value) for value in instance}) != len(instance):
        errors.append(f"{location}: 중복 항목이 있습니다")

    prefix_items = schema.get("prefixItems", [])
    prefix_length = len(prefix_items) if isinstance(prefix_items, list) else 0
    if isinstance(prefix_items, list):
        for index, subschema in enumerate(prefix_items):
            if index < len(instance) and isinstance(subschema, dict):
                _validate(instance[index], subschema, root, f"{location}[{index}]", errors)
    items = schema.get("items")
    if items is False:
        if len(instance) > prefix_length:
            errors.append(f"{location}: prefixItems 외 항목이 허용되지 않습니다")
    elif isinstance(items, dict):
        for index, value in enumerate(instance[prefix_length:], start=prefix_length):
            _validate(value, items, root, f"{location}[{index}]", errors)


def _validate_string(instance: str, schema: dict[str, object], location: str, errors: list[str]) -> None:
    if isinstance(schema.get("minLength"), int) and len(instance) < schema["minLength"]:
        errors.append(f"{location}: 문자열 길이가 최소 {schema['minLength']}자여야 합니다")
    if isinstance(schema.get("pattern"), str) and re.search(schema["pattern"], instance) is None:
        errors.append(f"{location}: pattern과 일치하지 않습니다")
    format_name = schema.get("format")
    if format_name == "date-time":
        try:
            datetime.fromisoformat(instance.replace("Z", "+00:00"))
        except ValueError:
            errors.append(f"{location}: date-time 형식이 아닙니다")
    elif format_name == "date":
        try:
            datetime.strptime(instance, "%Y-%m-%d")
        except ValueError:
            errors.append(f"{location}: date 형식이 아닙니다")
    elif format_name == "uri":
        parsed = urlparse(instance)
        if not parsed.scheme:
            errors.append(f"{location}: URI 형식이 아닙니다")


def _validate_number(instance: int | float, schema: dict[str, object], location: str, errors: list[str]) -> None:
    if isinstance(schema.get("minimum"), (int, float)) and instance < schema["minimum"]:
        errors.append(f"{location}: minimum보다 작습니다")
    if isinstance(schema.get("maximum"), (int, float)) and instance > schema["maximum"]:
        errors.append(f"{location}: maximum보다 큽니다")
