#!/usr/bin/env bash
set -euo pipefail
SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf -- "$WORK"' EXIT
mkdir "$WORK/bin"
export PATH="$WORK/bin:$PATH"
OLD_IMAGE="sha256:$(printf '%064d' 1)"
NEW_IMAGE="sha256:$(printf '%064d' 2)"
export OLD_IMAGE NEW_IMAGE

cat > "$WORK/bin/sleep" <<'MOCK'
#!/usr/bin/env bash
exit 0
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
    if [[ "$*" == *--format* ]]; then printf '%s\n' "$NEW_IMAGE"; fi
    ;;
  inspect)
    case "$3" in
      *State.Running*)
        if [[ "$MODE" == unhealthy-baseline ]]; then echo 'true unhealthy'; else echo 'true healthy'; fi
        ;;
      *) cat "$CASE_ROOT/current-image" ;;
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
        if [[ "$MODE" == config-fail && -f "$CASE_ROOT/prepared" ]]; then exit 1; fi
        if [[ "$*" == --environment ]]; then printf 'RDS_CA_FILE=%s\n' "$RESOLVED_CA_FILE"; fi
        ;;
      ps) echo fixture-container ;;
      exec)
        if [[ "$(cat "$CASE_ROOT/current-image")" == "$NEW_IMAGE" && "$MODE" == route-fail ]]; then
          echo '{"status":"down"}'
        else
          echo '{"status":"ok"}'
        fi
        ;;
      up)
        [[ "$*" == *'--no-deps'* && "$*" == *'--no-build'* && "$*" == *'--pull never'* ]]
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
chmod +x "$WORK/bin/"*
ln -s "$WORK/bin/sleep" "$WORK/bin/aws"

setup_case() {
  export MODE="$1" TARGET="${2:-server}"
  export RESOLVED_CA_FILE=./certs/rds-ca.pem
  export IMAGE_KEY=SERVER_IMAGE
  [[ "$TARGET" != ai ]] || IMAGE_KEY=AI_IMAGE
  export CASE_ROOT="$WORK/$MODE-$TARGET"
  mkdir -p "$CASE_ROOT/infra/scripts" "$CASE_ROOT/infra/env" "$CASE_ROOT/infra/certs" "$CASE_ROOT/infra/nginx"
  touch "$CASE_ROOT/infra/nginx/default.conf"
  touch "$CASE_ROOT/infra/docker-compose.yml" "$CASE_ROOT/infra/certs/rds-ca.pem" "$CASE_ROOT/infra/env/.env.server.template"
  cp "$SOURCE_DIR/scripts/deploy.sh" "$CASE_ROOT/infra/scripts/"
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
    'RDS_CA_FILE=./certs/rds-ca.pem' > "$CASE_ROOT/infra/env/.env.deploy"
  printf '%s\n' 'DB_PASSWORD=old-fixture-value' > "$CASE_ROOT/infra/env/.env.server"
  cp "$CASE_ROOT/infra/env/.env.deploy" "$CASE_ROOT/original-deploy"
  cp "$CASE_ROOT/infra/env/.env.server" "$CASE_ROOT/original-server"
  printf '%s\n' "$OLD_IMAGE" > "$CASE_ROOT/current-image"
  : > "$CASE_ROOT/calls"
}

run_case() {
  RESULT=0
  bash "$CASE_ROOT/infra/scripts/deploy.sh" "$TARGET" \
    "${1:-ghcr.io/sw-capstone/bareum-$TARGET:new}" > "$CASE_ROOT/output" 2>&1 || RESULT=$?
  if grep -q 'fixture-value' "$CASE_ROOT/output"; then
    echo 'FAIL: secret appeared in output' >&2; exit 1
  fi
}

pass() { printf 'PASS: %s\n' "$1"; }
for target in server ai; do
  setup_case success "$target"
  run_case
  [[ "$RESULT" == 0 && "$(cat "$CASE_ROOT/current-image")" == "$NEW_IMAGE" ]]
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

for mode in pull-fail prepare-fail config-fail unhealthy-baseline missing-nginx missing-compose missing-cert missing-template; do
  setup_case "$mode"
  if [[ "$mode" == missing-cert ]]; then rm "$CASE_ROOT/infra/certs/rds-ca.pem"; fi
  if [[ "$mode" == missing-nginx ]]; then rm "$CASE_ROOT/infra/nginx/default.conf"; fi
  if [[ "$mode" == missing-compose ]]; then rm "$CASE_ROOT/infra/docker-compose.yml"; fi
  if [[ "$mode" == missing-template ]]; then rm "$CASE_ROOT/infra/env/.env.server.template"; fi
  run_case
  [[ "$RESULT" != 0 ]]
  cmp "$CASE_ROOT/original-deploy" "$CASE_ROOT/infra/env/.env.deploy"
  cmp "$CASE_ROOT/original-server" "$CASE_ROOT/infra/env/.env.server"
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
printf 'All deployment tests passed. Docker and Parameter Store calls were simulated.\n'
