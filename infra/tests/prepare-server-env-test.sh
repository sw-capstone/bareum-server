#!/usr/bin/env bash
set -euo pipefail
SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf -- "$WORK"' EXIT
mkdir "$WORK/bin"
export PATH="$WORK/bin:$PATH"
PREFIX=/bareum/dev/server

cat > "$WORK/bin/aws" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == ssm && "$2" == get-parameters ]]
printf '%s\n' "$@" >> "$CASE_ROOT/aws-args"
printf 'call\n' >> "$CASE_ROOT/aws-calls"
[[ "$MODE" != aws-fail ]] || exit 1
names=()
collect=false
for arg in "$@"; do
  case "$arg" in
    --names) collect=true ;;
    --*) collect=false ;;
    *) if [[ "$collect" == true ]]; then names+=("$arg"); fi ;;
  esac
done
(( ${#names[@]} > 0 && ${#names[@]} <= 10 ))
if [[ $(wc -l < "$CASE_ROOT/aws-calls") -gt 1 ]]; then
  [[ "$MODE" != late-aws-fail ]] || exit 1
  if [[ "$MODE" == late-malformed-response ]]; then printf 'invalid-json'; exit 0; fi
fi
jq -n --slurpfile response "$CASE_ROOT/response.json" --args '
  $response[0] |
  $ARGS.positional as $names |
  .Parameters |= map(select(.Name as $name | $names | index($name))) |
  if env.MODE == "unexpected" then
    .Parameters += [{Name: "/bareum/dev/server/UNKNOWN", Value: "unexpected", Type: "String"}]
  elif env.MODE == "cross-batch-response" then
    .Parameters[0].Name = "/bareum/dev/server/DB_URL"
  else . end
' "${names[@]}"
MOCK
chmod +x "$WORK/bin/aws"

jq -n --arg prefix "$PREFIX" '
  {
    InvalidParameters: [],
    Parameters: ([
      {Name: "DB_URL", Value: "jdbc:postgresql://fixture.invalid:5432/bareum?sslmode=verify-full", Type: "String"},
      {Name: "DB_USERNAME", Value: "fixture-user", Type: "String"},
      {Name: "DB_PASSWORD", Value: "fixture-secret-$dollar#hash=equals\"quote\\slash", Type: "SecureString"},
      {Name: "EMAIL_VERIFICATION_HASH_SECRET", Value: ("h" * 32 | @base64), Type: "SecureString"},
      {Name: "EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY", Value: ("e" * 32 | @base64), Type: "SecureString"},
      {Name: "SPRING_MAIL_PASSWORD", Value: "fixture-secret-mail-$dollar#hash=equals\"quote\\slash", Type: "SecureString"},
      {Name: "SPRING_MAIL_HOST", Value: "smtp.fixture.invalid", Type: "String"},
      {Name: "SPRING_MAIL_USERNAME", Value: "sender@fixture.invalid", Type: "String"},
      {Name: "EMAIL_VERIFICATION_MAIL_FROM", Value: "sender@fixture.invalid", Type: "String"},
      {Name: "EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS", Value: "5", Type: "String"},
      {Name: "EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS", Value: "600", Type: "String"},
      {Name: "LOGIN_JWT_SIGNING_KEY", Value: ("j" * 32 | @base64), Type: "SecureString"},
      {Name: "LOGIN_ALLOWED_ORIGINS", Value: "https://bareum.site,https://bareum-web-git-develop-numkites-projects.vercel.app", Type: "String"}
    ] | map(.Name = ($prefix + "/" + .Name)))
  }
' > "$WORK/baseline.json"

setup_case() {
  export MODE="$1" CASE_ROOT="$WORK/$1"
  mkdir -p "$CASE_ROOT/infra/scripts" "$CASE_ROOT/infra/env"
  cp "$SOURCE_DIR/scripts/prepare-server-env.sh" "$CASE_ROOT/infra/scripts/"
  cp "$SOURCE_DIR/env/.env.server.template" "$CASE_ROOT/infra/env/"
  cp "$WORK/baseline.json" "$CASE_ROOT/response.json"
  printf 'EXISTING_ENV=preserve-on-failure\n' > "$CASE_ROOT/original.env"
  cp "$CASE_ROOT/original.env" "$CASE_ROOT/infra/env/.env.server"
}

run_case() {
  RESULT=0
  bash "$CASE_ROOT/infra/scripts/prepare-server-env.sh" > "$CASE_ROOT/output" 2>&1 || RESULT=$?
  if grep -q 'fixture-secret' "$CASE_ROOT/output"; then
    printf 'FAIL: secret appeared in output (%s)\n' "$MODE" >&2
    exit 1
  fi
  while IFS= read -r secret; do
    if grep -Fq -- "$secret" "$CASE_ROOT/output"; then
      printf 'FAIL: key appeared in output (%s)\n' "$MODE" >&2
      exit 1
    fi
  done < <(jq -r '.Parameters[] | select(.Name | test("(HASH_SECRET|ENCRYPTION_KEY|JWT_SIGNING_KEY)$")) | .Value' "$WORK/baseline.json")
  [[ -z "$(find "$CASE_ROOT/infra/env" -name '.env.prepare.*' -print)" ]]
}

expect_failure() {
  [[ "$RESULT" != 0 ]]
  cmp "$CASE_ROOT/original.env" "$CASE_ROOT/infra/env/.env.server"
  printf 'PASS: %s; previous environment preserved\n' "$MODE"
}

expect_success() {
  if [[ "$RESULT" != 0 ]]; then
    cat "$CASE_ROOT/output" >&2
    return 1
  fi
  local generated="$CASE_ROOT/infra/env/.env.server" mode
  mode=$(stat -c %a "$generated" 2>/dev/null || stat -f %Lp "$generated")
  [[ "$mode" == 600 ]] || { printf 'FAIL: expected mode 600, got %s\n' "$mode" >&2; return 1; }
  jq -r '.Parameters[] | (.Name | split("/") | last) + "=" + .Value' \
    "$CASE_ROOT/response.json" > "$CASE_ROOT/expected.env"
  while IFS= read -r entry; do grep -Fxq -- "$entry" "$generated"; done < "$CASE_ROOT/expected.env"
  awk -F= '
    /^[[:space:]]*(#|$)/ { next }
    { if (seen[$1]++) exit 1 }
  ' "$generated"
  while IFS= read -r entry; do
    case "$entry" in ''|\#*) continue ;; esac
    grep -Fxq -- "$entry" "$generated"
  done < "$SOURCE_DIR/env/.env.server.template"
  printf 'PASS: %s; raw values, template settings and permissions verified\n' "$MODE"
}

setup_case success
run_case
expect_success
awk '
  $0 == "--names" { names=1; next }
  $0 == "--with-decryption" { names=0 }
  names { print }
' "$CASE_ROOT/aws-args" | sort > "$CASE_ROOT/requested-names"
jq -r '.Parameters[].Name' "$WORK/baseline.json" | sort > "$CASE_ROOT/expected-names"
cmp "$CASE_ROOT/expected-names" "$CASE_ROOT/requested-names"
grep -Fxq -- '--with-decryption' "$CASE_ROOT/aws-args"
grep -Fxq -- 'ap-northeast-2' "$CASE_ROOT/aws-args"
[[ $(wc -l < "$CASE_ROOT/aws-calls") == 2 ]]
printf 'PASS: thirteen parameters fetched in two batches of at most ten\n'

setup_case stale-template
jq -r '.Parameters[] | (.Name | split("/") | last) + "=stale-template-value"' \
  "$WORK/baseline.json" >> "$CASE_ROOT/infra/env/.env.server.template"
run_case
expect_success

setup_case longer-hmac-key
jq '(.Parameters[] | select(.Name | endswith("/EMAIL_VERIFICATION_HASH_SECRET"))).Value = ("h" * 48 | @base64)' \
  "$WORK/baseline.json" > "$CASE_ROOT/response.json"
run_case
expect_success

while IFS= read -r name; do
  setup_case "missing-${name##*/}"
  jq --arg name "$name" '.Parameters |= map(select(.Name != $name))' \
    "$WORK/baseline.json" > "$CASE_ROOT/response.json"
  run_case
  expect_failure
done < <(jq -r '.Parameters[].Name' "$WORK/baseline.json")

for key in DB_PASSWORD EMAIL_VERIFICATION_HASH_SECRET EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY SPRING_MAIL_PASSWORD LOGIN_JWT_SIGNING_KEY; do
  setup_case "plaintext-$key"
  jq --arg name "$PREFIX/$key" '(.Parameters[] | select(.Name == $name)).Type = "String"' \
    "$WORK/baseline.json" > "$CASE_ROOT/response.json"
  run_case
  expect_failure
done

for scenario in empty whitespace newline carriage-return nul non-string short-hmac short-outbox long-outbox bad-hmac-base64 bad-outbox-padding duplicate unexpected invalid-parameters; do
  setup_case "$scenario"
  case "$scenario" in
    empty) filter='.Parameters[2].Value = ""' ;;
    whitespace) filter='.Parameters[2].Value = "   "' ;;
    newline) filter='.Parameters[2].Value = "fixture-secret\ninjected"' ;;
    carriage-return) filter='.Parameters[2].Value = "fixture-secret\rinjected"' ;;
    nul) filter='.Parameters[2].Value = "fixture-secret\u0000injected"' ;;
    non-string) filter='.Parameters[2].Value = null' ;;
    short-hmac) filter='.Parameters[3].Value = ("h" * 31 | @base64)' ;;
    short-outbox) filter='.Parameters[4].Value = ("e" * 31 | @base64)' ;;
    long-outbox) filter='.Parameters[4].Value = ("e" * 33 | @base64)' ;;
    bad-hmac-base64) filter='.Parameters[3].Value = ("!" * 44)' ;;
    bad-outbox-padding) filter='.Parameters[4].Value = (("A" * 42) + "=A")' ;;
    duplicate) filter='.Parameters += [.Parameters[0]]' ;;
    unexpected) filter='.Parameters += [{Name: "/bareum/dev/server/UNKNOWN", Value: "unexpected", Type: "String"}]' ;;
    invalid-parameters) filter='.InvalidParameters = ["/bareum/dev/server/UNKNOWN"]' ;;
  esac
  jq "$filter" "$WORK/baseline.json" > "$CASE_ROOT/response.json"
  run_case
  expect_failure
