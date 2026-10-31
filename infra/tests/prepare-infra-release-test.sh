#!/usr/bin/env bash
set -Eeuo pipefail
SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf -- "$WORK"' EXIT
REAL_DOCKER=$(command -v docker)
export REAL_DOCKER
mkdir -p "$WORK/bin" "$WORK/source"
export PATH="$WORK/bin:$PATH" FIXTURE_SOURCE="$WORK/source"
export EXPECTED_COMMIT=0123456789abcdef0123456789abcdef01234567
IMAGE="ghcr.io/sw-capstone/bareum-server@sha256:$(printf '%064d' 2)"
OLD_IMAGE="sha256:$(printf '%064d' 1)"
export OLD_IMAGE
FILES=(docker-compose.yml nginx/default.conf scripts/deploy.sh scripts/prepare-server-env.sh scripts/prepare-ai-env.sh
  env/.env.server.template scripts/reload-nginx-after-cert-renewal.sh)
for file in "${FILES[@]}"; do
  mkdir -p "$FIXTURE_SOURCE/$(dirname -- "$file")"
  cp "$SOURCE_DIR/$file" "$FIXTURE_SOURCE/$file"
done

cat > "$WORK/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >> "$CASE_ROOT/curl-calls"
while [[ "$1" != https://* ]]; do shift; done
prefix="https://raw.githubusercontent.com/sw-capstone/bareum-server/$EXPECTED_COMMIT/infra/"
[[ "$1" == "$prefix"* && "$2" == --output ]]
file="${1#"$prefix"}"
if [[ "$file" == env/.env.server.template ]]; then
  case "$MODE" in
    download-fail) exit 22 ;;
    empty-file) : > "$3"; exit 0 ;;
    interrupted) kill -TERM "$PPID"; exit 1 ;;
  esac
fi
cp "$FIXTURE_SOURCE/$file" "$3"
if [[ "$file" == docker-compose.yml ]]; then
  awk -v certs="$LETSENCRYPT_DIR" -v webroot="$BAREUM_ROOT/webroot" '
    /source:/ { gsub("/etc/letsencrypt", certs); gsub("/var/www/certbot", webroot) }
    { print }
  ' "$3" > "$3.next"
  mv "$3.next" "$3"
fi
case "$MODE:$file" in
  bad-shell:scripts/deploy.sh|bad-hook:scripts/reload-nginx-after-cert-renewal.sh) printf '\nif then\n' >> "$3" ;;
  bad-template:env/.env.server.template) printf '\nSERVER_PORT=9999\n' >> "$3" ;;
  bad-compose:docker-compose.yml) printf '\nservices: [\n' >> "$3" ;;
  bad-nginx:nginx/default.conf) printf '\ninvalid_directive;\n' >> "$3" ;;
  apply-*:scripts/deploy.sh)
    cat > "$3" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ $# == 3 && "$1" == server && "$2" == ghcr.io/sw-capstone/bareum-server@sha256:* ]]
[[ "$0" == "$3/staged/infra/scripts/deploy.sh" && "$(cat "$3/status")" == validated ]]
if flock -n "$BAREUM_ROOT/.actions-deploy.lock" true; then exit 90; fi
flock -n "$BAREUM_ROOT/infra/.deploy-state/lock" true
touch "$CASE_ROOT/applied"
if [[ "$MODE" == apply-fail ]]; then exit 7; fi
SCRIPT
    ;;
