#!/usr/bin/env bash
set -euo pipefail
SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf -- "$WORK"' EXIT
mkdir "$WORK/bin"
export PATH="$WORK/bin:$PATH"
PARAMETER=/bareum/dev/ai/env

cat > "$WORK/bin/aws" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == ssm && "$2" == get-parameter ]]
printf '%s\n' "$@" > "$CASE_ROOT/aws-args"
if [[ "$MODE" == aws-fail ]]; then echo fixture-secret >&2; exit 1; fi
if [[ "$MODE" == malformed-response ]]; then printf '{"Value":"fixture-secret"'; exit 0; fi
cat "$CASE_ROOT/response.json"
MOCK
chmod +x "$WORK/bin/aws"

cat > "$WORK/baseline.env" <<'ENV'
BAREUM_AI_INTERNAL_API_TOKEN=fixture-secret-$dollar#hash=equals"quote\slash
BAREUM_AI_S3_ALLOWED_HOST=fixture-bucket.s3.ap-northeast-2.amazonaws.com
BAREUM_AI_S3_ALLOWED_KEY_PREFIX=reports/
BAREUM_AI_MAX_FILE_SIZE_BYTES=20000000
BAREUM_AI_MAX_PAGE_COUNT=60
BAREUM_AI_MAX_CONCURRENT_PARSES=1
BAREUM_AI_SOURCE_DOWNLOAD_TIMEOUT_SECONDS=120
BAREUM_AI_PARSER_TIMEOUT_SECONDS=120
BAREUM_AI_EXECUTION_TIMEOUT_SECONDS=300
BAREUM_AI_PROCESS_TERMINATION_GRACE_SECONDS=2
ENV

setup_case() {
  export MODE="$1" CASE_ROOT="$WORK/$1"
  mkdir -p "$CASE_ROOT/infra/scripts" "$CASE_ROOT/infra/env"
  if [[ -f "$SOURCE_DIR/scripts/prepare-ai-env.sh" ]]; then
    cp "$SOURCE_DIR/scripts/prepare-ai-env.sh" "$CASE_ROOT/infra/scripts/"
  fi
  cp "$WORK/baseline.env" "$CASE_ROOT/input.env"
  printf 'EXISTING_ENV=preserve-on-failure\n' > "$CASE_ROOT/infra/env/.env.ai"
  cp "$CASE_ROOT/infra/env/.env.ai" "$CASE_ROOT/original.env"
}

run_case() {
  jq -n --arg name "$PARAMETER" --rawfile value "$CASE_ROOT/input.env" \
    '{Parameter:{Name:$name,Type:"SecureString",Value:$value}}' > "$CASE_ROOT/response.json"
  if [[ -n "${FILTER:-}" ]]; then
    jq "$FILTER" "$CASE_ROOT/response.json" > "$CASE_ROOT/response.next"
    mv "$CASE_ROOT/response.next" "$CASE_ROOT/response.json"
  fi
  RESULT=0
  bash "$CASE_ROOT/infra/scripts/prepare-ai-env.sh" "$@" > "$CASE_ROOT/output" 2>&1 || RESULT=$?
  if grep -q 'fixture-secret' "$CASE_ROOT/output"; then
    echo 'FAIL: AI secret appeared in output' >&2; exit 1
  fi
  [[ -z "$(find "$CASE_ROOT/infra/env" -name '.env.ai.prepare.*' -print)" ]]
}

expect_success() {
  if [[ "$RESULT" != 0 ]]; then
    printf 'FAIL: %s did not generate the AI environment (exit %s)\n' "$MODE" "$RESULT" >&2
    exit 1
  fi
  local output="${1:-$CASE_ROOT/infra/env/.env.ai}"
  cmp "$CASE_ROOT/input.env" "$output"
  [[ "$(stat -c %a "$output")" == 600 ]]
  printf 'PASS: %s; raw content and permissions verified\n' "$MODE"
}

expect_failure() {
  [[ "$RESULT" != 0 ]]
  cmp "$CASE_ROOT/original.env" "$CASE_ROOT/infra/env/.env.ai"
  printf 'PASS: %s; previous AI environment preserved\n' "$MODE"
}

setup_case bootstrap
rm "$CASE_ROOT/infra/env/.env.ai"
run_case
expect_success
grep -Fxq -- "$PARAMETER" "$CASE_ROOT/aws-args"
grep -Fxq -- '--with-decryption' "$CASE_ROOT/aws-args"
grep -Fxq -- 'ap-northeast-2' "$CASE_ROOT/aws-args"

setup_case replace
run_case
expect_success

setup_case staged-output
run_case "$CASE_ROOT/candidate.env"
expect_success "$CASE_ROOT/candidate.env"
cmp "$CASE_ROOT/original.env" "$CASE_ROOT/infra/env/.env.ai"

