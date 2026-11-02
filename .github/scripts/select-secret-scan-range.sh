#!/usr/bin/env bash
set -Eeuo pipefail

case "${EVENT_NAME:-}" in
  pull_request|push) ;;
  *)
    printf '%s\n' '--all'
    exit 0
    ;;
esac

if [[ ! "${HEAD_SHA:-}" =~ ^[0-9a-f]{40}([0-9a-f]{24})?$ ]] ||
  ! git cat-file -e "${HEAD_SHA}^{commit}" 2>/dev/null; then
  printf 'Unable to resolve the scan head; scanning checked-out history.\n' >&2
  git rev-parse --verify 'HEAD^{commit}'
  exit 0
fi

if [[ ! "${BASE_SHA:-}" =~ ^[0-9a-f]{40}([0-9a-f]{24})?$ ]] ||
  ! git cat-file -e "${BASE_SHA}^{commit}" 2>/dev/null; then
  printf 'Unable to resolve the scan base; scanning head history.\n' >&2
  printf '%s\n' "$HEAD_SHA"
  exit 0
fi

if git diff --quiet --no-ext-diff --no-textconv "$BASE_SHA" "$HEAD_SHA" -- \
  .gitleaks.toml harness/secret-scan-policy.toml; then
  printf '%s..%s\n' "$BASE_SHA" "$HEAD_SHA"
else
  printf 'Secret scan rules changed or could not be compared; scanning head history.\n' >&2
  printf '%s\n' "$HEAD_SHA"
fi
