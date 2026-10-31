#!/usr/bin/env bash
set -euo pipefail
SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf -- "$WORK"' EXIT
REAL_INSTALL=$(command -v install)
export REAL_INSTALL
mkdir "$WORK/bin"
export PATH="$WORK/bin:$PATH"
OLD_IMAGE="sha256:$(printf '%064d' 1)"
NEW_IMAGE="sha256:$(printf '%064d' 2)"
OLD_NGINX_IMAGE="sha256:$(printf '%064d' 3)"
NEW_NGINX_IMAGE="sha256:$(printf '%064d' 4)"
export OLD_IMAGE NEW_IMAGE OLD_NGINX_IMAGE NEW_NGINX_IMAGE

cat > "$WORK/bin/sleep" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
cat > "$WORK/bin/install" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$MODE" == install-fail && "$*" == *'/staged/infra/nginx/default.conf'* ]]; then exit 1; fi
exec "$REAL_INSTALL" "$@"
MOCK
cat > "$WORK/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$CASE_ROOT/calls"
case "$1" in
  pull)
    [[ "$MODE" != pull-fail ]]
    ;;
  image)
    if [[ "$*" == *--format* ]]; then
      if [[ "${!#}" == nginx:* ]]; then printf '%s\n' "$NEW_NGINX_IMAGE"; else printf '%s\n' "$NEW_IMAGE"; fi
    fi
    ;;
  ps)
    case "${!#}" in
      *=server) echo fixture-container ;;
      *=nginx) echo nginx-container ;;
      *=ai) echo ai-container ;;
    esac
    ;;
  inspect)
    case "$3" in
      '{{json .Config.Env}}') printf '["BAREUM_AI_INTERNAL_API_TOKEN=old-fixture-value"]\n' ;;
      '{{.State.Running}}') echo true ;;
      *State.Running*)
        if [[ "$MODE" == unhealthy-baseline ]]; then echo 'true unhealthy'; else echo 'true healthy'; fi
        ;;
      *)
        if [[ "${!#}" == nginx-container ]]; then cat "$CASE_ROOT/current-nginx"; else cat "$CASE_ROOT/current-image"; fi
        ;;
    esac
    ;;
  compose)
    shift
    if [[ "$1" == version ]]; then exit 0; fi
    while [[ "$1" == -* ]]; do shift 2; done
    operation="$1"
    shift
    case "$operation" in
      config)
        [[ -f "${AI_ENV_FILE:-$CASE_ROOT/infra/env/.env.ai}" ]] || exit 1
        if [[ "$MODE" == config-fail && -f "$CASE_ROOT/prepared" ]]; then exit 1; fi
        if [[ "$*" == --environment ]]; then printf 'RDS_CA_FILE=%s\n' "$RESOLVED_CA_FILE"; fi
        ;;
      ps)
        if [[ "${!#}" == nginx ]]; then echo nginx-container; else echo fixture-container; fi
        ;;
      exec)
        if [[ "$*" == *'nginx -t' ]]; then exit 0; fi
        if [[ "$(cat "$CASE_ROOT/current-image")" == "$NEW_IMAGE" && "$MODE" == route-fail ]]; then
          echo '{"status":"down"}'
        else
          echo '{"status":"ok"}'
        fi
        ;;
      up)
        [[ "$*" == *'--no-deps'* && "$*" == *'--no-build'* && "$*" == *'--pull never'* ]]
        if [[ "${!#}" == nginx ]]; then
          selected=$(jq -r '.services.nginx.image' "$CASE_ROOT/infra/.deploy-state/pending/nginx-image.json")
          printf '%s\n' "$selected" > "$CASE_ROOT/current-nginx"
          [[ "$MODE" != nginx-fail || "$selected" == "$OLD_NGINX_IMAGE" ]]
          exit
        fi
        [[ "${!#}" == "$TARGET" ]]
        selected=$(awk -F= -v k="$IMAGE_KEY" '$1 == k {print $2}' "$CASE_ROOT/infra/env/.env.deploy")
        if [[ "$selected" == "$OLD_IMAGE" ]]; then
          [[ "$MODE" != rollback-fail ]] || exit 1
          if [[ "$MODE" == hangup-during-rollback ]]; then kill -HUP "$PPID"; fi
          printf '%s\n' "$OLD_IMAGE" > "$CASE_ROOT/current-image"
          if [[ "$MODE" == rollback-wrong-image ]]; then echo sha256:wrong > "$CASE_ROOT/current-image"; fi
        else
          printf '%s\n' "$NEW_IMAGE" > "$CASE_ROOT/current-image"
          case "$MODE" in
            up-fail|rollback-fail|rollback-wrong-image) exit 1 ;;
            interrupted) kill -TERM "$PPID" ;;
            hangup|hangup-during-rollback) kill -HUP "$PPID" ;;
            wrong-image) echo sha256:wrong > "$CASE_ROOT/current-image" ;;
          esac
        fi
        ;;
      *) exit 90 ;;
    esac
    ;;
  *) exit 91 ;;
