#!/usr/bin/env bash
# EC2: sudo bash infra/scripts/deploy.sh server|ai ghcr.io/sw-capstone/bareum-<service>:<tag>
# 실행 중인 대상 서비스가 healthy일 때 사용합니다. 배포 실패 시 이전 이미지와 환경변수를 복원하며, DB 테이블·컬럼 변경은 되돌리지 않습니다.
set +x
set -Eeuo pipefail
umask 077

fail() { printf '%s\n' "$1" >&2; exit 1; }
parse_arguments() {
  [[ $# == 2 || $# == 3 ]] || fail 'Usage: deploy.sh server|ai IMAGE [VALIDATED_RELEASE]'
  SERVICE="$1"
  IMAGE="$2"
  RELEASE_DIR="${3:-}"
  [[ -z "$RELEASE_DIR" || "$SERVICE" == server ]] || fail 'Infrastructure releases are owned by the server deployment.'
  case "$SERVICE" in
    server) IMAGE_KEY=SERVER_IMAGE; PORT=8082 ;;
    ai) IMAGE_KEY=AI_IMAGE; PORT=8081 ;;
    *) fail 'Deployment target must be server or ai.' ;;
  esac
  [[ "$IMAGE" =~ ^ghcr\.io/sw-capstone/bareum-${SERVICE}(:[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}|@sha256:[a-f0-9]{64})$ ]] \
    || fail 'Provide a GHCR tag or SHA256 digest for the target service.'
  [[ -z "$RELEASE_DIR" || "$IMAGE" =~ @sha256:[a-f0-9]{64}$ ]] || fail 'Infrastructure releases require an image digest.'
}

configure_paths() {
  SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  INFRA_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
  if [[ -n "$RELEASE_DIR" ]]; then
    INFRA_DIR="${BAREUM_ROOT:-/opt/bareum}/infra"
    SCRIPT_DIR="$INFRA_DIR/scripts"
  fi
  HOOK_DIR="${LETSENCRYPT_DIR:-/etc/letsencrypt}/renewal-hooks/deploy"
  DEPLOY_ENV="$INFRA_DIR/env/.env.deploy"
  SERVER_ENV="$INFRA_DIR/env/.env.server"
  STATE_DIR="$INFRA_DIR/.deploy-state"
  PENDING="$STATE_DIR/pending"
  WAIT_TIMEOUT="${DEPLOY_WAIT_TIMEOUT:-180}"
  [[ "$WAIT_TIMEOUT" =~ ^[1-9][0-9]{0,2}$ ]] || fail 'DEPLOY_WAIT_TIMEOUT must be 1-999 seconds.'
  INFRA_APPLIED=0
  NGINX_APPLIED=0
  COMPOSE_OVERRIDE=''
}

acquire_deployment_lock() {
  for tool in docker flock awk; do
    command -v "$tool" >/dev/null || fail "Required command not found: $tool"
  done
  [[ -f "$DEPLOY_ENV" && ! -L "$DEPLOY_ENV" ]] || fail '.env.deploy must be a regular file, not a symlink.'
  [[ ! -L "$STATE_DIR" ]] || fail 'Deployment state directory must not be a symlink.'
  mkdir -p -- "$STATE_DIR"
  chmod 700 "$STATE_DIR"
  exec 9>"$STATE_DIR/lock"
  flock -n 9 || fail 'Another deployment is running. Retry after it finishes.'
  [[ ! -e "$PENDING" ]] || fail 'Incomplete deployment detected. Check infra/.deploy-state/pending.'
}

compose() (
  unset SERVER_IMAGE AI_IMAGE SERVER_ENV_FILE AI_ENV_FILE RDS_CA_FILE
  unset HTTP_BIND_IP HTTP_PORT HTTPS_PORT COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES COMPOSE_ENV_FILES
  local extra=()
  if [[ -n "$COMPOSE_OVERRIDE" ]]; then extra=(-f "$COMPOSE_OVERRIDE"); fi
  docker compose --project-name bareum --project-directory "$INFRA_DIR" \
    -f "$INFRA_DIR/docker-compose.yml" "${extra[@]}" --env-file "$DEPLOY_ENV" "$@"
)

