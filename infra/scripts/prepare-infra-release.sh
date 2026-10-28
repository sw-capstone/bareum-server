#!/usr/bin/env bash
set +x
set -Eeuo pipefail
umask 077

fail() { printf '%s\n' "$1" >&2; exit 1; }

[[ $# == 2 || ( $# == 3 && "$3" == --apply ) ]] \
  || fail 'Usage: prepare-infra-release.sh COMMIT_SHA SERVER_IMAGE_DIGEST [--apply]'
APPLY="${3:-}"
COMMIT_SHA="$1"
SERVER_IMAGE="$2"
[[ "$COMMIT_SHA" =~ ^[a-f0-9]{40}$ ]] || fail 'A full Git commit SHA is required.'
[[ "$SERVER_IMAGE" =~ ^ghcr\.io/sw-capstone/bareum-server@sha256:[a-f0-9]{64}$ ]] \
  || fail 'A published bareum-server image digest is required.'

BAREUM_ROOT="${BAREUM_ROOT:-/opt/bareum}"
LETSENCRYPT_DIR="${LETSENCRYPT_DIR:-/etc/letsencrypt}"
INFRA_DIR="$BAREUM_ROOT/infra"
STATE_DIR="$INFRA_DIR/.deploy-state"
RELEASES_DIR="$STATE_DIR/releases"
WORK=''
CHECK_CONTAINER=''

cleanup() {
  local result=$?
  trap - EXIT
  if [[ -n "$CHECK_CONTAINER" ]]; then docker rm -f "$CHECK_CONTAINER" >/dev/null 2>&1 || true; fi
  if [[ -n "$WORK" ]]; then rm -rf -- "$WORK"; fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

for tool in curl docker flock sha256sum stat jq awk timeout; do
  command -v "$tool" >/dev/null || fail "Required command not found: $tool"
done
[[ "$BAREUM_ROOT" == /* && "$LETSENCRYPT_DIR" == /* ]] || fail 'Deployment roots must be absolute paths.'
[[ -d "$INFRA_DIR" && ! -L "$INFRA_DIR" ]] || fail 'The existing infra directory is required.'
[[ ! -L "$BAREUM_ROOT/.actions-deploy.lock" ]] || fail 'Deployment lock must not be a symlink.'
exec 8>"$BAREUM_ROOT/.actions-deploy.lock"
if [[ "$APPLY" == --apply ]]; then
  flock --wait 600 8 || fail 'Timed out waiting for another Actions deployment.'
else
  flock -n 8 || fail 'Another Actions deployment is running. Retry after it finishes.'
fi
[[ ! -L "$STATE_DIR" ]] || fail 'Deployment state directory must not be a symlink.'
mkdir -p -- "$STATE_DIR"
chmod 700 "$STATE_DIR"
[[ ! -L "$STATE_DIR/lock" ]] || fail 'Deployment lock must not be a symlink.'
exec 9>"$STATE_DIR/lock"
flock -n 9 || fail 'Another deployment is running. Retry after it finishes.'
[[ ! -e "$STATE_DIR/pending" && ! -L "$STATE_DIR/pending" ]] \
  || fail 'Incomplete deployment detected. Check infra/.deploy-state/pending.'
[[ ! -L "$RELEASES_DIR" ]] || fail 'Release directory must not be a symlink.'
mkdir -p -- "$RELEASES_DIR"
chmod 700 "$RELEASES_DIR"
WORK=$(mktemp -d "$RELEASES_DIR/.preparing.XXXXXX")

FILES=(
  docker-compose.yml
  nginx/default.conf
  scripts/deploy.sh
  scripts/prepare-server-env.sh
  env/.env.server.template
  scripts/reload-nginx-after-cert-renewal.sh
)

for file in "${FILES[@]}"; do
  target="$WORK/staged/infra/$file"
  mkdir -p -- "$(dirname -- "$target")"
  curl --proto '=https' --tlsv1.2 --fail --silent --show-error --location \
    --retry 2 --connect-timeout 10 --max-time 60 \
    "https://raw.githubusercontent.com/sw-capstone/bareum-server/$COMMIT_SHA/infra/$file" \
    --output "$target"
  [[ -s "$target" && -f "$target" && ! -L "$target" ]] || fail "Missing or empty release file: $file"
done
(cd "$WORK/staged/infra" && sha256sum "${FILES[@]}") > "$WORK/staged.sha256"

mkdir -p -- "$WORK/backup"
backup_file() {
  local source="$1" relative="$2" required="$3"
  [[ ! -L "$source" ]] || fail "Refusing to back up a symlink: $source"
  if [[ ! -e "$source" ]]; then
    [[ "$required" == optional ]] || fail "Required live file is missing: $source"
    printf '%s\tmissing\t-\n' "$relative" >> "$WORK/backup/files.tsv"
    return
  fi
  [[ -f "$source" && -r "$source" ]] || fail "Live file is not a readable regular file: $source"
  mkdir -p -- "$(dirname -- "$WORK/backup/$relative")"
  cp -- "$source" "$WORK/backup/$relative"
  chmod 600 "$WORK/backup/$relative"
  printf '%s\tpresent\t%s\n' "$relative" "$(stat -c %a "$source")" >> "$WORK/backup/files.tsv"
}

for file in "${FILES[@]}"; do
  if [[ "$file" == scripts/reload-nginx-after-cert-renewal.sh ]]; then
    backup_file "$LETSENCRYPT_DIR/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh" \
      certbot/reload-nginx-after-cert-renewal.sh optional
  else
    backup_file "$INFRA_DIR/$file" "infra/$file" optional
  fi
done
backup_file "$INFRA_DIR/env/.env.deploy" infra/env/.env.deploy required
backup_file "$INFRA_DIR/env/.env.server" infra/env/.env.server required

for service in server ai nginx; do
  container=$(docker ps --all --quiet --filter label=com.docker.compose.project=bareum \
    --filter "label=com.docker.compose.service=$service")
  if [[ -z "$container" ]]; then
    [[ "$service" == ai ]] || fail "Existing $service container is required."
    printf '%s\tmissing\t-\t-\n' "$service" >> "$WORK/backup/images.tsv"
    continue
  fi
  [[ "$container" != *[[:space:]]* ]] || fail "Expected exactly one $service container."
  image_id=$(docker inspect --format '{{.Image}}' "$container")
  [[ "$image_id" =~ ^sha256:[a-f0-9]{64}$ ]] || fail "Invalid image ID for $service."
  running=$(docker inspect --format '{{.State.Running}}' "$container")
  [[ "$running" == true || "$running" == false ]] || fail "Invalid container state for $service."
  printf '%s\t%s\t%s\t%s\n' "$service" "$container" "$image_id" "$running" >> "$WORK/backup/images.tsv"
done

validate_release() {
  local staged="$WORK/staged/infra" config="$WORK/compose.json"
  local source target nginx_image
  local mounts=()
  bash -n "$staged/scripts/deploy.sh"
  bash -n "$staged/scripts/prepare-server-env.sh"
  sh -n "$staged/scripts/reload-nginx-after-cert-renewal.sh"
  awk '
    /^[[:space:]]*(#|$)/ { next }
    !/^[A-Za-z_][A-Za-z0-9_]*=/ { exit 1 }
    { key=$0; sub(/=.*/, "", key); if (seen[key]++) exit 1 }
  ' "$staged/env/.env.server.template" || fail 'Invalid or duplicate environment template entry.'

  if ! (
    unset AI_IMAGE SERVER_ENV_FILE AI_ENV_FILE RDS_CA_FILE HTTP_BIND_IP HTTP_PORT HTTPS_PORT
    unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES COMPOSE_ENV_FILES
    export SERVER_IMAGE
    docker compose --project-name bareum --project-directory "$INFRA_DIR" \
      -f "$staged/docker-compose.yml" --env-file "$INFRA_DIR/env/.env.deploy" \
      config --format json
  ) > "$config" 2> "$WORK/compose-error.log"; then
    fail 'Candidate Compose validation failed. Check the Compose file and deployment environment.'
  fi
  jq -e --arg source "$INFRA_DIR/nginx/default.conf" '
    .services.server and .services.ai and .services.nginx and
    ([.services.nginx.volumes[]? | select(.type == "bind" and .source == $source and
      .target == "/etc/nginx/conf.d/default.conf" and .read_only == true)] | length == 1) and
    ([.services.nginx.volumes[]? | select(.type == "bind" and
      .target == "/etc/letsencrypt" and .read_only == true)] | length == 1)
  ' "$config" >/dev/null || fail 'Candidate Compose must mount the Nginx configuration and certificates read-only.'

  while IFS=$'\t' read -r source target; do
    if [[ "$source" == "$INFRA_DIR/nginx/default.conf" && "$target" == /etc/nginx/conf.d/default.conf ]]; then
      source="$staged/nginx/default.conf"
    fi
    [[ -e "$source" && -r "$source" ]] || fail "Missing or unreadable bind mount for: $target"
  done < <(jq -r '.services[].volumes[]? | select(.type == "bind") | [.source, .target] | @tsv' "$config")

  while IFS=$'\t' read -r source target; do
    if [[ "$target" == /etc/nginx/conf.d/default.conf ]]; then source="$staged/nginx/default.conf"; fi
    [[ "$source" != *,* && "$target" != *,* ]] || fail 'Commas in Nginx mount paths are unsupported.'
    mounts+=(--mount "type=bind,source=$source,target=$target,readonly")
  done < <(jq -r '.services.nginx.volumes[]? | select(.type == "bind") | [.source, .target] | @tsv' "$config")
  nginx_image=$(jq -er '.services.nginx.image | select(type == "string" and length > 0)' "$config")
  CHECK_CONTAINER="bareum-infra-check-${WORK##*.}"
  if ! timeout --kill-after=5s 120s docker run --rm --name "$CHECK_CONTAINER" \
    --network none --entrypoint nginx "${mounts[@]}" "$nginx_image" -t \
    > "$WORK/nginx-check.log" 2>&1; then
    fail 'Candidate Nginx validation failed. Check configuration, certificates and image availability.'
  fi
  CHECK_CONTAINER=''
  printf '%s\n' "$nginx_image" > "$WORK/nginx-image"
  rm -- "$config" "$WORK/compose-error.log" "$WORK/nginx-check.log"
  printf '%s\n' 'Shell, environment template, Compose, bind mounts and Nginx checks passed.' > "$WORK/validation.txt"
}

validate_release
printf '%s\n' "$COMMIT_SHA" > "$WORK/commit-sha"
printf '%s\n' "$SERVER_IMAGE" > "$WORK/server-image"
printf '%s\n' validated > "$WORK/status"
release="$RELEASES_DIR/$COMMIT_SHA-${WORK##*.}"
mv -T -- "$WORK" "$release"
WORK=''
printf 'Release prepared: %s\n' "$release"
if [[ "$APPLY" == --apply ]]; then
  # Actions 잠금(8)은 유지하고, 적용 스크립트가 내부 잠금(9)을 다시 획득합니다.
  exec 9>&-
  bash "$release/staged/infra/scripts/deploy.sh" server "$SERVER_IMAGE" "$release"
fi