esac
MOCK
cat > "$WORK/bin/aws" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == ssm && "$2" == get-parameter ]]
touch "$CASE_ROOT/ai-prepared"
[[ "$MODE" != ai-env-fail ]] || exit 1
jq -n '{Parameter:{Name:"/bareum/dev/ai/env",Type:"SecureString",Value:"BAREUM_AI_INTERNAL_API_TOKEN=new-fixture-value\nBAREUM_AI_S3_ALLOWED_HOST=fixture.invalid\nBAREUM_AI_S3_ALLOWED_KEY_PREFIX=reports/\n"}}'
MOCK
chmod +x "$WORK/bin/"*

setup_case() {
  RELEASE_DIR=''
  export MODE="$1" TARGET="${2:-server}"
  export RESOLVED_CA_FILE=./certs/rds-ca.pem
  export IMAGE_KEY=SERVER_IMAGE
  [[ "$TARGET" != ai ]] || IMAGE_KEY=AI_IMAGE
  export CASE_ROOT="$WORK/$MODE-$TARGET"
  export BAREUM_ROOT="$CASE_ROOT" LETSENCRYPT_DIR="$CASE_ROOT/letsencrypt"
  mkdir -p "$CASE_ROOT/infra/scripts" "$CASE_ROOT/infra/env" "$CASE_ROOT/infra/certs" "$CASE_ROOT/infra/nginx"
  touch "$CASE_ROOT/infra/nginx/default.conf"
  touch "$CASE_ROOT/infra/docker-compose.yml" "$CASE_ROOT/infra/certs/rds-ca.pem" "$CASE_ROOT/infra/env/.env.server.template"
  cp "$SOURCE_DIR/scripts/deploy.sh" "$CASE_ROOT/infra/scripts/"
  cp "$SOURCE_DIR/scripts/prepare-ai-env.sh" "$CASE_ROOT/infra/scripts/"
  cat > "$CASE_ROOT/infra/scripts/prepare-server-env.sh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
touch "$CASE_ROOT/prepared"
printf '%s\n' 'DB_PASSWORD=new-fixture-value' > "$CASE_ROOT/infra/env/.env.server"
[[ "$MODE" != prepare-fail ]]
MOCK
  printf '%s\n' \
    'SERVER_IMAGE=ghcr.io/sw-capstone/bareum-server:old' \
    'AI_IMAGE=ghcr.io/sw-capstone/bareum-ai:old' \
    'SERVER_ENV_FILE=./env/.env.server' \
    'AI_ENV_FILE=./env/.env.ai' \
    'RDS_CA_FILE=./certs/rds-ca.pem' > "$CASE_ROOT/infra/env/.env.deploy"
  printf '%s\n' 'DB_PASSWORD=old-fixture-value' > "$CASE_ROOT/infra/env/.env.server"
  cp "$CASE_ROOT/infra/env/.env.deploy" "$CASE_ROOT/original-deploy"
  cp "$CASE_ROOT/infra/env/.env.server" "$CASE_ROOT/original-server"
  printf 'BAREUM_AI_INTERNAL_API_TOKEN=old-fixture-value\n' > "$CASE_ROOT/infra/env/.env.ai"
  cp "$CASE_ROOT/infra/env/.env.ai" "$CASE_ROOT/original-ai"
  printf '%s\n' "$OLD_IMAGE" > "$CASE_ROOT/current-image"
  printf '%s\n' "$OLD_NGINX_IMAGE" > "$CASE_ROOT/current-nginx"
  : > "$CASE_ROOT/calls"
}