esac
MOCK
cat > "$WORK/bin/aws" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == ssm && "$2" == get-parameter ]]
if [[ "$MODE" == ai-env-fail ]]; then echo fixture-secret >&2; exit 1; fi
value=$'BAREUM_AI_INTERNAL_API_TOKEN=fixture-secret\nBAREUM_AI_S3_ALLOWED_HOST=fixture.invalid\nBAREUM_AI_S3_ALLOWED_KEY_PREFIX=reports/\n'
if [[ "$MODE" == missing-ai-token ]]; then value=$'BAREUM_AI_S3_ALLOWED_HOST=fixture.invalid\nBAREUM_AI_S3_ALLOWED_KEY_PREFIX=reports/\n'; fi
jq -n --arg value "$value" '{Parameter:{Name:"/bareum/dev/ai/env",Type:"SecureString",Value:$value}}'
MOCK
cat > "$WORK/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >> "$CASE_ROOT/docker-calls"
if [[ "${TEST_REAL_VALIDATION:-0}" == 1 && ( "$1" == compose || "$1" == run || "$1" == rm ) ]]; then
  exec "$REAL_DOCKER" "$@"
fi
case "$1" in
  compose)
    [[ "$*" == *"--project-directory $BAREUM_ROOT/infra"* && "$*" == *'config --format json'* ]]
    [[ "$*" == *'/staged/infra/docker-compose.yml'* ]]
    [[ -z "${HTTP_PORT+x}" && -z "${HTTPS_PORT+x}" && -z "${RDS_CA_FILE+x}" ]]
    [[ "$SERVER_IMAGE" == ghcr.io/sw-capstone/bareum-server@sha256:* ]]
    [[ -f "$AI_ENV_FILE" && "$AI_ENV_FILE" == */.preparing.*/ai.env ]]
    if [[ "$MODE" == bad-compose ]]; then echo fixture-secret >&2; exit 1; fi
    jq -n --arg root "$BAREUM_ROOT" --arg certs "$LETSENCRYPT_DIR" '
      {services:{server:{volumes:[{type:"bind",source:($root+"/infra/certs/rds-ca.pem"),target:"/etc/ssl/certs/rds-ca.pem"}]},ai:{},
       nginx:{image:"nginx:1.30-alpine",volumes:[
        {type:"bind",source:($root+"/infra/nginx/default.conf"),target:"/etc/nginx/conf.d/default.conf",read_only:true},
        {type:"bind",source:$certs,target:"/etc/letsencrypt",read_only:true}]}}}'
    ;;
  run)
    [[ "$*" == *'--network none --entrypoint nginx'* && "$*" == *'/staged/infra/nginx/default.conf'* ]]
    [[ "$*" != *' --publish '* && "$*" != *' -p '* && "$*" == *'readonly'* && "${!#}" == -t ]]
    if [[ "$MODE" == bad-nginx ]]; then echo fixture-secret >&2; exit 1; fi
    ;;
  rm) [[ "$*" == 'rm -f bareum-infra-check-'* ]] ;;
  ps)
    service="${!#}"
    service="${service##*=}"
    [[ "$*" == *label=com.docker.compose.project=bareum* ]]
    [[ "$MODE" != missing-server || "$service" != server ]] || exit 0
    [[ "$MODE" != missing-ai || "$service" != ai ]] || exit 0
    printf '%s-container\n' "$service"
    if [[ "$MODE" == duplicate-container ]]; then echo other-container; fi
    ;;
  inspect)
    [[ "$MODE" != inspect-fail ]] || exit 1
    case "$3" in
      '{{.Image}}') printf '%s\n' "$OLD_IMAGE" ;;
      '{{.State.Running}}') echo true ;;
      *) exit 91 ;;
    esac
    ;;
  *) exit 90 ;;
esac
MOCK
chmod +x "$WORK/bin/curl" "$WORK/bin/docker" "$WORK/bin/aws"

