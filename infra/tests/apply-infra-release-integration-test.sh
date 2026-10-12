#!/usr/bin/env bash
set -Eeuo pipefail
SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK=$(mktemp -d)
REAL_DOCKER=$(command -v docker)
PROJECT="bareum-release-test-${WORK##*.}"
PROJECT="${PROJECT,,}"
export REAL_DOCKER PROJECT
export BAREUM_ROOT="$WORK/bareum" LETSENCRYPT_DIR="$WORK/letsencrypt"
export OLD_TAG="$PROJECT:old" NEW_TAG="$PROJECT:new"
export TARGET_TAG="$NEW_TAG" DOCKER_EVENTS="$WORK/docker-events"
FAKE_DIGEST="ghcr.io/sw-capstone/bareum-server@sha256:$(printf '%064d' 99)"
export FAKE_DIGEST
cleanup() {
  local result=$?
  trap - EXIT
  "$REAL_DOCKER" ps -aq --filter "label=com.docker.compose.project=$PROJECT" | xargs -r "$REAL_DOCKER" rm -f >/dev/null 2>&1 || true
  "$REAL_DOCKER" network rm "${PROJECT}_default" >/dev/null 2>&1 || true
  "$REAL_DOCKER" image rm "$OLD_TAG" "$NEW_TAG" >/dev/null 2>&1 || true
  rm -rf "$WORK"
  exit "$result"
}
trap cleanup EXIT
mkdir -p "$WORK/bin" "$WORK/build" "$BAREUM_ROOT/infra/"{env,nginx,scripts,certs} "$BAREUM_ROOT/webroot" "$LETSENCRYPT_DIR/live/api.bareum.site"
cat > "$WORK/build/default.conf" <<'CONF'
server {
  listen 8080;
  listen 8000;
  location = /health { default_type application/json; return 200 '{"status":"ok"}'; }
}
CONF
cat > "$WORK/build/Dockerfile" <<'DOCKERFILE'
FROM nginx:1.30-alpine
ARG RELEASE
LABEL test.release=$RELEASE
COPY default.conf /etc/nginx/conf.d/default.conf
DOCKERFILE
"$REAL_DOCKER" build --quiet --build-arg RELEASE=old -t "$OLD_TAG" "$WORK/build" >/dev/null
"$REAL_DOCKER" build --quiet --build-arg RELEASE=new -t "$NEW_TAG" "$WORK/build" >/dev/null
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=api.bareum.site \
  -keyout "$LETSENCRYPT_DIR/live/api.bareum.site/privkey.pem" \
  -out "$LETSENCRYPT_DIR/live/api.bareum.site/fullchain.pem" >/dev/null 2>&1
cp "$LETSENCRYPT_DIR/live/api.bareum.site/fullchain.pem" "$BAREUM_ROOT/infra/certs/rds-ca.pem"
cp "$SOURCE_DIR/nginx/default.conf" "$BAREUM_ROOT/infra/nginx/default.conf"
cp "$SOURCE_DIR/scripts/"{deploy.sh,prepare-server-env.sh,reload-nginx-after-cert-renewal.sh} "$BAREUM_ROOT/infra/scripts/"
cp "$SOURCE_DIR/env/.env.server.template" "$BAREUM_ROOT/infra/env/"
printf 'DB_PASSWORD=integration-secret\n' > "$BAREUM_ROOT/infra/env/.env.server"
printf 'AI_UNCHANGED=true\n' > "$BAREUM_ROOT/infra/env/.env.ai"
cat > "$BAREUM_ROOT/infra/env/.env.deploy" <<ENV
SERVER_IMAGE=$OLD_TAG
AI_IMAGE=$OLD_TAG
SERVER_ENV_FILE=./env/.env.server
RDS_CA_FILE=./certs/rds-ca.pem
ENV
cat > "$BAREUM_ROOT/infra/docker-compose.yml" <<COMPOSE
services:
  nginx:
    image: nginx:1.30-alpine
    ports: ["127.0.0.1::80", "127.0.0.1::443"]
    volumes:
      - ./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro
      - $LETSENCRYPT_DIR:/etc/letsencrypt:ro
      - $BAREUM_ROOT/webroot:/var/www/certbot:ro
    healthcheck:
      test: [CMD, wget, -q, -O, /dev/null, "http://127.0.0.1:8082/health"]
      interval: 1s
      retries: 30
  server:
    image: \${SERVER_IMAGE:?}
    env_file: ./env/.env.server
    volumes: ["\${RDS_CA_FILE:?}:/etc/ssl/certs/rds-ca.pem:ro"]
    healthcheck:
      test: [CMD, wget, -q, -O, /dev/null, "http://127.0.0.1:8080/health"]
      interval: 1s
      retries: 30
  ai:
    image: \${AI_IMAGE:?}
    env_file: ./env/.env.ai
