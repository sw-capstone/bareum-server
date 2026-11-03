#!/usr/bin/env bash
set -euo pipefail
SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf -- "$WORK"' EXIT
mkdir "$WORK/bin"
export PATH="$WORK/bin:$PATH"
PARAMETER=/bareum/dev/server/env

cat > "$WORK/bin/aws" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == ssm && "$2" == get-parameter ]]
printf '%s\n' "$@" > "$CASE_ROOT/aws-args"
printf 'call\n' >> "$CASE_ROOT/aws-calls"
if [[ "$MODE" == aws-fail ]]; then echo fixture-secret >&2; exit 1; fi
if [[ "$MODE" == malformed-response ]]; then printf '{"Value":"fixture-secret"'; exit 0; fi
cat "$CASE_ROOT/response.json"
MOCK
chmod +x "$WORK/bin/aws"

cat > "$WORK/baseline.env" <<'ENV'
# 실제 운영값이 아닌 테스트 전용 가짜 env. 필수 키와 검증용 값만 포함한다.
SERVER_PORT=8080
DB_URL=jdbc:postgresql://fixture.invalid:5432/test
DB_USERNAME=fixture-user
DB_PASSWORD=fixture-secret-$dollar#hash=equals"quote\slash
SPRING_MAIL_HOST=smtp.fixture.invalid
SPRING_MAIL_USERNAME=sender@fixture.invalid
SPRING_MAIL_PASSWORD=fixture-secret-mail-${LITERAL}#hash=equals"quote\slash
EMAIL_VERIFICATION_MAIL_FROM=sender@fixture.invalid
EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS=3
EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS=120
LOGIN_ALLOWED_ORIGINS=https://web.fixture.invalid,https://admin.fixture.invalid
ENV
jq -nr '
  "EMAIL_VERIFICATION_HASH_SECRET=" + ("h" * 32 | @base64),
  "EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY=" + ("e" * 32 | @base64),
  "LOGIN_JWT_SIGNING_KEY=" + ("j" * 32 | @base64)
' >> "$WORK/baseline.env"

REQUIRED_KEYS=(SERVER_PORT DB_URL DB_USERNAME DB_PASSWORD
  EMAIL_VERIFICATION_HASH_SECRET EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY
  SPRING_MAIL_PASSWORD SPRING_MAIL_HOST SPRING_MAIL_USERNAME EMAIL_VERIFICATION_MAIL_FROM
  EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS
  LOGIN_JWT_SIGNING_KEY LOGIN_ALLOWED_ORIGINS)

setup_case() {
  export MODE="$1" CASE_ROOT="$WORK/$1"
  FILTER=''
  mkdir -p "$CASE_ROOT/infra/scripts" "$CASE_ROOT/infra/env"
  cp "$SOURCE_DIR/scripts/prepare-server-env.sh" "$CASE_ROOT/infra/scripts/"
  cp "$WORK/baseline.env" "$CASE_ROOT/input.env"
  printf 'EXISTING_ENV=preserve-on-failure\n' > "$CASE_ROOT/original.env"
  cp "$CASE_ROOT/original.env" "$CASE_ROOT/infra/env/.env.server"
}

set_value() {
  local key="$1" value="$2"
  jq -Rrs --arg key "$key" --arg value "$value" '
    split("\n") | map(if startswith($key + "=") then $key + "=" + $value else . end) | join("\n")
  ' "$CASE_ROOT/input.env" > "$CASE_ROOT/input.next"
  mv "$CASE_ROOT/input.next" "$CASE_ROOT/input.env"
}

run_case() {
  local key value
  jq -n --arg name "$PARAMETER" --rawfile value "$CASE_ROOT/input.env" \
    '{Parameter:{Name:$name,Type:"SecureString",Value:$value}}' > "$CASE_ROOT/response.json"
  if [[ -n "$FILTER" ]]; then
    jq "$FILTER" "$CASE_ROOT/response.json" > "$CASE_ROOT/response.next"
    mv "$CASE_ROOT/response.next" "$CASE_ROOT/response.json"
  fi
  RESULT=0
  bash "$CASE_ROOT/infra/scripts/prepare-server-env.sh" "$@" > "$CASE_ROOT/output" 2>&1 || RESULT=$?
  if grep -q 'fixture-secret' "$CASE_ROOT/output"; then
    printf '실패: 비밀값이 출력됨 (%s)\n' "$MODE" >&2
    exit 1
  fi
  while IFS='=' read -r key value || [[ -n "$key" ]]; do
    case "$key" in
      EMAIL_VERIFICATION_HASH_SECRET|EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY|LOGIN_JWT_SIGNING_KEY)
        if [[ -n "$value" ]] && grep -Fq -- "$value" "$CASE_ROOT/output"; then
          printf '실패: 비밀키가 출력됨 (%s)\n' "$MODE" >&2
          exit 1
        fi ;;
    esac
  done < "$CASE_ROOT/input.env"
  [[ -z "$(find "$CASE_ROOT" -name '.env.prepare.*' -print)" ]]
}