setup_case() {
  export MODE="$1" CASE_ROOT="$WORK/$1"
  export BAREUM_ROOT="$CASE_ROOT/bareum" LETSENCRYPT_DIR="$CASE_ROOT/letsencrypt"
  mkdir -p "$BAREUM_ROOT/infra" "$BAREUM_ROOT/webroot" "$LETSENCRYPT_DIR/renewal-hooks/deploy" "$LETSENCRYPT_DIR/live"
  for file in "${FILES[@]}"; do
    mkdir -p "$BAREUM_ROOT/infra/$(dirname -- "$file")"
    printf 'old %s\n' "$file" > "$BAREUM_ROOT/infra/$file"
    chmod 644 "$BAREUM_ROOT/infra/$file"
  done
  chmod 755 "$BAREUM_ROOT/infra/scripts/deploy.sh"
  printf 'old hook\n' > "$LETSENCRYPT_DIR/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh"
  printf 'SERVER_IMAGE=old\nAI_IMAGE=unchanged\nRDS_CA_FILE=./certs/rds-ca.pem\n' > "$BAREUM_ROOT/infra/env/.env.deploy"
  printf 'DB_PASSWORD=fixture-secret\n' > "$BAREUM_ROOT/infra/env/.env.server"
  printf 'AI_SECRET=fixture-secret\n' > "$BAREUM_ROOT/infra/env/.env.ai"
  printf 'private-key-fixture-secret\n' > "$LETSENCRYPT_DIR/live/privkey.pem"
  mkdir "$BAREUM_ROOT/infra/certs"
  printf 'rds-ca-fixture\n' > "$BAREUM_ROOT/infra/certs/rds-ca.pem"
  chmod 600 "$BAREUM_ROOT/infra/env/.env."*
  if [[ "${TEST_REAL_VALIDATION:-0}" == 1 ]]; then
    mkdir -p "$LETSENCRYPT_DIR/live/api.bareum.site"
    openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=api.bareum.site \
      -keyout "$LETSENCRYPT_DIR/live/api.bareum.site/privkey.pem" \
      -out "$LETSENCRYPT_DIR/live/api.bareum.site/fullchain.pem" >/dev/null 2>&1
  fi
}

save_baseline() {
  find "$BAREUM_ROOT/infra" "$LETSENCRYPT_DIR" -type f ! -path '*/.deploy-state/*' -print0 \
    | sort -z | xargs -0 sha256sum > "$CASE_ROOT/live.sha256"
}

run_case() {
  RESULT=0
  bash "$SOURCE_DIR/scripts/prepare-infra-release.sh" "${1:-$EXPECTED_COMMIT}" "${2:-$IMAGE}" \
    > "$CASE_ROOT/output" 2>&1 || RESULT=$?
  sha256sum --check --status "$CASE_ROOT/live.sha256"
  if grep -q fixture-secret "$CASE_ROOT/output"; then
    echo 'FAIL: secret appeared in output' >&2; exit 1
  fi
}

assert_failure() {
  [[ "$RESULT" != 0 ]]
  if [[ -d "$BAREUM_ROOT/infra/.deploy-state/releases" ]]; then
    [[ -z "$(ls -A "$BAREUM_ROOT/infra/.deploy-state/releases")" ]]
  fi
}

if [[ "${TEST_REAL_VALIDATION:-0}" == 1 ]]; then
  for mode in real-success bootstrap bad-compose bad-nginx missing-cert; do
    setup_case "$mode"
    if [[ "$mode" == bootstrap ]]; then rm "$BAREUM_ROOT/infra/env/.env.ai"; fi
    if [[ "$mode" == missing-cert ]]; then rm "$LETSENCRYPT_DIR/live/api.bareum.site/fullchain.pem"; fi
    save_baseline
    run_case
    if [[ "$mode" == real-success || "$mode" == bootstrap ]]; then
      if [[ "$RESULT" != 0 ]]; then cat "$CASE_ROOT/output" >&2; exit 1; fi
    else
      assert_failure
    fi
    printf 'PASS: real Docker Compose/Nginx validation: %s\n' "$mode"
  done
  exit 0
fi

