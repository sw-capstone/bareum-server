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

PARAMETER_NAMES=(
  "$PREFIX/DB_URL"
  "$PREFIX/DB_USERNAME"
  "$PREFIX/DB_PASSWORD"
  "$PREFIX/EMAIL_VERIFICATION_HASH_SECRET"
  "$PREFIX/EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY"
  "$PREFIX/SPRING_MAIL_PASSWORD"
  "$PREFIX/SPRING_MAIL_HOST"
  "$PREFIX/SPRING_MAIL_USERNAME"
  "$PREFIX/EMAIL_VERIFICATION_MAIL_FROM"
  "$PREFIX/EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS"
  "$PREFIX/EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS"
)

# GetParameters는 한 번에 최대 10개만 조회합니다. 모든 배치를 확인한 뒤 파일을 교체합니다.
for ((offset=0; offset<${#PARAMETER_NAMES[@]}; offset+=10)); do
  names=("${PARAMETER_NAMES[@]:offset:10}")
  response="$WORK_DIR/batch-$offset.json"
  if ! aws ssm get-parameters \
    --region ap-northeast-2 \
    --names "${names[@]}" \
    --with-decryption \
    --output json \
    --no-cli-pager \
    > "$response" 2> "$WORK_DIR/aws-error"; then
    fail "Parameter Store 조회 실패. 기존 파일을 유지합니다."
  fi
  # 누락·중복·다른 배치의 값이 섞인 응답도 거부합니다.
  if ! jq -ne --slurpfile response "$response" --args '
    $response[0] |
    (.InvalidParameters == []) and
    ([.Parameters[].Name] | sort == ($ARGS.positional | sort))
  ' "${names[@]}" >/dev/null 2>&1; then
    fail "파라미터 조회 목록 검증 실패. 기존 파일을 유지합니다."
  fi
done

# 복호화된 값은 권한이 제한된 임시 파일에만 저장합니다.
jq -s '{InvalidParameters: [], Parameters: [.[].Parameters[]]}' \
  "$WORK_DIR"/batch-*.json > "$WORK_DIR/parameters.json"

if ! jq -e --arg prefix "$PREFIX" '
  def base64_byte_length:
    if test("^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
    then (length / 4 * 3) - (match("=*$").length)
    else -1 end;

  def positive_integer($max):
    test("^[1-9][0-9]*$") and
    ((length < ($max | length)) or
      (length == ($max | length) and . <= $max));

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
      $prefix + "/EMAIL_VERIFICATION_MAIL_FROM",
      $prefix + "/EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS",
      $prefix + "/EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS"] | sort)) and
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
    (.Value | base64_byte_length) == 32) and
  all(.Parameters[] | select(.Name == ($prefix + "/EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS"));
    .Type == "String" and (.Value | positive_integer("2147483647"))) and
  all(.Parameters[] | select(.Name == ($prefix + "/EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS"));
    .Type == "String" and (.Value | positive_integer("9223372036854775807")))
' "$WORK_DIR/parameters.json" >/dev/null 2>&1; then
  fail "파라미터 검증 실패. 기존 파일을 유지합니다."
fi

awk '!/^(DB_(URL|USERNAME|PASSWORD)|EMAIL_VERIFICATION_(HASH_SECRET|OUTBOX_ENCRYPTION_KEY|MAIL_FROM|MAX_VERIFICATION_ATTEMPTS|PASSWORD_RESET_VERIFICATION_TTL_SECONDS)|SPRING_MAIL_(HOST|USERNAME|PASSWORD))=/' "$TEMPLATE" \
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