expect_failure() {
  [[ "$RESULT" != 0 ]]
  cmp "$CASE_ROOT/original.env" "$CASE_ROOT/infra/env/.env.server"
  printf 'PASS: %s; 실패 시 기존 환경파일 보존\n' "$MODE"
}

expect_success() {
  if [[ "$RESULT" != 0 ]]; then
    printf '실패: 환경파일 생성 실패 (%s, 종료 코드 %s)\n' "$MODE" "$RESULT" >&2
    cat "$CASE_ROOT/output" >&2
    return 1
  fi
  local generated="${1:-$CASE_ROOT/infra/env/.env.server}" mode
  cmp "$CASE_ROOT/input.env" "$generated"
  mode=$(stat -c %a "$generated" 2>/dev/null || stat -f %Lp "$generated")
  [[ "$mode" == 600 ]]
  printf 'PASS: %s; 원문과 파일 권한 보존\n' "$MODE"
}

setup_case success
run_case
expect_success
printf '%s\n' ssm get-parameter --region ap-northeast-2 --name "$PARAMETER" \
  --with-decryption --output json --no-cli-pager > "$CASE_ROOT/expected-args"
cmp "$CASE_ROOT/expected-args" "$CASE_ROOT/aws-args"
[[ $(wc -l < "$CASE_ROOT/aws-calls") == 1 ]]
printf 'PASS: 단일 파라미터를 한 번만 조회하고 복호화\n'

setup_case stale-template
printf 'SERVER_PORT=9999\nNOT_VALID\n' > "$CASE_ROOT/infra/env/.env.server.template"
run_case
expect_success

setup_case staged-output
run_case "$CASE_ROOT/candidate.env"
expect_success "$CASE_ROOT/candidate.env"
cmp "$CASE_ROOT/original.env" "$CASE_ROOT/infra/env/.env.server"

setup_case future-setting
cat >> "$CASE_ROOT/input.env" <<'ENV'
FUTURE_SETTING=$LITERAL#hash=equals
OPTIONAL_EMPTY=
ENV
run_case
expect_success

setup_case literal-command
printf '%s%s\n' 'FUTURE_SETTING=$' "(touch $CASE_ROOT/should-not-exist)" >> "$CASE_ROOT/input.env"
run_case
expect_success
[[ ! -e "$CASE_ROOT/should-not-exist" ]]

setup_case no-final-newline
printf '%s' "$(cat "$WORK/baseline.env")" > "$CASE_ROOT/input.env"
run_case
expect_success

setup_case comments
printf '\n   # 들여쓴 주석\n\t# 탭 주석\n \t\n' >> "$CASE_ROOT/input.env"
run_case
expect_success

for key in "${REQUIRED_KEYS[@]}"; do
  setup_case "missing-$key"
  sed "/^$key=/d" "$WORK/baseline.env" > "$CASE_ROOT/input.env"
  run_case
  expect_failure

  setup_case "empty-$key"
  set_value "$key" ''
  run_case
  expect_failure

  setup_case "whitespace-$key"
  set_value "$key" '   '
  run_case
  expect_failure
done

for key in EMAIL_VERIFICATION_HASH_SECRET LOGIN_JWT_SIGNING_KEY; do
  setup_case "longer-$key"
  set_value "$key" "$(jq -nr '"k" * 48 | @base64')"
  run_case
  expect_success
done

for key in EMAIL_VERIFICATION_HASH_SECRET EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY LOGIN_JWT_SIGNING_KEY; do
  for scenario in short bad-base64 bad-padding; do
    setup_case "$scenario-$key"
    case "$scenario" in
      short) value=$(jq -nr '"k" * 31 | @base64') ;;
      bad-base64) value=$(jq -nr '"!" * 44') ;;
      bad-padding) value=$(jq -nr '("A" * 42) + "=A"') ;;
    esac
    set_value "$key" "$value"
    run_case
    expect_failure
  done
done

setup_case long-outbox
set_value EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY "$(jq -nr '"e" * 33 | @base64')"
run_case
expect_failure

for key in EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS; do
  for value in 0 -1 1.5 1e2 abc 01 99999999999999999999; do
    setup_case "invalid-$key-$value"
    set_value "$key" "$value"
    run_case
    expect_failure
  done