release_target() {
  case "$1" in
    docker-compose.yml|nginx/default.conf|scripts/deploy.sh|scripts/prepare-server-env.sh|env/.env.server.template)
      printf '%s/%s\n' "$INFRA_DIR" "$1" ;;
    scripts/reload-nginx-after-cert-renewal.sh) printf '%s/reload-nginx-after-cert-renewal.sh\n' "$HOOK_DIR" ;;
    *) return 1 ;;
  esac
}

check_release() {
  [[ -n "$RELEASE_DIR" ]] || return 0
  local file relative target state mode container image_id running service
  for tool in sha256sum cmp stat jq; do command -v "$tool" >/dev/null || fail "Required command not found: $tool"; done
  [[ "$RELEASE_DIR" == "$STATE_DIR/releases/"* && ! -L "$RELEASE_DIR" && -d "$RELEASE_DIR" ]] \
    || fail 'Release must be a directory under infra/.deploy-state/releases.'
  [[ "${RELEASE_DIR#"$STATE_DIR/releases/"}" =~ ^[a-f0-9]{40}-[a-zA-Z0-9]+$ ]] || fail 'Invalid release directory name.'
  [[ "$(cat "$RELEASE_DIR/status")" == validated ]] || fail 'Release has not passed validation or has already been used.'
  [[ "$(cat "$RELEASE_DIR/server-image")" == "$IMAGE" ]] || fail 'Release image does not match the requested image.'
  RELEASE_FILES=(docker-compose.yml nginx/default.conf scripts/deploy.sh scripts/prepare-server-env.sh
    env/.env.server.template scripts/reload-nginx-after-cert-renewal.sh)
  cmp -s "$RELEASE_DIR/staged.sha256" <(cd "$RELEASE_DIR/staged/infra" && sha256sum "${RELEASE_FILES[@]}") \
    || fail 'Release files changed after validation.'
  for file in "${RELEASE_FILES[@]}" env/.env.deploy env/.env.server; do
    relative="infra/$file"
    if [[ "$file" == scripts/reload-nginx-after-cert-renewal.sh ]]; then relative=certbot/reload-nginx-after-cert-renewal.sh; fi
    target="$INFRA_DIR/$file"
    if [[ "$file" == scripts/reload-nginx-after-cert-renewal.sh ]]; then target="$(release_target "$file")"; fi
    IFS=$'\t' read -r state mode < <(awk -F '\t' -v file="$relative" '$1 == file {print $2 "\t" $3}' "$RELEASE_DIR/backup/files.tsv")
    [[ ! -L "$target" ]] || fail 'A live deployment file became a symlink.'
    case "$state" in
      present)
        if [[ ! -f "$target" || "$(stat -c %a "$target")" != "$mode" ]] || ! cmp -s "$target" "$RELEASE_DIR/backup/$relative"; then
          fail 'Live deployment files changed after preparation. Prepare a new release.'
        fi ;;
      missing) [[ ! -e "$target" ]] || fail 'A previously absent deployment file now exists. Prepare a new release.' ;;
      *) fail 'Release backup manifest is incomplete.' ;;
    esac
  done
  while IFS=$'\t' read -r service container image_id running; do
    [[ "$service" == server || "$service" == ai || "$service" == nginx ]] || fail 'Invalid backup service.'
    [[ "$(docker ps --all --quiet --filter label=com.docker.compose.project=bareum --filter "label=com.docker.compose.service=$service")" == "${container/missing/}" ]] \
      || fail 'Containers changed after preparation. Prepare a new release.'
    [[ "$container" != missing ]] || continue
    [[ "$(docker inspect --format '{{.Image}}' "$container")" == "$image_id" &&
       "$(docker inspect --format '{{.State.Running}}' "$container")" == "$running" ]] \
      || fail 'Container state changed after preparation. Prepare a new release.'
    if [[ "$service" == nginx ]]; then OLD_NGINX_IMAGE="$image_id"; fi
  done < "$RELEASE_DIR/backup/images.tsv"
  [[ -n "${OLD_NGINX_IMAGE:-}" ]] || fail 'Nginx image backup is missing.'
  docker image inspect "$OLD_NGINX_IMAGE" >/dev/null
  NGINX_IMAGE=$(cat "$RELEASE_DIR/nginx-image")
  [[ -n "$NGINX_IMAGE" ]] || fail 'Validated Nginx image is missing.'
}