setup_case success
save_baseline
run_case
[[ "$RESULT" == 0 ]]
release=$(sed -n 's/^Release prepared: //p' "$CASE_ROOT/output")
[[ -d "$release" && "$(cat "$release/status")" == validated ]]
[[ -s "$release/validation.txt" && ! -e "$release/compose.json" && ! -e "$release/nginx-check.log" ]]
[[ "$(cat "$release/commit-sha")" == "$EXPECTED_COMMIT" && "$(cat "$release/server-image")" == "$IMAGE" ]]
[[ "$(stat -c %a "$release")" == 700 && "$(stat -c %a "$release/backup/infra/env/.env.server")" == 600 ]]
[[ "$(wc -l < "$CASE_ROOT/curl-calls")" == 7 ]]
(cd "$release/staged/infra" && sha256sum --check --status ../../staged.sha256)
for file in "${FILES[@]}"; do
  if [[ "$file" != docker-compose.yml ]]; then cmp "$FIXTURE_SOURCE/$file" "$release/staged/infra/$file"; fi
  [[ "$file" == scripts/reload-nginx-after-cert-renewal.sh ]] && continue
  cmp "$BAREUM_ROOT/infra/$file" "$release/backup/infra/$file"
done
cmp "$LETSENCRYPT_DIR/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh" "$release/backup/certbot/reload-nginx-after-cert-renewal.sh"
cmp "$BAREUM_ROOT/infra/env/.env.deploy" "$release/backup/infra/env/.env.deploy"
cmp "$BAREUM_ROOT/infra/env/.env.server" "$release/backup/infra/env/.env.server"
grep -Fxq $'infra/scripts/deploy.sh\tpresent\t755' "$release/backup/files.tsv"
grep -Fxq "$(printf 'server\tserver-container\t%s\ttrue' "$OLD_IMAGE")" "$release/backup/images.tsv"
[[ "$(wc -l < "$release/backup/images.tsv")" == 3 ]]
cmp "$BAREUM_ROOT/infra/env/.env.ai" "$release/backup/infra/env/.env.ai"
[[ ! -e "$release/backup/live" && "$(stat -c %a "$release/ai.env")" == 600 ]]
cp "$release/backup/images.tsv" "$CASE_ROOT/first-images"
run_case
[[ "$RESULT" == 0 ]]
second_release=$(sed -n 's/^Release prepared: //p' "$CASE_ROOT/output")
[[ "$release" != "$second_release" ]]
cmp "$CASE_ROOT/first-images" "$release/backup/images.tsv"
printf 'PASS: pinned files, restricted backups, image state, unchanged live files and separate retries\n'

setup_case bootstrap
rm "$BAREUM_ROOT/infra/env/.env.ai"
save_baseline
run_case
[[ "$RESULT" == 0 && ! -e "$BAREUM_ROOT/infra/env/.env.ai" ]]
release=$(sed -n 's/^Release prepared: //p' "$CASE_ROOT/output")
grep -Fxq $'infra/env/.env.ai\tmissing\t-' "$release/backup/files.tsv"
grep -q '^BAREUM_AI_INTERNAL_API_TOKEN=' "$release/ai.env"
printf 'PASS: absent AI environment generated for candidate Compose without changing live files\n'

setup_case missing-optional
rm "$BAREUM_ROOT/infra/env/.env.server.template" "$LETSENCRYPT_DIR/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh"
save_baseline
run_case
[[ "$RESULT" == 0 ]]
release=$(sed -n 's/^Release prepared: //p' "$CASE_ROOT/output")
grep -Fxq $'infra/env/.env.server.template\tmissing\t-' "$release/backup/files.tsv"
grep -Fxq $'certbot/reload-nginx-after-cert-renewal.sh\tmissing\t-' "$release/backup/files.tsv"
[[ ! -e "$BAREUM_ROOT/infra/env/.env.server.template" ]]
printf 'PASS: missing old template and hook recorded without installation\n'

setup_case missing-ai
save_baseline
run_case
[[ "$RESULT" == 0 ]]
release=$(sed -n 's/^Release prepared: //p' "$CASE_ROOT/output")
grep -Fxq $'ai\tmissing\t-\t-' "$release/backup/images.tsv"
printf 'PASS: absent optional AI container recorded\n'

