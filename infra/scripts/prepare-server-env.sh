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
  --with-decryption \
  --output json \
  --no-cli-pager \
  > "$WORK_DIR/parameters.json"; then
  fail "Parameter Store 조회 실패. 기존 파일을 유지합니다."
fi

if ! jq -e --arg prefix "$PREFIX" '
  (.InvalidParameters == []) and
  ([.Parameters[].Name] | sort ==
    ([$prefix + "/DB_URL",
      $prefix + "/DB_USERNAME",
      $prefix + "/DB_PASSWORD"] | sort)) and
  all(.Parameters[];
    (.Value | type == "string") and
    (.Value | length > 0) and
    (.Value | test("[\r\n\u0000]") | not)) and
  any(.Parameters[];
    .Name == ($prefix + "/DB_PASSWORD") and
    .Type == "SecureString")
' "$WORK_DIR/parameters.json" >/dev/null; then
  fail "파라미터 검증 실패. 기존 파일을 유지합니다."
fi

awk '!/^DB_(URL|USERNAME|PASSWORD)=/' "$TEMPLATE" \
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