atomic_install() {
  local source="$1" target="$2" mode="$3" temporary
  temporary=$(mktemp "$(dirname -- "$target")/.deploy-install.XXXXXX")
  if install -m "$mode" -- "$source" "$temporary" && mv -fT -- "$temporary" "$target"; then return 0; fi
  rm -f -- "$temporary"
  return 1
}

install_release() {
  [[ -n "$RELEASE_DIR" ]] || return 0
  local file mode
  CHANGED=1
  INFRA_APPLIED=1
  printf '%s\n' applying > "$RELEASE_DIR/status"
  install -d -m 755 -- "$HOOK_DIR"
  for file in "${RELEASE_FILES[@]}"; do
    mode=644
    if [[ "$file" == scripts/* ]]; then mode=755; fi
    atomic_install "$RELEASE_DIR/staged/infra/$file" "$(release_target "$file")" "$mode"
  done
}

restore_release() {
  local file relative state mode target
  for file in "${RELEASE_FILES[@]}"; do
    relative="infra/$file"
    if [[ "$file" == scripts/reload-nginx-after-cert-renewal.sh ]]; then relative=certbot/reload-nginx-after-cert-renewal.sh; fi
    target=$(release_target "$file") || return 1
    IFS=$'\t' read -r state mode < <(awk -F '\t' -v file="$relative" '$1 == file {print $2 "\t" $3}' "$PENDING/infra-backup/files.tsv") || return 1
    if [[ "$state" == present ]]; then
      atomic_install "$PENDING/infra-backup/$relative" "$target" "$mode" || return 1
    else
      rm -f -- "$target" || return 1
    fi
  done
}

route_healthy() {
  local body attempt
  for ((attempt=1; attempt<=15; attempt++)); do
    if body=$(compose exec -T nginx wget -q -T 3 -O - "http://127.0.0.1:$PORT/health" 2>/dev/null); then
      if [[ "${body//[[:space:]]/}" == '{"status":"ok"}' ]]; then return 0; fi
    fi
    sleep 2
  done
  return 1
}

replace_image() {
  awk -v key="$IMAGE_KEY" -v image="$3" '
    index($0, key "=") == 1 { print key "=" image; next }
    { print }
  ' "$1" > "$2"
}

check_configuration() {
  local required_file ca_file source_infra="$INFRA_DIR"
  if [[ -n "$RELEASE_DIR" ]]; then source_infra="$RELEASE_DIR/staged/infra"; fi
  docker compose version >/dev/null || fail 'Docker Compose plugin is required.'
  for required_file in "$INFRA_DIR/docker-compose.yml" "$INFRA_DIR/nginx/default.conf"; do
    [[ -f "$required_file" && -r "$required_file" ]] \
      || fail "Required deployment file is missing or unreadable: $required_file"
  done
  compose config --quiet
  [[ "$(awk -F= -v key="$IMAGE_KEY" '$1 == key { n++ } END { print n+0 }' "$DEPLOY_ENV")" == 1 ]] \
    || fail 'Target image key must appear exactly once in .env.deploy.'
  if [[ "$SERVICE" == server ]]; then
    ca_file=$(compose config --environment |
      awk -F= '$1 == "RDS_CA_FILE" { print substr($0, index($0, "=")+1) }')
    [[ -n "$ca_file" ]] || fail 'RDS_CA_FILE is required.'
    [[ "$ca_file" == /* ]] || ca_file="$INFRA_DIR/$ca_file"
    [[ -f "$ca_file" && -r "$ca_file" ]] || fail 'RDS CA file is missing or unreadable.'
    for required_file in "$source_infra/scripts/prepare-server-env.sh" "$source_infra/env/.env.server.template"; do
      [[ -f "$required_file" && -r "$required_file" ]] || fail "Required file is missing or unreadable: $required_file"
    done
    for tool in aws jq; do
      command -v "$tool" >/dev/null || fail "Required command not found: $tool"
    done
    [[ -f "$SERVER_ENV" && ! -L "$SERVER_ENV" ]] || fail 'Existing .env.server must be a regular file, not a symlink.'
    [[ "$(awk -F= '$1 == "SERVER_ENV_FILE" { print substr($0, index($0, "=")+1) }' "$DEPLOY_ENV")" == './env/.env.server' ]] \
      || fail 'SERVER_ENV_FILE must be ./env/.env.server.'
  fi
}

check_current_service() {
  OLD_CONTAINER=$(compose ps -q "$SERVICE")
  [[ -n "$OLD_CONTAINER" && "$OLD_CONTAINER" != *[[:space:]]* ]] || fail 'Exactly one running target container is required.'
  [[ "$(docker inspect --format '{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$OLD_CONTAINER")" == 'true healthy' ]] \
    || fail 'Existing target container is not healthy.'
  route_healthy || fail 'Pre-deployment Nginx route health check failed.'
  OLD_IMAGE=$(docker inspect --format '{{.Image}}' "$OLD_CONTAINER")
  docker image inspect "$OLD_IMAGE" >/dev/null
}

pull_image() {
  printf 'Pulling image: %s\n' "$IMAGE"
  docker pull "$IMAGE"
  NEW_IMAGE=$(docker image inspect --format '{{.Id}}' "$IMAGE")
  if [[ -n "$RELEASE_DIR" ]]; then
    docker pull "$NGINX_IMAGE"
    NEW_NGINX_IMAGE=$(docker image inspect --format '{{.Id}}' "$NGINX_IMAGE")
  fi
}

save_recovery_files() {
  mkdir -- "$PENDING"
  cp -- "$DEPLOY_ENV" "$PENDING/deploy.env"
  if [[ "$SERVICE" == server ]]; then cp -- "$SERVER_ENV" "$PENDING/server.env"; fi
  printf '%s\n' "$SERVICE" > "$PENDING/service"
  printf '%s\n' "$OLD_IMAGE" > "$PENDING/image-id"
  if [[ -n "$RELEASE_DIR" ]]; then
    cp -R -- "$RELEASE_DIR/backup" "$PENDING/infra-backup"
    printf '%s\n' "$RELEASE_DIR" > "$PENDING/release"
    printf '%s\n' "$OLD_NGINX_IMAGE" > "$PENDING/nginx-image-id"
    COMPOSE_OVERRIDE="$PENDING/nginx-image.json"
    jq -n --arg image "$NEW_NGINX_IMAGE" '{services:{nginx:{image:$image}}}' > "$COMPOSE_OVERRIDE"
  fi
  CHANGED=0
  APPLIED=0
}

rollback() {
  local restored=1
  if (( INFRA_APPLIED == 1 )); then restore_release || restored=0; fi
  if [[ -n "$RELEASE_DIR" ]]; then
    jq -n --arg image "$OLD_NGINX_IMAGE" '{services:{nginx:{image:$image}}}' > "$COMPOSE_OVERRIDE" || restored=0
  fi
  if [[ "$SERVICE" == server ]]; then
    cp -- "$PENDING/server.env" "$PENDING/server.restore" && \
      mv -f -- "$PENDING/server.restore" "$SERVER_ENV" || restored=0
  fi
  if (( APPLIED == 1 )); then
    replace_image "$PENDING/deploy.env" "$PENDING/deploy.restore" "$OLD_IMAGE" || restored=0
  else
    cp -- "$PENDING/deploy.env" "$PENDING/deploy.restore" || restored=0
  fi
  if (( restored == 1 )); then
    mv -f -- "$PENDING/deploy.restore" "$DEPLOY_ENV" || restored=0
  fi
  if (( restored == 1 && APPLIED == 1 )); then
    compose up -d --no-deps --no-build --pull never --force-recreate \
      --wait --wait-timeout "$WAIT_TIMEOUT" "$SERVICE" || restored=0
    local rollback_container
    if (( restored == 1 )); then
      rollback_container=$(compose ps -q "$SERVICE") || restored=0
      [[ -n "$rollback_container" && "$rollback_container" != *[[:space:]]* ]] || restored=0
    fi
    if (( restored == 1 )); then
      [[ "$(docker inspect --format '{{.Image}}' "$rollback_container")" == "$OLD_IMAGE" ]] || restored=0
    fi
  fi
  if (( restored == 1 && NGINX_APPLIED == 1 )); then
    compose up -d --no-deps --no-build --pull never --force-recreate \
      --wait --wait-timeout "$WAIT_TIMEOUT" nginx || restored=0
  fi
  if (( restored == 1 && APPLIED == 1 )); then route_healthy || restored=0; fi
  (( restored == 1 ))
}

finish() {
  local result=$?
  trap - EXIT
  trap '' HUP INT TERM
  set +e
  if (( result != 0 && CHANGED == 1 )); then
    printf 'Deployment failed. Restoring the previous image and environment.\n' >&2
    if ! rollback; then
      if [[ -n "$RELEASE_DIR" ]]; then printf '%s\n' recovery-required > "$RELEASE_DIR/status"; fi
      printf 'Rollback failed. Recovery files retained at: %s\n' "$PENDING" >&2
      exit 2
    fi
    printf 'Rollback completed. Deployment exits with a failure status.\n' >&2
  fi
  if [[ -n "$RELEASE_DIR" ]]; then
    if (( result == 0 )); then printf '%s\n' applied > "$RELEASE_DIR/status"
    else printf '%s\n' failed > "$RELEASE_DIR/status"; fi
  fi
  rm -rf -- "$PENDING"
  exit "$result"
}
register_cleanup() {
  trap finish EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
}

deploy_service() {
  CHANGED=1
  if [[ "$SERVICE" == server ]]; then bash "$SCRIPT_DIR/prepare-server-env.sh"; fi
  replace_image "$PENDING/deploy.env" "$PENDING/deploy.next" "$IMAGE"
  mv -f -- "$PENDING/deploy.next" "$DEPLOY_ENV"
  compose config --quiet
  APPLIED=1
  compose up -d --no-deps --no-build --pull never --force-recreate \
    --wait --wait-timeout "$WAIT_TIMEOUT" "$SERVICE"
  if [[ -n "$RELEASE_DIR" ]]; then
    NGINX_APPLIED=1
    compose up -d --no-deps --no-build --pull never --force-recreate \
      --wait --wait-timeout "$WAIT_TIMEOUT" nginx
    compose exec -T nginx nginx -t
  fi
}

verify_deployment() {
  NEW_CONTAINER=$(compose ps -q "$SERVICE")
  [[ -n "$NEW_CONTAINER" && "$NEW_CONTAINER" != *[[:space:]]* ]] || fail 'Expected exactly one target container after deployment.'
  [[ "$(docker inspect --format '{{.Image}}' "$NEW_CONTAINER")" == "$NEW_IMAGE" ]] \
    || fail 'Running image does not match the pulled image.'
  if [[ -n "$RELEASE_DIR" ]]; then
    local nginx_container
    nginx_container=$(compose ps -q nginx)
    [[ -n "$nginx_container" && "$nginx_container" != *[[:space:]]* &&
       "$(docker inspect --format '{{.Image}}' "$nginx_container")" == "$NEW_NGINX_IMAGE" ]] \
      || fail 'Running Nginx image does not match the prepared image.'
  fi
  route_healthy || fail 'Post-deployment Nginx route health check failed.'
}

main() {
  parse_arguments "$@"
  configure_paths
  acquire_deployment_lock
  check_release
  check_configuration
  check_current_service
  pull_image
  save_recovery_files
  register_cleanup
  install_release
  deploy_service
  verify_deployment
  printf 'Deployment completed: %s (%s)\n' "$SERVICE" "$IMAGE"
}

main "$@"