done


for key in EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS; do
  for value in 0 -1 1.5 1e2 abc 99999999999999999999; do
    setup_case "invalid-$key-$value"
    jq --arg name "$PREFIX/$key" --arg value "$value" \
      '(.Parameters[] | select(.Name == $name)).Value = $value' \
      "$WORK/baseline.json" > "$CASE_ROOT/response.json"
    run_case
    expect_failure
  done
  setup_case "secure-policy-$key"
  jq --arg name "$PREFIX/$key" '(.Parameters[] | select(.Name == $name)).Type = "SecureString"' \
    "$WORK/baseline.json" > "$CASE_ROOT/response.json"
  run_case
  expect_failure
done

setup_case integer-overflow
jq '(.Parameters[] | select(.Name | endswith("/EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS"))).Value = "2147483648"' \
  "$WORK/baseline.json" > "$CASE_ROOT/response.json"
run_case
expect_failure

setup_case long-overflow
jq '(.Parameters[] | select(.Name | endswith("/EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS"))).Value = "9223372036854775808"' \
  "$WORK/baseline.json" > "$CASE_ROOT/response.json"
run_case
expect_failure

setup_case longer-jwt-key
jq '(.Parameters[] | select(.Name | endswith("/LOGIN_JWT_SIGNING_KEY"))).Value = ("j" * 48 | @base64)' \
  "$WORK/baseline.json" > "$CASE_ROOT/response.json"
