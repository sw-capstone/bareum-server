#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
: "${SMOKE_IMAGE:?Set the locally built ARM64 image.}"
[[ "$(docker image inspect "$SMOKE_IMAGE" --format '{{.Os}}/{{.Architecture}}')" == linux/arm64 ]]
work=$(mktemp -d)
export SERVER_IMAGE="$SMOKE_IMAGE"
export AI_IMAGE=nginx:1.30-alpine
export SERVER_ENV_FILE="$work/server.env"
export RDS_CA_FILE="$work/unused-ca.pem"
export CI_AI_CONFIG="$PWD/.github/fixtures/ai-nginx.conf"
export HTTP_BIND_IP=127.0.0.1 HTTP_PORT=0 HTTPS_PORT=0
export CI_CERTBOT_WEBROOT="$work/certbot"
export CI_LETSENCRYPT_DIR="$work/letsencrypt"
export AI_ENV_FILE="$work/unused-ai.env"
project="bareum-smoke-$(basename "$work" | tr '[:upper:].' '[:lower:]-')"
compose=(docker compose -p "$project" -f infra/docker-compose.yml -f .github/compose.smoke.yml)
cleanup() {
  status=$?
  if (( status != 0 )); then "${compose[@]}" logs --no-color --tail 100 >&2 || true; fi
  "${compose[@]}" down --volumes --remove-orphans || true
  rm -rf -- "$work"
  exit "$status"
}
trap cleanup EXIT
# 테스트 DB는 TLS 없이 연결합니다. 운영 인증서와 Parameter Store는 사용하지 않습니다.
printf '%s\n' 'DB_URL=jdbc:postgresql://postgres:5432/bareum_ci' \
  'DB_USERNAME=bareum_ci' 'DB_PASSWORD=ci-only-password' \
  'EMAIL_VERIFICATION_HASH_SECRET=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=' \
  'EMAIL_VERIFICATION_OUTBOX_ENABLED=false' \
  'SERVER_PORT=8080' 'SPRING_JPA_SHOW_SQL=false' > "$SERVER_ENV_FILE"
touch "$RDS_CA_FILE"
certificate_dir="$CI_LETSENCRYPT_DIR/live/api.bareum.site"
mkdir -p "$certificate_dir" "$CI_CERTBOT_WEBROOT/.well-known/acme-challenge"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj '/CN=api.bareum.site' -addext 'subjectAltName=DNS:api.bareum.site' \
  -keyout "$certificate_dir/privkey.pem" -out "$certificate_dir/fullchain.pem"
printf 'bareum-acme-ok\n' > "$CI_CERTBOT_WEBROOT/.well-known/acme-challenge/smoke-check"
"${compose[@]}" config --quiet
"${compose[@]}" up -d --wait --wait-timeout 180 --no-build
"${compose[@]}" exec -T nginx nginx -t
http_address=$("${compose[@]}" port nginx 80)
https_address=$("${compose[@]}" port nginx 443)
redirect=$(curl --fail --silent --show-error --noproxy '*' --max-time 10 \
  --output /dev/null --write-out '%{http_code} %{redirect_url}' "http://$http_address/health?smoke=1")
[[ "$redirect" == '308 https://api.bareum.site/health?smoke=1' ]]
[[ "$(curl --fail --silent --show-error --noproxy '*' --max-time 10 \
  "http://$http_address/.well-known/acme-challenge/smoke-check")" == bareum-acme-ok ]]
curl --fail --silent --show-error --noproxy '*' --max-time 10 \
  --cacert "$certificate_dir/fullchain.pem" \
  --connect-to "api.bareum.site:443:$https_address" \
  https://api.bareum.site/health | jq -e '.status == "ok"'
"${compose[@]}" exec -T server curl --fail --silent --show-error --max-time 10 \
  http://server:8080/health | jq -e '.status == "ok"'
# 실제 AI 이미지의 기동은 AI 레포에서 검사하고 여기서는 Nginx의 AI 라우팅을 확인합니다.
"${compose[@]}" exec -T server curl --fail --silent --show-error --max-time 10 \
  http://nginx:8081/health | jq -e '.status == "ok" and .backend == "ai-fixture"'
printf 'PASS: ARM64 server startup, database migrations, HTTPS, HTTP redirect, ACME, and AI routes\n'
