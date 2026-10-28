#!/bin/sh
set -eu

compose() {
  /usr/bin/docker compose \
    --project-name bareum \
    -f /opt/bareum/infra/docker-compose.yml \
    --env-file /opt/bareum/infra/env/.env.deploy \
    "$@"
}

compose exec -T nginx nginx -t
compose exec -T nginx nginx -s reload