done

setup_case integer-overflow
set_value EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS 2147483648
run_case
expect_failure

setup_case long-overflow
set_value EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS 9223372036854775808
run_case
expect_failure

setup_case numeric-upper-bounds
set_value EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS 2147483647
set_value EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS 9223372036854775807
run_case
expect_success

origin_case=0
for value in '*' 'null' 'http://bareum.site' 'https://bareum.site/' 'https://bareum.site/path' \
  'https://bareum.site?query=1' 'https://user@bareum.site' 'https://bareum.site#fragment' \
  'https://bareum.site,' ',https://bareum.site' 'https://bareum.site, https://web.example.invalid' \
  'https://bareum.site,https://bareum.site' 'https://bareum.site:65536' 'https://bareum..site' 'https://-bareum.site'; do
  origin_case=$((origin_case+1))
  setup_case "invalid-origin-$origin_case"
  set_value LOGIN_ALLOWED_ORIGINS "$value"
  run_case
  expect_failure
done

setup_case origin-port
set_value LOGIN_ALLOWED_ORIGINS https://fixture.invalid:65535
run_case
expect_success

for scenario in wrong-port duplicate duplicate-unknown malformed-line export-prefix leading-space invalid-key; do
  setup_case "$scenario"
  case "$scenario" in
    wrong-port) set_value SERVER_PORT 9999 ;;
    duplicate) printf 'DB_PASSWORD=fixture-secret-duplicate\n' >> "$CASE_ROOT/input.env" ;;
    duplicate-unknown) printf 'FUTURE_SETTING=one\nFUTURE_SETTING=two\n' >> "$CASE_ROOT/input.env" ;;
    malformed-line) printf 'fixture-secret-invalid-line\n' >> "$CASE_ROOT/input.env" ;;
    export-prefix) printf 'export FUTURE_SETTING=value\n' >> "$CASE_ROOT/input.env" ;;
    leading-space) printf ' FUTURE_SETTING=value\n' >> "$CASE_ROOT/input.env" ;;
    invalid-key) printf 'INVALID-KEY=value\n' >> "$CASE_ROOT/input.env" ;;
  esac
  run_case
  expect_failure
done

for scenario in plaintext wrong-name non-string empty-env comments-only carriage-return nul multiple-responses; do
  setup_case "$scenario"
  case "$scenario" in
    plaintext) FILTER='.Parameter.Type="String"' ;;
    wrong-name) FILTER='.Parameter.Name="/bareum/dev/wrong/env"' ;;
    non-string) FILTER='.Parameter.Value=null' ;;
    empty-env) FILTER='.Parameter.Value=""' ;;
    comments-only) FILTER='.Parameter.Value="# 주석만 있는 파일\n\n"' ;;
    carriage-return) FILTER='.Parameter.Value += "\r"' ;;
    nul) FILTER='.Parameter.Value += "\u0000"' ;;
    multiple-responses) FILTER='., .' ;;
  esac
  run_case
  expect_failure
done

for scenario in aws-fail malformed-response; do
  setup_case "$scenario"
  run_case
  expect_failure
done

setup_case first-run
rm "$CASE_ROOT/infra/env/.env.server"
run_case
expect_success

for scenario in aws-fail invalid-env; do
  setup_case "first-run-$scenario"
  if [[ "$scenario" == aws-fail ]]; then MODE=aws-fail
  else set_value DB_PASSWORD ''; fi
  rm "$CASE_ROOT/infra/env/.env.server"
  run_case
  [[ "$RESULT" != 0 && ! -e "$CASE_ROOT/infra/env/.env.server" ]]
  printf 'PASS: 최초 생성 실패 시 환경파일을 남기지 않음 (%s)\n' "$scenario"
done

setup_case symlink-output
rm "$CASE_ROOT/infra/env/.env.server"
ln -s "$CASE_ROOT/original.env" "$CASE_ROOT/infra/env/.env.server"
run_case
expect_failure
[[ -L "$CASE_ROOT/infra/env/.env.server" ]]

setup_case directory-output
mkdir "$CASE_ROOT/output-directory"
run_case "$CASE_ROOT/output-directory"
expect_failure
[[ -d "$CASE_ROOT/output-directory" ]]

setup_case relative-output
run_case relative.env
expect_failure

setup_case too-many-arguments
run_case "$CASE_ROOT/candidate.env" extra
expect_failure
[[ ! -e "$CASE_ROOT/candidate.env" ]]

printf '서버 환경파일 생성 테스트 통과. AWS 응답은 모의했습니다.\n'