run_case() {
  RESULT=0
  local extra=()
  local image="${1:-ghcr.io/sw-capstone/bareum-$TARGET:new}"
  if [[ -n "$RELEASE_DIR" ]]; then extra=("$RELEASE_DIR"); fi
  if [[ -n "$RELEASE_DIR" && $# == 0 ]]; then image="ghcr.io/sw-capstone/bareum-server@$NEW_IMAGE"; fi
  bash "$CASE_ROOT/infra/scripts/deploy.sh" "$TARGET" \
    "$image" "${extra[@]}" > "$CASE_ROOT/output" 2>&1 || RESULT=$?
  if grep -q 'fixture-value' "$CASE_ROOT/output"; then
    echo 'FAIL: secret appeared in output' >&2; exit 1
  fi
}

pass() { printf 'PASS: %s\n' "$1"; }
for target in server ai; do
  setup_case bootstrap "$target"
  rm "$CASE_ROOT/infra/env/.env.ai"
  run_case
  if [[ "$RESULT" != 0 ]]; then
    printf 'FAIL: %s deployment cannot bootstrap a missing AI environment\n' "$target" >&2
    exit 1
  fi
  grep -q '^BAREUM_AI_INTERNAL_API_TOKEN=new-fixture-value$' "$CASE_ROOT/infra/env/.env.ai"
  [[ "$(stat -c %a "$CASE_ROOT/infra/env/.env.ai")" == 600 ]]
  pass "$target bootstraps AI environment before Compose validation"
done

for target in server ai; do
  setup_case up-fail "$target"
  rm "$CASE_ROOT/infra/env/.env.ai"
  run_case
  [[ "$RESULT" != 0 && ! -e "$CASE_ROOT/infra/env/.env.ai" ]]
  [[ "$(cat "$CASE_ROOT/current-image")" == "$OLD_IMAGE" ]]
  if [[ "$target" == ai ]]; then
    grep -q "inspect --format {{json .Config.Env}}" "$CASE_ROOT/calls"
  fi
  [[ ! -e "$CASE_ROOT/infra/.deploy-state/pending" ]]
  pass "$target rollback restores originally absent AI env state"
done

for target in server ai; do
  setup_case ai-env-fail "$target"
  rm "$CASE_ROOT/infra/env/.env.ai"
  run_case
  [[ "$RESULT" != 0 && ! -e "$CASE_ROOT/infra/env/.env.ai" ]]
  if grep -q ' up -d ' "$CASE_ROOT/calls"; then exit 1; fi
  pass "$target missing AI parameter leaves running containers and absent file intact"
done

for target in server ai; do
  setup_case success "$target"
  run_case
  [[ "$RESULT" == 0 && "$(cat "$CASE_ROOT/current-image")" == "$NEW_IMAGE" ]]
  if [[ "$target" == server ]]; then
    grep -q 'http://127.0.0.1:8082/health' "$CASE_ROOT/calls"
  else
    grep -q 'http://127.0.0.1:8081/health' "$CASE_ROOT/calls"
  fi
  grep -q "^$IMAGE_KEY=ghcr.io/sw-capstone/bareum-$target:new$" "$CASE_ROOT/infra/env/.env.deploy"
  [[ ! -e "$CASE_ROOT/infra/.deploy-state/pending" ]]
  if [[ "$target" == ai ]]; then
    [[ ! -e "$CASE_ROOT/prepared" ]]
    cmp "$CASE_ROOT/original-server" "$CASE_ROOT/infra/env/.env.server"
    grep -q '^SERVER_IMAGE=ghcr.io/sw-capstone/bareum-server:old$' "$CASE_ROOT/infra/env/.env.deploy"
  else
    [[ -f "$CASE_ROOT/prepared" ]]
    grep -q '^AI_IMAGE=ghcr.io/sw-capstone/bareum-ai:old$' "$CASE_ROOT/infra/env/.env.deploy"
  fi
  pass "$target success; other service preserved"
done

for mode in pull-fail prepare-fail ai-env-fail config-fail unhealthy-baseline missing-nginx missing-compose missing-cert missing-template; do
  setup_case "$mode"
  if [[ "$mode" == missing-cert ]]; then rm "$CASE_ROOT/infra/certs/rds-ca.pem"; fi
  if [[ "$mode" == missing-nginx ]]; then rm "$CASE_ROOT/infra/nginx/default.conf"; fi
  if [[ "$mode" == missing-compose ]]; then rm "$CASE_ROOT/infra/docker-compose.yml"; fi
  if [[ "$mode" == missing-template ]]; then rm "$CASE_ROOT/infra/env/.env.server.template"; fi
  run_case
  [[ "$RESULT" != 0 ]]
  cmp "$CASE_ROOT/original-deploy" "$CASE_ROOT/infra/env/.env.deploy"
  cmp "$CASE_ROOT/original-server" "$CASE_ROOT/infra/env/.env.server"
  cmp "$CASE_ROOT/original-ai" "$CASE_ROOT/infra/env/.env.ai"
  if grep -q ' up -d ' "$CASE_ROOT/calls"; then exit 1; fi
  pass "$mode preserves files and running container"
done

setup_case absolute-cert
RESOLVED_CA_FILE="$CASE_ROOT/infra/certs/rds-ca.pem"
printf 'RDS_CA_FILE=%s/infra/certs/rds-ca.pem\n' "$CASE_ROOT" >> "$CASE_ROOT/infra/env/.env.deploy"
awk '!/^RDS_CA_FILE=\.\//' "$CASE_ROOT/infra/env/.env.deploy" > "$CASE_ROOT/deploy.next"
mv "$CASE_ROOT/deploy.next" "$CASE_ROOT/infra/env/.env.deploy"
run_case
[[ "$RESULT" == 0 ]]
pass 'absolute certificate path accepted'

for mode in quoted-cert commented-cert; do
  setup_case "$mode"
  ca_value='"./certs/rds-ca.pem"'
  if [[ "$mode" == commented-cert ]]; then ca_value='./certs/rds-ca.pem # certificate'; fi
  awk -v value="$ca_value" '/^RDS_CA_FILE=/ { print "RDS_CA_FILE=" value; next } { print }' \
    "$CASE_ROOT/infra/env/.env.deploy" > "$CASE_ROOT/deploy.next"
  mv "$CASE_ROOT/deploy.next" "$CASE_ROOT/infra/env/.env.deploy"
  run_case
  if [[ "$RESULT" != 0 ]]; then
    printf 'FAIL: %s must accept the certificate path resolved by Compose\n' "$mode" >&2
    cat "$CASE_ROOT/output" >&2
    exit 1
  fi
  [[ "$(cat "$CASE_ROOT/current-image")" == "$NEW_IMAGE" ]]
  pass "$mode accepts the certificate path resolved by Compose"
done

for mode in up-fail route-fail interrupted wrong-image hangup hangup-during-rollback; do
  setup_case "$mode"
  run_case
  [[ "$RESULT" != 0 && "$(cat "$CASE_ROOT/current-image")" == "$OLD_IMAGE" ]]
  if [[ "$mode" == hangup* ]]; then [[ "$RESULT" == 129 ]]; fi
  cmp "$CASE_ROOT/original-server" "$CASE_ROOT/infra/env/.env.server"
  cmp "$CASE_ROOT/original-ai" "$CASE_ROOT/infra/env/.env.ai"
  grep -q "^SERVER_IMAGE=$OLD_IMAGE$" "$CASE_ROOT/infra/env/.env.deploy"
  [[ ! -e "$CASE_ROOT/infra/.deploy-state/pending" ]]
  [[ "$(stat -c %a "$CASE_ROOT/infra/env/.env.server")" == 600 ]]
  pass "$mode rolls back image and secret file; nonzero exit"
done

for mode in rollback-fail rollback-wrong-image; do
setup_case "$mode"
run_case
[[ "$RESULT" == 2 && -f "$CASE_ROOT/infra/.deploy-state/pending/server.env" ]]
[[ "$(stat -c %a "$CASE_ROOT/infra/.deploy-state/pending")" == 700 ]]
[[ "$(stat -c %a "$CASE_ROOT/infra/.deploy-state/pending/server.env")" == 600 ]]
cp "$CASE_ROOT/calls" "$CASE_ROOT/calls-before-retry"
run_case
[[ "$RESULT" != 0 ]]
cmp "$CASE_ROOT/calls" "$CASE_ROOT/calls-before-retry"
pass 'rollback failure preserves restricted backup and blocks next deployment'
done

setup_case locked
mkdir -p "$CASE_ROOT/infra/.deploy-state"
(
  exec 8> "$CASE_ROOT/infra/.deploy-state/lock"
  flock -n 8
  run_case
  [[ "$RESULT" != 0 && ! -s "$CASE_ROOT/calls" ]]
)
pass 'concurrent deployment rejected by real flock'

setup_case invalid-image
run_case 'ghcr.io/another/project:latest'
[[ "$RESULT" != 0 && ! -s "$CASE_ROOT/calls" ]]
pass 'foreign image rejected before Docker calls'

setup_case duplicate-key
echo 'SERVER_IMAGE=duplicate' >> "$CASE_ROOT/infra/env/.env.deploy"
run_case
[[ "$RESULT" != 0 ]]
if grep -q '^pull ' "$CASE_ROOT/calls"; then exit 1; fi
pass 'duplicate image key rejected'

setup_release() {
  local file relative source mode
  RELEASE_DIR="$CASE_ROOT/infra/.deploy-state/releases/0123456789abcdef0123456789abcdef01234567-fixture"
  mkdir -p "$RELEASE_DIR/staged/infra" "$RELEASE_DIR/backup"
  RELEASE_FILES=(docker-compose.yml nginx/default.conf scripts/deploy.sh scripts/prepare-server-env.sh scripts/prepare-ai-env.sh
    env/.env.server.template scripts/reload-nginx-after-cert-renewal.sh)
  for file in "${RELEASE_FILES[@]}" env/.env.deploy env/.env.server env/.env.ai; do
    source="$CASE_ROOT/infra/$file"
    relative="infra/$file"
    if [[ "$file" == scripts/reload-nginx-after-cert-renewal.sh ]]; then
      source="$LETSENCRYPT_DIR/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh"
      relative=certbot/reload-nginx-after-cert-renewal.sh
    fi
    if [[ -f "$source" ]]; then
      mkdir -p "$(dirname "$RELEASE_DIR/backup/$relative")"
      cp "$source" "$RELEASE_DIR/backup/$relative"
      mode=$(stat -c %a "$source")
      printf '%s\tpresent\t%s\n' "$relative" "$mode" >> "$RELEASE_DIR/backup/files.tsv"
    else
      printf '%s\tmissing\t-\n' "$relative" >> "$RELEASE_DIR/backup/files.tsv"
    fi
    [[ "$file" != env/.env.deploy && "$file" != env/.env.server && "$file" != env/.env.ai ]] || continue
    mkdir -p "$(dirname "$RELEASE_DIR/staged/infra/$file")"
    if [[ -f "$source" ]]; then cp "$source" "$RELEASE_DIR/staged/infra/$file"
    else cp "$SOURCE_DIR/$file" "$RELEASE_DIR/staged/infra/$file"; fi
    printf '\n# candidate fixture\n' >> "$RELEASE_DIR/staged/infra/$file"
  done
  (cd "$RELEASE_DIR/staged/infra" && sha256sum "${RELEASE_FILES[@]}") > "$RELEASE_DIR/staged.sha256"
  bash "$SOURCE_DIR/scripts/prepare-ai-env.sh" "$RELEASE_DIR/ai.env" >/dev/null
  (cd "$RELEASE_DIR" && sha256sum ai.env) > "$RELEASE_DIR/ai-env.sha256"
  printf 'server\tfixture-container\t%s\ttrue\nai\tai-container\t%s\ttrue\nnginx\tnginx-container\t%s\ttrue\n' \
    "$OLD_IMAGE" "$OLD_IMAGE" "$OLD_NGINX_IMAGE" > "$RELEASE_DIR/backup/images.tsv"
  printf '%s\n' validated > "$RELEASE_DIR/status"
  printf '%s\n' "ghcr.io/sw-capstone/bareum-server@$NEW_IMAGE" > "$RELEASE_DIR/server-image"
  printf '%s\n' nginx:1.30-alpine > "$RELEASE_DIR/nginx-image"
}

for mode in release-success absent-template; do
  setup_case "$mode"
  if [[ "$mode" == absent-template ]]; then rm "$CASE_ROOT/infra/env/.env.server.template"; fi
  setup_release
  run_case
  if [[ "$RESULT" != 0 ]]; then cat "$CASE_ROOT/output" >&2; exit 1; fi
  [[ "$(cat "$RELEASE_DIR/status")" == applied && "$(cat "$CASE_ROOT/current-nginx")" == "$NEW_NGINX_IMAGE" ]]
  for file in "${RELEASE_FILES[@]}"; do
    target="$CASE_ROOT/infra/$file"
    if [[ "$file" == scripts/reload-nginx-after-cert-renewal.sh ]]; then target="$LETSENCRYPT_DIR/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh"; fi
    cmp "$RELEASE_DIR/staged/infra/$file" "$target"
  done
  [[ "$(stat -c %a "$CASE_ROOT/infra/scripts/deploy.sh")" == 755 ]]
  [[ "$(stat -c %a "$CASE_ROOT/infra/nginx/default.conf")" == 644 ]]
  grep -q '^AI_IMAGE=ghcr.io/sw-capstone/bareum-ai:old$' "$CASE_ROOT/infra/env/.env.deploy"
  [[ "$(grep ' up -d ' "$CASE_ROOT/calls" | awk '{print $NF}' | paste -sd , -)" == server,nginx ]]
  pass "$mode installs all files, then recreates server and nginx only"
done

for mode in stale-live stale-ai-env tampered-release tampered-ai-env wrong-status stale-image wrong-image-request; do
  setup_case "$mode"
  setup_release
  case "$mode" in
    stale-live) echo changed >> "$CASE_ROOT/infra/nginx/default.conf" ;;
    stale-ai-env) echo changed >> "$CASE_ROOT/infra/env/.env.ai" ;;
    tampered-release) echo changed >> "$RELEASE_DIR/staged/infra/nginx/default.conf" ;;
    tampered-ai-env) echo changed >> "$RELEASE_DIR/ai.env" ;;
    wrong-status) echo applied > "$RELEASE_DIR/status" ;;
    stale-image) echo "$NEW_IMAGE" > "$CASE_ROOT/current-image" ;;
    wrong-image-request) echo different > "$RELEASE_DIR/server-image" ;;
  esac
  run_case
  [[ "$RESULT" != 0 ]]
  if grep -q '^pull\| up -d ' "$CASE_ROOT/calls"; then exit 1; fi
  [[ ! -e "$CASE_ROOT/prepared" ]]
  pass "$mode blocks mutation"