setup_case required-only
sed -n '1,3p' "$WORK/baseline.env" > "$CASE_ROOT/input.env"
run_case
expect_success

setup_case future-setting
printf 'BAREUM_AI_FUTURE_SETTING=value\n' >> "$CASE_ROOT/input.env"
run_case
expect_success

setup_case no-final-newline
printf '%s' "$(cat "$WORK/baseline.env")" > "$CASE_ROOT/input.env"
run_case
expect_success

for key in INTERNAL_API_TOKEN S3_ALLOWED_HOST S3_ALLOWED_KEY_PREFIX; do
  setup_case "missing-$key"
  sed "/^BAREUM_AI_$key=/d" "$WORK/baseline.env" > "$CASE_ROOT/input.env"
  run_case
  expect_failure
done

for scenario in empty-token placeholder-token bad-host bad-prefix leading-slash duplicate malformed-line \
  invalid-size invalid-pages invalid-concurrency invalid-timeout; do
  setup_case "$scenario"
  case "$scenario" in
    empty-token) sed 's/^BAREUM_AI_INTERNAL_API_TOKEN=.*/BAREUM_AI_INTERNAL_API_TOKEN=   /' "$WORK/baseline.env" > "$CASE_ROOT/input.env" ;;
    placeholder-token) sed 's/^BAREUM_AI_INTERNAL_API_TOKEN=.*/BAREUM_AI_INTERNAL_API_TOKEN=replace-me/' "$WORK/baseline.env" > "$CASE_ROOT/input.env" ;;
    bad-host) sed 's@^BAREUM_AI_S3_ALLOWED_HOST=.*@BAREUM_AI_S3_ALLOWED_HOST=https://fixture.invalid/@' "$WORK/baseline.env" > "$CASE_ROOT/input.env" ;;
    bad-prefix) sed 's@^BAREUM_AI_S3_ALLOWED_KEY_PREFIX=.*@BAREUM_AI_S3_ALLOWED_KEY_PREFIX=reports@' "$WORK/baseline.env" > "$CASE_ROOT/input.env" ;;
    leading-slash) sed 's@^BAREUM_AI_S3_ALLOWED_KEY_PREFIX=.*@BAREUM_AI_S3_ALLOWED_KEY_PREFIX=/reports/@' "$WORK/baseline.env" > "$CASE_ROOT/input.env" ;;
    duplicate) printf 'BAREUM_AI_MAX_PAGE_COUNT=5\n' >> "$CASE_ROOT/input.env" ;;
    malformed-line) printf 'not-an-environment-entry\n' >> "$CASE_ROOT/input.env" ;;
    invalid-size) sed 's/MAX_FILE_SIZE_BYTES=20000000/MAX_FILE_SIZE_BYTES=20000001/' "$WORK/baseline.env" > "$CASE_ROOT/input.env" ;;
    invalid-pages) sed 's/MAX_PAGE_COUNT=60/MAX_PAGE_COUNT=61/' "$WORK/baseline.env" > "$CASE_ROOT/input.env" ;;
    invalid-concurrency) sed 's/MAX_CONCURRENT_PARSES=1/MAX_CONCURRENT_PARSES=0/' "$WORK/baseline.env" > "$CASE_ROOT/input.env" ;;
    invalid-timeout) sed 's/EXECUTION_TIMEOUT_SECONDS=300/EXECUTION_TIMEOUT_SECONDS=NaN/' "$WORK/baseline.env" > "$CASE_ROOT/input.env" ;;
  esac
  run_case
  expect_failure
done

for scenario in plaintext wrong-name non-string carriage-return nul; do
  setup_case "$scenario"
  case "$scenario" in
    plaintext) FILTER='.Parameter.Type="String"' ;;
    wrong-name) FILTER='.Parameter.Name="/bareum/dev/wrong/env"' ;;
    non-string) FILTER='.Parameter.Value=null' ;;
    carriage-return) FILTER='.Parameter.Value += "\r"' ;;
    nul) FILTER='.Parameter.Value += "\u0000"' ;;
  esac
  run_case
  expect_failure
done
FILTER=''

setup_case aws-fail
run_case
expect_failure

setup_case malformed-response
run_case
expect_failure

setup_case bootstrap-aws-fail
MODE=aws-fail
rm "$CASE_ROOT/infra/env/.env.ai"
run_case
[[ "$RESULT" != 0 && ! -e "$CASE_ROOT/infra/env/.env.ai" ]]
printf 'PASS: first-run failure leaves no AI environment file\n'

setup_case symlink-output
rm "$CASE_ROOT/infra/env/.env.ai"
ln -s "$CASE_ROOT/original.env" "$CASE_ROOT/infra/env/.env.ai"
run_case
[[ "$RESULT" != 0 && -L "$CASE_ROOT/infra/env/.env.ai" ]]
printf 'PASS: symlink output rejected\n'
printf 'All AI environment generation tests passed. Parameter Store calls were simulated.\n'
