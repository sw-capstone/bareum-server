#!/usr/bin/env bash
set -Eeuo pipefail
server=true
infra=true

print_selection() {
  printf 'server=%s\ninfra=%s\n' "$server" "$infra"
  printf 'Selected CI checks: server=%s infra=%s\n' "$server" "$infra" >&2
}

case "${EVENT_NAME:-}" in
  pull_request|push) ;;
  *)
    print_selection
    exit 0
    ;;
esac

if [[ ! "${BASE_SHA:-}" =~ ^[0-9a-f]{40}([0-9a-f]{24})?$ ||
      ! "${HEAD_SHA:-}" =~ ^[0-9a-f]{40}([0-9a-f]{24})?$ ]] ||
  ! git cat-file -e "${BASE_SHA}^{commit}" 2>/dev/null ||
  ! git cat-file -e "${HEAD_SHA}^{commit}" 2>/dev/null; then
  printf 'Unable to resolve the change range; running all checks.\n' >&2
  print_selection
  exit 0
fi

changed_files=$(mktemp)
trap 'rm -f -- "$changed_files"' EXIT
if ! git diff --no-ext-diff --no-textconv --no-renames --name-only -z "$BASE_SHA" "$HEAD_SHA" -- > "$changed_files"; then
  printf 'Unable to read changed files; running all checks.\n' >&2
  print_selection
  exit 0
fi

server=false
infra=false
while IFS= read -r -d '' file; do
  case "$file" in
    README.md|AGENTS.md|docs/*.md|.github/pull_request_template.md|harness/README.md|harness/AGENTS.md)
      ;;
    *)
      server=true
      ;;
  esac
  case "$file" in
    infra/*|.github/workflows/*|.github/scripts/*|.github/tests/*|Dockerfile|.dockerignore)
      infra=true
      ;;
  esac
done < "$changed_files"

if [[ "$EVENT_NAME" == push && "$server" == true ]]; then
  infra=true
fi

print_selection
