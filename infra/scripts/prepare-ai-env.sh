#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
OUTPUT="${1:-$INFRA_DIR/env/.env.ai}"
PARAMETER=/bareum/dev/ai/env

fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ $# -le 1 ]] || fail 'Usage: prepare-ai-env.sh [OUTPUT_FILE]'
for tool in aws jq awk; do command -v "$tool" >/dev/null || fail "Required command not found: $tool"; done
[[ "$OUTPUT" == /* && ! -L "$OUTPUT" && ! -d "$OUTPUT" ]] \
  || fail 'AI environment output must be an absolute file path, not a symlink or directory.'
WORK=$(mktemp -d "$(dirname -- "$OUTPUT")/.env.ai.prepare.XXXXXX")
trap 'rm -rf -- "$WORK"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! aws ssm get-parameter --region ap-northeast-2 --name "$PARAMETER" \
  --with-decryption --output json --no-cli-pager > "$WORK/parameter.json" 2> "$WORK/aws-error.log"; then
  fail 'AI Parameter Store lookup failed. Existing environment preserved.'
fi
if ! jq -e --arg name "$PARAMETER" '
  .Parameter.Name == $name and .Parameter.Type == "SecureString" and
  (.Parameter.Value | type == "string" and length > 0 and (test("[\r\u0000]") | not))
' "$WORK/parameter.json" >/dev/null 2>&1; then
  fail 'AI parameter must contain the complete env file as a SecureString. Existing environment preserved.'
fi
jq -j '.Parameter.Value' "$WORK/parameter.json" > "$WORK/ai.env"

if ! awk '
  /^[[:space:]]*(#|$)/ { next }
  !/^[A-Za-z_][A-Za-z0-9_]*=/ { bad=1; next }
  {
    key=substr($0, 1, index($0, "=")-1)
    value=substr($0, index($0, "=")+1)
    if (seen[key]++) bad=1
    values[key]=value
    if (key ~ /^BAREUM_AI_(MAX_FILE_SIZE_BYTES|MAX_PAGE_COUNT|MAX_CONCURRENT_PARSES)$/) {
      if (value !~ /^[0-9]+$/ || value+0 <= 0) bad=1
      if (key == "BAREUM_AI_MAX_FILE_SIZE_BYTES" && value+0 > 20000000) bad=1
      if (key == "BAREUM_AI_MAX_PAGE_COUNT" && value+0 > 60) bad=1
    }
    if (key ~ /^BAREUM_AI_(SOURCE_DOWNLOAD_TIMEOUT|PARSER_TIMEOUT|EXECUTION_TIMEOUT|PROCESS_TERMINATION_GRACE)_SECONDS$/) {
      if (value !~ /^[+]?[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$/ || value+0 <= 0 || value+0 > 1.7976931348623157e308) bad=1
    }
  }
  END {
    token=values["BAREUM_AI_INTERNAL_API_TOKEN"]
    host=values["BAREUM_AI_S3_ALLOWED_HOST"]
    prefix=values["BAREUM_AI_S3_ALLOWED_KEY_PREFIX"]
    if (token !~ /[^[:space:]]/ || token == "replace-me") bad=1
    if (host !~ /^([a-z0-9]([a-z0-9-]*[a-z0-9])?[.])+[a-z][a-z]+$/) bad=1
    if (prefix !~ /[^[:space:]]/ || prefix ~ /^\// || prefix !~ /\/$/) bad=1
    exit bad
  }
' "$WORK/ai.env"; then
  fail 'AI environment validation failed. Check required keys, raw syntax, duplicates and numeric limits. Existing environment preserved.'
fi

chmod 600 "$WORK/ai.env"
mv -f -- "$WORK/ai.env" "$OUTPUT"
printf 'AI environment prepared (permissions 600).\n'