COMPOSE
cp -R "$BAREUM_ROOT/infra" "$WORK/candidate"
printf '\n# candidate deployment\n' >> "$WORK/candidate/nginx/default.conf"
cat > "$WORK/candidate/scripts/prepare-server-env.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cp "$script_dir/../env/.env.server.template" "$script_dir/../env/.env.server"
chmod 600 "$script_dir/../env/.env.server"
SCRIPT
export CANDIDATE="$WORK/candidate"
cat > "$WORK/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
while [[ "$1" != https://* ]]; do shift; done
cp "$CANDIDATE/${1#*/infra/}" "$3"
MOCK
cat > "$WORK/bin/docker" <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
args=("$@")
for ((i=0; i<${#args[@]}; i++)); do
  if [[ "${args[i]}" == bareum ]]; then args[i]="$PROJECT"; fi
  if [[ "${args[i]}" == label=com.docker.compose.project=bareum ]]; then args[i]="label=com.docker.compose.project=$PROJECT"; fi
  if [[ "${args[i]}" == "$FAKE_DIGEST" ]]; then args[i]="$TARGET_TAG"; fi
done
if [[ "$1" == pull ]]; then
  "$REAL_DOCKER" image inspect "${args[1]}" >/dev/null
  exit
fi
if [[ "$1" == compose ]]; then
  selected=$(awk -F= '$1 == "SERVER_IMAGE" {print $2}' "$BAREUM_ROOT/infra/env/.env.deploy")
  if [[ "${SERVER_IMAGE:-$selected}" == "$FAKE_DIGEST" ]]; then export SERVER_IMAGE="$TARGET_TAG"; fi
  if [[ " $* " == *' up -d '* && ( "${!#}" == server || "${!#}" == nginx ) ]]; then
    "$REAL_DOCKER" "${args[@]}"
    service="${!#}"
    container=$("$REAL_DOCKER" ps -q --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.service=$service")
    image=$("$REAL_DOCKER" inspect --format '{{.Image}}' "$container")
    printf '%s\t%s\t%s\n' "$service" "$container" "$image" >> "$DOCKER_EVENTS"
    exit 0
  fi
fi
exec "$REAL_DOCKER" "${args[@]}"
WRAPPER
printf '#!/bin/sh\nexit 0\n' > "$WORK/bin/aws"
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH"
compose() {
  docker compose --project-name bareum --project-directory "$BAREUM_ROOT/infra" \
    -f "$BAREUM_ROOT/infra/docker-compose.yml" --env-file "$BAREUM_ROOT/infra/env/.env.deploy" "$@"
}
compose up -d --wait --wait-timeout 60 >/dev/null
server_before=$(compose ps -q server)
nginx_before=$(compose ps -q nginx)
ai_before=$(compose ps -q ai)
bash "$SOURCE_DIR/scripts/prepare-infra-release.sh" 0123456789abcdef0123456789abcdef01234567 "$FAKE_DIGEST" > "$WORK/prepared"
release=$(sed -n 's/^Release prepared: //p' "$WORK/prepared")
bash "$release/staged/infra/scripts/deploy.sh" server "$FAKE_DIGEST" "$release"
[[ "$(cat "$release/status")" == applied ]]
[[ "$(compose ps -q server)" != "$server_before" && "$(compose ps -q nginx)" != "$nginx_before" ]]
[[ "$(compose ps -q ai)" == "$ai_before" ]]
[[ "$(docker inspect --format '{{.Image}}' "$(compose ps -q server)")" == "$(docker image inspect --format '{{.Id}}' "$NEW_TAG")" ]]
cmp "$CANDIDATE/nginx/default.conf" "$BAREUM_ROOT/infra/nginx/default.conf"
cmp "$CANDIDATE/scripts/reload-nginx-after-cert-renewal.sh" "$LETSENCRYPT_DIR/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh"
[[ "$(stat -c %a "$LETSENCRYPT_DIR/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh")" == 755 ]]
[[ "$(compose exec -T nginx wget -q -O - http://127.0.0.1:8082/health)" == '{"status":"ok"}' ]]
[[ "$(cat "$BAREUM_ROOT/infra/env/.env.ai")" == AI_UNCHANGED=true ]]
printf 'PASS: real containers replaced for Server and Nginx; AI preserved; candidate configuration and hook installed.\n'

baseline="$WORK/rollback-baseline"
mkdir -p "$baseline/infra"
files=(docker-compose.yml nginx/default.conf scripts/deploy.sh scripts/prepare-server-env.sh env/.env.server.template)
for file in "${files[@]}" env/.env.server env/.env.ai; do
  mkdir -p "$(dirname "$baseline/infra/$file")"
  cp -p "$BAREUM_ROOT/infra/$file" "$baseline/infra/$file"
done
cp -p "$BAREUM_ROOT/infra/env/.env.deploy" "$baseline/deploy.env"
cp -p "$LETSENCRYPT_DIR/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh" "$baseline/hook.sh"
server_image_before=$(docker inspect --format '{{.Image}}' "$(compose ps -q server)")
nginx_image_before=$(docker inspect --format '{{.Image}}' "$(compose ps -q nginx)")
find "$LETSENCRYPT_DIR/live" "$BAREUM_ROOT/infra/certs" -type f -print0 | sort -z | xargs -0 sha256sum > "$baseline/certificates.sha256"

TARGET_TAG="$OLD_TAG"
FAKE_DIGEST="ghcr.io/sw-capstone/bareum-server@sha256:$(printf '%064d' 98)"
awk -v image="$OLD_TAG" '{sub("image: nginx:1.30-alpine", "image: " image); print}' \
  "$CANDIDATE/docker-compose.yml" > "$WORK/failing-compose.yml"
mv "$WORK/failing-compose.yml" "$CANDIDATE/docker-compose.yml"
awk '{sub("proxy_pass http://spring_backend;", "default_type application/json; return 200 '\''{\"status\":\"unhealthy\"}'\'';"); print}' \
  "$CANDIDATE/nginx/default.conf" > "$WORK/failing-nginx.conf"
mv "$WORK/failing-nginx.conf" "$CANDIDATE/nginx/default.conf"
printf '\nTEST_RELEASE=rollback-candidate\n' >> "$CANDIDATE/env/.env.server.template"
printf '\n# rollback candidate hook\n' >> "$CANDIDATE/scripts/reload-nginx-after-cert-renewal.sh"
bash "$SOURCE_DIR/scripts/prepare-infra-release.sh" 0123456789abcdef0123456789abcdef01234567 "$FAKE_DIGEST" > "$WORK/prepared-rollback"
release=$(sed -n 's/^Release prepared: //p' "$WORK/prepared-rollback")
: > "$DOCKER_EVENTS"
result=0
bash "$release/staged/infra/scripts/deploy.sh" server "$FAKE_DIGEST" "$release" > "$WORK/rollback.log" 2>&1 || result=$?
if [[ "$result" != 1 ]]; then cat "$WORK/rollback.log" >&2; exit 1; fi
grep -q 'Post-deployment Nginx route health check failed.' "$WORK/rollback.log"
grep -q 'Rollback completed.' "$WORK/rollback.log"
[[ "$(cat "$release/status")" == failed && ! -e "$BAREUM_ROOT/infra/.deploy-state/pending" ]]

candidate_image=$(docker image inspect --format '{{.Id}}' "$OLD_TAG")
[[ "$candidate_image" != "$server_image_before" && "$candidate_image" != "$nginx_image_before" ]]
[[ "$(cut -f1 "$DOCKER_EVENTS" | paste -sd , -)" == server,nginx,server,nginx ]]
[[ "$(sed -n '1p' "$DOCKER_EVENTS" | cut -f3)" == "$candidate_image" ]]
[[ "$(sed -n '2p' "$DOCKER_EVENTS" | cut -f3)" == "$candidate_image" ]]
[[ "$(sed -n '3p' "$DOCKER_EVENTS" | cut -f3)" == "$server_image_before" ]]
[[ "$(sed -n '4p' "$DOCKER_EVENTS" | cut -f3)" == "$nginx_image_before" ]]
[[ "$(docker inspect --format '{{.Image}}' "$(compose ps -q server)")" == "$server_image_before" ]]
[[ "$(docker inspect --format '{{.Image}}' "$(compose ps -q nginx)")" == "$nginx_image_before" ]]
[[ "$(compose ps -q ai)" == "$ai_before" ]]
for file in "${files[@]}" env/.env.server env/.env.ai; do
  cmp "$baseline/infra/$file" "$BAREUM_ROOT/infra/$file"
  [[ "$(stat -c %a "$baseline/infra/$file")" == "$(stat -c %a "$BAREUM_ROOT/infra/$file")" ]]
done
awk -v image="$server_image_before" '/^SERVER_IMAGE=/ {print "SERVER_IMAGE=" image; next} {print}' "$baseline/deploy.env" > "$baseline/expected-deploy.env"
cmp "$baseline/expected-deploy.env" "$BAREUM_ROOT/infra/env/.env.deploy"
cmp "$baseline/hook.sh" "$LETSENCRYPT_DIR/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh"
[[ "$(stat -c %a "$LETSENCRYPT_DIR/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh")" == 755 ]]
sha256sum --check --status "$baseline/certificates.sha256"
[[ "$(compose exec -T nginx wget -q -O - http://127.0.0.1:8082/health)" == '{"status":"ok"}' ]]
printf 'PASS: real post-deployment failure restored Server/Nginx images, configuration, environment, hook and health response; AI and certificates preserved.\n'