for mode in download-fail empty-file interrupted missing-env inspect-fail missing-server duplicate-container symlink-file; do
  setup_case "$mode"
  if [[ "$mode" == missing-env ]]; then rm "$BAREUM_ROOT/infra/env/.env.deploy"; fi
  if [[ "$mode" == symlink-file ]]; then
    rm "$BAREUM_ROOT/infra/nginx/default.conf"
    ln -s "$BAREUM_ROOT/infra/env/.env.server" "$BAREUM_ROOT/infra/nginx/default.conf"
  fi
  save_baseline
  run_case
  assert_failure
  printf 'PASS: %s leaves live files intact and removes incomplete preparation\n' "$mode"
done

for mode in bad-shell bad-hook bad-template ai-env-fail missing-ai-token bad-compose missing-ca bad-nginx; do
  setup_case "$mode"
  if [[ "$mode" == missing-ca ]]; then rm "$BAREUM_ROOT/infra/certs/rds-ca.pem"; fi
  save_baseline
  run_case
  assert_failure
  if [[ "$mode" == bad-nginx ]]; then
    grep -q '^rm -f bareum-infra-check-' "$CASE_ROOT/docker-calls"
  elif grep -q '^run ' "$CASE_ROOT/docker-calls"; then
    echo 'FAIL: Nginx check ran despite earlier validation failure' >&2; exit 1
  fi
  printf 'PASS: %s rejected before installation without logging secrets\n' "$mode"
done

setup_case inherited-environment
save_baseline
HTTP_PORT=invalid HTTPS_PORT=invalid RDS_CA_FILE=/invalid run_case
[[ "$RESULT" == 0 ]]
printf 'PASS: caller environment cannot override deployment configuration\n'

for lock in actions manual; do
  setup_case "locked-$lock"
  mkdir -p "$BAREUM_ROOT/infra/.deploy-state"
  lock_file="$BAREUM_ROOT/.actions-deploy.lock"
  if [[ "$lock" == manual ]]; then lock_file="$BAREUM_ROOT/infra/.deploy-state/lock"; fi
  save_baseline
  (
    exec 7>"$lock_file"
    flock -n 7
    run_case
    assert_failure
    [[ ! -e "$CASE_ROOT/curl-calls" && ! -e "$CASE_ROOT/docker-calls" ]]
  )
  printf 'PASS: existing %s deployment lock blocks preparation\n' "$lock"
done

setup_case pending
mkdir -p "$BAREUM_ROOT/infra/.deploy-state/pending"
save_baseline
run_case
assert_failure
[[ ! -e "$CASE_ROOT/curl-calls" && ! -e "$CASE_ROOT/docker-calls" ]]
printf 'PASS: unresolved rollback blocks preparation\n'

setup_case invalid-input
save_baseline
run_case develop
assert_failure
run_case "$EXPECTED_COMMIT" ghcr.io/sw-capstone/bareum-server:latest
assert_failure
[[ ! -e "$CASE_ROOT/curl-calls" && ! -e "$CASE_ROOT/docker-calls" ]]
printf 'PASS: mutable commit and image references rejected\n'
for mode in apply-success apply-fail; do
  setup_case "$mode"
  save_baseline
  result=0
  bash "$SOURCE_DIR/scripts/prepare-infra-release.sh" "$EXPECTED_COMMIT" "$IMAGE" --apply \
    > "$CASE_ROOT/output" 2>&1 || result=$?
  if [[ "$mode" == apply-success ]]; then [[ "$result" == 0 ]]; else [[ "$result" == 7 ]]; fi
  [[ -f "$CASE_ROOT/applied" ]]
  sha256sum --check --status "$CASE_ROOT/live.sha256"
  flock -n "$BAREUM_ROOT/.actions-deploy.lock" true
  printf 'PASS: %s holds the Actions lock through deployment and propagates its result\n' "$mode"
done
printf 'All release preparation tests passed. GitHub downloads and Docker reads were simulated.\n'
