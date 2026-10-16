#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
TEMPLATE="$REPO_DIR/infra/env/.env.server.template"
OUTPUT="$REPO_DIR/infra/env/.env.server"
DEPLOY_ENV="$REPO_DIR/infra/env/.env.deploy"
PREFIX="/bareum/dev/server"
fail() { printf '%s\n' "$1" >&2; exit 1; }

[[ $# == 0 || ( $# == 1 && "$1" == --with-auth ) ]] \
  || fail 'Usage: prepare-server-env.sh [--with-auth]'
AUTH_ENABLED=false
if [[ -e "$DEPLOY_ENV" || -L "$DEPLOY_ENV" ]]; then
  [[ -f "$DEPLOY_ENV" && ! -L "$DEPLOY_ENV" ]] || fail '.env.deploy must be a regular file.'
  count=$(awk -F= '$1 == "AUTH_ENV_ENABLED" { n++ } END { print n+0 }' "$DEPLOY_ENV")
  (( count <= 1 )) || fail 'AUTH_ENV_ENABLED must appear at most once.'
  if (( count == 1 )); then
    AUTH_ENABLED=$(awk -F= '$1 == "AUTH_ENV_ENABLED" { print substr($0, index($0, "=")+1) }' "$DEPLOY_ENV")
    AUTH_ENABLED="${AUTH_ENABLED%$'\r'}"
  fi
fi
[[ "$AUTH_ENABLED" == true || "$AUTH_ENABLED" == false ]] || fail 'AUTH_ENV_ENABLED must be true or false.'
if [[ "${1:-}" == --with-auth ]]; then AUTH_ENABLED=true; fi
for tool in aws jq base64 wc; do
  command -v "$tool" >/dev/null || fail "Required command not found: $tool"
done
[[ -f "$TEMPLATE" && ! -L "$TEMPLATE" ]] || fail 'Environment template must be a regular file.'
[[ ! -L "$REPO_DIR/infra/env" ]] || fail 'Environment directory must not be a symlink.'
[[ ! -L "$OUTPUT" && ( ! -e "$OUTPUT" || -f "$OUTPUT" ) ]] \
  || fail 'Existing .env.server must be a regular file.'

BASE_KEYS=(
  DB_URL DB_USERNAME DB_PASSWORD
  EMAIL_VERIFICATION_HASH_SECRET EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY
  SPRING_MAIL_PASSWORD SPRING_MAIL_HOST SPRING_MAIL_USERNAME EMAIL_VERIFICATION_MAIL_FROM
)
AUTH_REQUIRED=(
  LOGIN_JWT_SIGNING_KEY
  SPRING_MAIL_PORT
  SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE
  SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED
  LOGIN_ALLOWED_ORIGINS
  EMAIL_VERIFICATION_MINIMUM_SEND_INTERVAL_SECONDS EMAIL_VERIFICATION_MAX_SENDS_PER_HOUR
  EMAIL_VERIFICATION_MAX_SENDS_PER_DAY EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS
  SIGNUP_TERMS_AGE_OVER_14_VERSION SIGNUP_TERMS_TERMS_OF_SERVICE_VERSION
  SIGNUP_TERMS_PRIVACY_POLICY_VERSION SIGNUP_TERMS_MARKETING_CONSENT_VERSION
)
AUTH_OPTIONAL=(
  GOOGLE_CLIENT_IDS GOOGLE_SIGNUP_TTL_SECONDS
  EMAIL_VERIFICATION_ATTEMPT_COOLDOWN_SECONDS
  EMAIL_VERIFICATION_PASSWORD_RESET_CODE_TTL_SECONDS
  EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS
)
SECRET_KEYS=(DB_PASSWORD LOGIN_JWT_SIGNING_KEY EMAIL_VERIFICATION_HASH_SECRET EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY SPRING_MAIL_PASSWORD)
KEYS=("${BASE_KEYS[@]}")
if [[ "$AUTH_ENABLED" == true ]]; then KEYS+=("${AUTH_REQUIRED[@]}" "${AUTH_OPTIONAL[@]}"); fi

WORK_DIR=$(mktemp -d "$REPO_DIR/infra/env/.env.prepare.XXXXXX")
cleanup() {
  [[ "$WORK_DIR" == "$REPO_DIR/infra/env/.env.prepare."* && -d "$WORK_DIR" && ! -L "$WORK_DIR" ]] \
    && rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
printf '%s\n' "${SECRET_KEYS[@]}" | jq -Rsc 'split("\n")[:-1]' > "$WORK_DIR/secret-keys.json"
printf '%s\n' "${AUTH_OPTIONAL[@]}" | jq -Rsc 'split("\n")[:-1]' > "$WORK_DIR/optional-keys.json"

# GetParameters accepts at most ten names. No recursive path reads or value logging.
for ((offset=0; offset<${#KEYS[@]}; offset+=10)); do
  names=()
  for key in "${KEYS[@]:offset:10}"; do names+=("$PREFIX/$key"); done
  printf '%s\n' "${names[@]}" | jq -Rsc 'split("\n")[:-1]' > "$WORK_DIR/expected.json"
  response="$WORK_DIR/batch-$offset.json"
  if ! aws ssm get-parameters --region ap-northeast-2 --names "${names[@]}" \
      --with-decryption --output json --no-cli-pager > "$response" 2> "$WORK_DIR/aws-error"; then
    fail 'Parameter Store lookup failed. Existing environment is unchanged.'
  fi
  if ! jq -e --arg prefix "$PREFIX" \
      --slurpfile expected "$WORK_DIR/expected.json" \
      --slurpfile secrets "$WORK_DIR/secret-keys.json" \
      --slurpfile optional "$WORK_DIR/optional-keys.json" '
    (.Parameters | type == "array") and (.InvalidParameters | type == "array") and
    (([.Parameters[].Name] + .InvalidParameters | sort) == ($expected[0] | sort)) and
    all(.InvalidParameters[]; . as $name | ($optional[0] | index($name | ltrimstr($prefix + "/"))) != null) and
    all(.Parameters[];
      (.Value | type == "string") and (.Value | test("\\S")) and
      (.Value | test("[\r\n\u0000]") | not) and
      (.Type == "String" or .Type == "SecureString") and
      (.Name as $name | if ($secrets[0] | index($name | ltrimstr($prefix + "/"))) != null
        then .Type == "SecureString" else true end))
  ' "$response" > /dev/null 2> "$WORK_DIR/json-error"; then
    fail 'Parameter validation failed. Existing environment is unchanged.'
  fi
done
jq -s '[.[].Parameters[]] | sort_by(.Name)' "$WORK_DIR"/batch-*.json > "$WORK_DIR/parameters.json"

if [[ "$AUTH_ENABLED" == true ]]; then
  if ! jq -e --arg prefix "$PREFIX/" '
    map({key: (.Name | ltrimstr($prefix)), value: .Value}) | from_entries |
    def positive($key): .[$key] | test("^[1-9][0-9]{0,9}$") and (tonumber <= 2147483647);
    positive("SPRING_MAIL_PORT") and (.SPRING_MAIL_PORT | tonumber <= 65535) and
    positive("EMAIL_VERIFICATION_MINIMUM_SEND_INTERVAL_SECONDS") and
    positive("EMAIL_VERIFICATION_MAX_SENDS_PER_HOUR") and
    positive("EMAIL_VERIFICATION_MAX_SENDS_PER_DAY") and
    positive("EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS") and
    (.SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH == "true") and
    ((.SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE == "true" and
      .SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE == "false" and
      .SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED == "false") or
     (.SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE == "false" and
      .SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE == "true" and
      .SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED == "true")) and
    (.SPRING_MAIL_HOST | test("^[A-Za-z0-9][A-Za-z0-9.-]*$")) and
    (.EMAIL_VERIFICATION_MAIL_FROM | test("^[^[:space:]<>@]+@[^[:space:]<>@]+$")) and
    (.LOGIN_ALLOWED_ORIGINS | split(",") | all(.[]; test("^https://[A-Za-z0-9][A-Za-z0-9.-]*(:[1-9][0-9]{0,4})?$"))) and
    ([.SIGNUP_TERMS_AGE_OVER_14_VERSION, .SIGNUP_TERMS_TERMS_OF_SERVICE_VERSION,
      .SIGNUP_TERMS_PRIVACY_POLICY_VERSION, .SIGNUP_TERMS_MARKETING_CONSENT_VERSION] |
      all(.[]; length <= 30 and test("[^[:space:]]") and (test("[<>]") | not))) and
    (if has("GOOGLE_CLIENT_IDS") then .GOOGLE_CLIENT_IDS | split(",") |
      all(.[]; test("^[A-Za-z0-9-]+\\.apps\\.googleusercontent\\.com$")) else true end) and
    (if has("GOOGLE_SIGNUP_TTL_SECONDS") then positive("GOOGLE_SIGNUP_TTL_SECONDS") and (.GOOGLE_SIGNUP_TTL_SECONDS | tonumber <= 600) else true end) and
    (if has("EMAIL_VERIFICATION_ATTEMPT_COOLDOWN_SECONDS") then
      positive("EMAIL_VERIFICATION_ATTEMPT_COOLDOWN_SECONDS") and
      (.EMAIL_VERIFICATION_ATTEMPT_COOLDOWN_SECONDS | tonumber <= 86400) else true end) and
    (has("EMAIL_VERIFICATION_PASSWORD_RESET_CODE_TTL_SECONDS") == has("EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS")) and
    (if has("EMAIL_VERIFICATION_PASSWORD_RESET_CODE_TTL_SECONDS") then
      positive("EMAIL_VERIFICATION_PASSWORD_RESET_CODE_TTL_SECONDS") and
      positive("EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS") else true end)
  ' "$WORK_DIR/parameters.json" >/dev/null 2> "$WORK_DIR/json-error"; then
    fail 'Auth configuration is incomplete or invalid. Existing environment is unchanged.'
  fi
fi

KEY_VALIDATIONS=(EMAIL_VERIFICATION_HASH_SECRET EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY)
if [[ "$AUTH_ENABLED" == true ]]; then KEY_VALIDATIONS+=(LOGIN_JWT_SIGNING_KEY); fi
for key in "${KEY_VALIDATIONS[@]}"; do
    value=$(jq -r --arg name "$PREFIX/$key" '.[] | select(.Name == $name) | .Value' "$WORK_DIR/parameters.json")
    [[ "$value" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || fail 'Invalid Auth encryption key.'
    printf '%s' "$value" | base64 --decode > "$WORK_DIR/key.bin" 2>/dev/null \
      || fail 'Invalid Auth encryption key.'
    size=$(wc -c < "$WORK_DIR/key.bin")
    if [[ "$key" != EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY ]]; then
      (( size >= 32 )) || fail 'Signing and HMAC keys must contain at least 32 bytes.'
    else
      (( size == 32 )) || fail 'Outbox key must contain exactly 32 bytes.'
    fi
    unset value
done

# Parameter Store owns these keys; discard template duplicates before appending raw values.
printf '%s\n' "${BASE_KEYS[@]}" > "$WORK_DIR/managed-keys"
if [[ "$AUTH_ENABLED" == true ]]; then
  printf '%s\n' "${AUTH_REQUIRED[@]}" "${AUTH_OPTIONAL[@]}" >> "$WORK_DIR/managed-keys"
else
  # Keep #18's SMTP template settings while dropping unrelated Auth overrides.
  printf '%s\n' "${AUTH_REQUIRED[@]}" "${AUTH_OPTIONAL[@]}" \
    | awk '$0 !~ /^SPRING_MAIL_/' >> "$WORK_DIR/managed-keys"
fi
# An explicit SSM policy replaces its old direct-binding alias, avoiding two values
# for the same Spring property. Unchanged #18 aliases remain in email-only mode.
while read -r key alias; do
  if jq -e --arg name "$PREFIX/$key" 'any(.[]; .Name == $name)' \
      "$WORK_DIR/parameters.json" >/dev/null; then
    printf '%s\n' "$alias" >> "$WORK_DIR/managed-keys"
  fi
done <<'ALIASES'
EMAIL_VERIFICATION_MINIMUM_SEND_INTERVAL_SECONDS AUTH_EMAILVERIFICATION_MINIMUMSENDINTERVALSECONDS
EMAIL_VERIFICATION_MAX_SENDS_PER_HOUR AUTH_EMAILVERIFICATION_MAXSENDSPERHOUR
EMAIL_VERIFICATION_MAX_SENDS_PER_DAY AUTH_EMAILVERIFICATION_MAXSENDSPERDAY
EMAIL_VERIFICATION_PASSWORD_RESET_CODE_TTL_SECONDS AUTH_EMAILVERIFICATION_PASSWORDRESETCODETTLSECONDS
ALIASES
awk 'NR == FNR { managed[$0]=1; next }
  { split($0, parts, "="); if (!(parts[1] in managed)) print }' \
  "$WORK_DIR/managed-keys" "$TEMPLATE" > "$WORK_DIR/server.env"
printf '\n' >> "$WORK_DIR/server.env"
jq -r '.[] | (.Name | split("/") | last) + "=" + .Value' \
  "$WORK_DIR/parameters.json" >> "$WORK_DIR/server.env"
chmod 600 "$WORK_DIR/server.env"
mv -f -- "$WORK_DIR/server.env" "$OUTPUT"
printf 'Environment prepared (permissions 600, auth=%s).\n' "$AUTH_ENABLED"