run_case
expect_success

for scenario in short-jwt invalid-jwt-base64 invalid-jwt-padding; do
  setup_case "$scenario"
  case "$scenario" in
    short-jwt) value='"j" * 31 | @base64' ;;
    invalid-jwt-base64) value='"!" * 44' ;;
    invalid-jwt-padding) value='("A" * 42) + "=A"' ;;
  esac
  jq "(.Parameters[] | select(.Name | endswith(\"/LOGIN_JWT_SIGNING_KEY\"))).Value = ($value)" \
    "$WORK/baseline.json" > "$CASE_ROOT/response.json"
  run_case
  expect_failure
done

origin_case=0
for value in '*' 'null' 'http://bareum.site' 'https://bareum.site/' 'https://bareum.site/path' \
  'https://bareum.site?query=1' 'https://user@bareum.site' 'https://bareum.site#fragment' \
  'https://bareum.site,' ',https://bareum.site' 'https://bareum.site, https://web.example.invalid' \
  'https://bareum.site,https://bareum.site' 'https://bareum.site:65536' 'https://bareum..site' 'https://-bareum.site'; do
  origin_case=$((origin_case+1))
  setup_case "invalid-origin-$origin_case"
  jq --arg value "$value" '(.Parameters[] | select(.Name | endswith("/LOGIN_ALLOWED_ORIGINS"))).Value = $value' \
    "$WORK/baseline.json" > "$CASE_ROOT/response.json"
  run_case
  expect_failure
done

setup_case secure-origins
jq '(.Parameters[] | select(.Name | endswith("/LOGIN_ALLOWED_ORIGINS"))).Type = "SecureString"' \
  "$WORK/baseline.json" > "$CASE_ROOT/response.json"
run_case
expect_failure

for scenario in late-aws-fail late-malformed-response cross-batch-response; do
  setup_case "$scenario"
  run_case
  expect_failure
done

setup_case malformed-response
printf '{"fixture-secret": invalid-json' > "$CASE_ROOT/response.json"
run_case
expect_failure

setup_case aws-fail
run_case
expect_failure

setup_case first-run
rm "$CASE_ROOT/infra/env/.env.server"
run_case
expect_success

setup_case aws-fail
rm "$CASE_ROOT/infra/env/.env.server"
run_case
[[ "$RESULT" != 0 && ! -e "$CASE_ROOT/infra/env/.env.server" ]]
printf 'PASS: first-run failure leaves no environment file\n'
printf 'All environment generation tests passed. Parameter Store calls were simulated.\n'
