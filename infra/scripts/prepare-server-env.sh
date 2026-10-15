#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

TEMPLATE="$REPO_DIR/infra/env/.env.server.template"
OUTPUT="$REPO_DIR/infra/env/.env.server"
PREFIX="/bareum/dev/server"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

command -v aws >/dev/null || fail "AWS CLI가 필요합니다."
command -v jq >/dev/null || fail "jq가 필요합니다."
[[ -f "$TEMPLATE" ]] || fail "환경변수 템플릿이 없습니다."
[[ ! -d "$OUTPUT" ]] || fail "출력 경로가 디렉터리입니다."

WORK_DIR="$(mktemp -d "$REPO_DIR/infra/env/.env.prepare.XXXXXX")"
trap 'rm -rf -- "$WORK_DIR"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# 복호화된 값은 권한이 제한된 임시 파일에만 저장합니다.
if ! aws ssm get-parameters \
  --region ap-northeast-2 \
  --names \
    "$PREFIX/DB_URL" \
    "$PREFIX/DB_USERNAME" \
    "$PREFIX/DB_PASSWORD" \
    "$PREFIX/EMAIL_VERIFICATION_HASH_SECRET" \
    "$PREFIX/EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY" \
    "$PREFIX/SPRING_MAIL_PASSWORD" \
    "$PREFIX/SPRING_MAIL_HOST" \
    "$PREFIX/SPRING_MAIL_USERNAME" \
    "$PREFIX/EMAIL_VERIFICATION_MAIL_FROM" \
  --with-decryption \
  --output json \
  --no-cli-pager \
  > "$WORK_DIR/parameters.json"; then
  fail "Parameter Store 조회 실패. 기존 파일을 유지합니다."
fi

if ! jq -e --arg prefix "$PREFIX" '
  def base64_byte_length:
    if test("^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
    then (length / 4 * 3) - (match("=*$").length)
    else -1 end;

  (.InvalidParameters == []) and
  ([.Parameters[].Name] | sort ==
    ([$prefix + "/DB_URL",
      $prefix + "/DB_USERNAME",
      $prefix + "/DB_PASSWORD",
      $prefix + "/EMAIL_VERIFICATION_HASH_SECRET",
      $prefix + "/EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY",
      $prefix + "/SPRING_MAIL_PASSWORD",
      $prefix + "/SPRING_MAIL_HOST",
      $prefix + "/SPRING_MAIL_USERNAME",
      $prefix + "/EMAIL_VERIFICATION_MAIL_FROM"] | sort)) and
  all(.Parameters[];
    (.Value | type == "string") and
    (.Value | test("\\S")) and
    (.Value | test("[\r\n\u0000]") | not)) and
  all(.Parameters[] | select(
    .Name == ($prefix + "/DB_PASSWORD") or
    .Name == ($prefix + "/EMAIL_VERIFICATION_HASH_SECRET") or
    .Name == ($prefix + "/EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY") or
    .Name == ($prefix + "/SPRING_MAIL_PASSWORD"));
    .Type == "SecureString") and
  all(.Parameters[] | select(.Name == ($prefix + "/EMAIL_VERIFICATION_HASH_SECRET"));
    (.Value | base64_byte_length) >= 32) and
  all(.Parameters[] | select(.Name == ($prefix + "/EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY"));
    (.Value | base64_byte_length) == 32)
' "$WORK_DIR/parameters.json" >/dev/null 2>&1; then
  fail "파라미터 검증 실패. 기존 파일을 유지합니다."
fi

awk '!/^(DB_(URL|USERNAME|PASSWORD)|EMAIL_VERIFICATION_(HASH_SECRET|OUTBOX_ENCRYPTION_KEY|MAIL_FROM)|SPRING_MAIL_(HOST|USERNAME|PASSWORD))=/' "$TEMPLATE" \
  > "$WORK_DIR/server.env"

printf '\n' >> "$WORK_DIR/server.env"

jq -r '
  .Parameters
  | sort_by(.Name)[]
  | (.Name | split("/") | last) + "=" + .Value
' "$WORK_DIR/parameters.json" >> "$WORK_DIR/server.env"

chmod 600 "$WORK_DIR/server.env"

# 모든 작업이 성공했을 때만 기존 파일을 교체합니다.
mv -f -- "$WORK_DIR/server.env" "$OUTPUT"

printf '완료: Spring 환경변수 파일 생성 (권한 600)\n'
