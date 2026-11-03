#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

OUTPUT="${1:-$REPO_DIR/infra/env/.env.server}"
PARAMETER=/bareum/dev/server/env

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

[[ $# -le 1 ]] || fail 'Usage: prepare-server-env.sh [OUTPUT_FILE]'
command -v aws >/dev/null || fail 'AWS CLI가 필요합니다.'
command -v jq >/dev/null || fail 'jq가 필요합니다.'
[[ "$OUTPUT" == /* && ! -L "$OUTPUT" && ( ! -e "$OUTPUT" || -f "$OUTPUT" ) ]] \
  || fail '출력 경로는 심볼릭 링크나 디렉터리가 아닌 절대 파일 경로여야 합니다.'

WORK_DIR="$(mktemp -d "$(dirname -- "$OUTPUT")/.env.prepare.XXXXXX")"
trap 'rm -rf -- "$WORK_DIR"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! aws ssm get-parameter \
  --region ap-northeast-2 \
  --name "$PARAMETER" \
  --with-decryption \
  --output json \
  --no-cli-pager \
  > "$WORK_DIR/parameter.json" 2> "$WORK_DIR/aws-error"; then
  fail 'Parameter Store 조회 실패. 기존 파일을 유지합니다.'
fi

# 응답 하나에 완성된 env 파일이 들어 있어야 하며 CR과 NUL 문자는 허용하지 않는다.
if ! jq -se --arg name "$PARAMETER" '
  length == 1 and (.[0].Parameter |
    .Name == $name and .Type == "SecureString" and
    (.Value | type == "string" and length > 0 and (test("[\r\u0000]") | not)))
' "$WORK_DIR/parameter.json" >/dev/null 2>&1; then
  fail 'env 파라미터 응답 검증 실패. 기존 파일을 유지합니다.'
fi

jq -j '.Parameter.Value' "$WORK_DIR/parameter.json" > "$WORK_DIR/server.env"

# 파일은 실행하거나 치환하지 않는다. 검증용으로만 파싱하고 원문 그대로 설치한다.
if ! jq -Rse '
  def base64_byte_length:
    if test("^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
    then (length / 4 * 3) - (match("=*$").length)
    else -1 end;

  def positive_integer($max):
    test("^[1-9][0-9]*$") and
    ((length < ($max | length)) or
      (length == ($max | length) and . <= $max));

  split("\n") | map(select(test("^[ \t]*(#|$)") | not)) |
  if all(.[]; test("^[A-Za-z_][A-Za-z0-9_]*=")) then
    map(capture("^(?<key>[A-Za-z_][A-Za-z0-9_]*)=(?<value>.*)$"))
  else error("invalid env syntax") end |
  if ([.[].key] | length == (unique | length)) then
    from_entries
  else error("duplicate env key") end |
  . as $env |
  (["SERVER_PORT", "DB_URL", "DB_USERNAME", "DB_PASSWORD",
    "EMAIL_VERIFICATION_HASH_SECRET", "EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY",
    "SPRING_MAIL_PASSWORD", "SPRING_MAIL_HOST", "SPRING_MAIL_USERNAME",
    "EMAIL_VERIFICATION_MAIL_FROM", "EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS",
    "EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS",
    "LOGIN_JWT_SIGNING_KEY", "LOGIN_ALLOWED_ORIGINS"] |
    all(.[]; $env[.] | type == "string" and test("\\S"))) and
  (.SERVER_PORT == "8080") and
  (.EMAIL_VERIFICATION_HASH_SECRET | base64_byte_length >= 32) and
  (.EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY | base64_byte_length == 32) and
  (.EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS | positive_integer("2147483647")) and
  (.EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS | positive_integer("9223372036854775807")) and
  (.LOGIN_JWT_SIGNING_KEY | base64_byte_length >= 32) and
  (.LOGIN_ALLOWED_ORIGINS | split(",") |
    length > 0 and (length == (unique | length)) and
    all(.[]; test("^https://([A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?\\.)*[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(:[1-9][0-9]{0,4})?$") and
      (if test(":[0-9]+$") then (split(":") | last | tonumber) <= 65535 else true end)))
' "$WORK_DIR/server.env" >/dev/null 2>&1; then
  fail 'env 내용 검증 실패. 필수값, raw 구문, 중복 키와 값 형식을 확인하세요. 기존 파일을 유지합니다.'
fi

chmod 600 "$WORK_DIR/server.env"

# 모든 작업이 성공했을 때만 같은 파일시스템에서 기존 파일을 교체한다.
mv -f -- "$WORK_DIR/server.env" "$OUTPUT"

printf '완료: Spring 환경변수 파일 생성 (권한 600)\n'