done

for mode in install-fail prepare-fail up-fail nginx-fail route-fail rollback-fail; do
  setup_case "release-$mode"
  MODE="$mode"
  setup_release
  run_case
  [[ "$RESULT" != 0 ]]
  cmp "$CASE_ROOT/original-server" "$CASE_ROOT/infra/env/.env.server"
  cmp "$CASE_ROOT/original-ai" "$CASE_ROOT/infra/env/.env.ai"
  for file in "${RELEASE_FILES[@]}"; do
    if [[ "$file" == scripts/reload-nginx-after-cert-renewal.sh ]]; then
      [[ ! -e "$LETSENCRYPT_DIR/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh" ]]
    else
      cmp "$RELEASE_DIR/backup/infra/$file" "$CASE_ROOT/infra/$file"
    fi
  done
  if [[ "$mode" == rollback-fail ]]; then
    [[ "$RESULT" == 2 && "$(cat "$RELEASE_DIR/status")" == recovery-required ]]
    [[ -f "$CASE_ROOT/infra/.deploy-state/pending/infra-backup/infra/docker-compose.yml" ]]
  else
    [[ "$(cat "$RELEASE_DIR/status")" == failed && "$(cat "$CASE_ROOT/current-image")" == "$OLD_IMAGE" ]]
    [[ "$(cat "$CASE_ROOT/current-nginx")" == "$OLD_NGINX_IMAGE" ]]
  fi
  pass "release $mode preserves recovery files or restores settings and images"
done
printf 'All deployment tests passed. Docker and Parameter Store calls were simulated.\n'
