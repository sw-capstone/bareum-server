#!/usr/bin/env bash
set -Eeuo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/bin"
export PATH="$work/bin:$PATH" RUNNER_TEMP="$work"
export EC2_INSTANCE_ID=i-03d7d975865752a46 DEPLOY_SERVICE=server
export DEPLOY_COMMIT_SHA=0123456789abcdef0123456789abcdef01234567
DEPLOY_IMAGE="ghcr.io/sw-capstone/bareum-server@sha256:$(printf '%064d' 1)"
export DEPLOY_IMAGE
export GITHUB_STEP_SUMMARY="$work/summary" MOCK_STATE="$work/state" MOCK_CALLS="$work/calls"
export MOCK_PARAMETERS="$work/parameters.json"
cat > "$work/bin/aws" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >> "$MOCK_CALLS"
if [[ "$2" == send-command ]]; then
  [[ "$MODE" != send-fail ]] || exit 1
  while [[ "$1" != --parameters ]]; do shift; done
  jq -e '.executionTimeout == ["1200"] and (.commands | length == 1)' <<< "$2" >/dev/null
  printf '%s\n' "$2" > "$MOCK_PARAMETERS"
  echo command-test
  exit
fi
if [[ "$MODE" == denied ]]; then echo AccessDeniedException >&2; exit 1; fi
if [[ "$MODE" == eventual && ! -f "$MOCK_STATE" ]]; then
  touch "$MOCK_STATE"
  echo InvocationDoesNotExist >&2
  exit 1
fi
if [[ "$MODE" == pending && ! -f "$MOCK_STATE" ]]; then
  touch "$MOCK_STATE"
  echo '{"Status":"InProgress"}'
  exit
fi
status=Success
response=0
case "$MODE" in
  failed) status=Failed; response=1 ;;
  cancelled) status=Cancelled; response=1 ;;
  timedout) status=TimedOut; response=1 ;;
  bad-response) response=1 ;;
esac
jq -n --arg status "$status" --argjson response "$response" \
  '{Status:$status,ResponseCode:$response,StandardOutputContent:"test output",StandardErrorContent:""}'
MOCK
cat > "$work/bin/sleep" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
chmod +x "$work/bin/aws" "$work/bin/sleep"
for mode in success eventual pending failed cancelled timedout bad-response denied send-fail; do
  export MODE="$mode"
  rm -f "$MOCK_STATE" "$MOCK_CALLS"
  result=0
  bash "$source_dir/scripts/deploy-via-ssm.sh" > "$work/output" 2>&1 || result=$?
  case "$mode" in
    success|eventual|pending) [[ "$result" == 0 ]] ;;
    *) [[ "$result" != 0 ]] ;;
  esac
  grep -q 'AWS-RunShellScript' "$MOCK_CALLS"
  printf 'PASS: %s\n' "$mode"
done
for invalid_commit in '' develop 01234567 '0123456789abcdef0123456789abcdef01234567;id'; do
  rm -f "$MOCK_CALLS"
  DEPLOY_COMMIT_SHA="$invalid_commit" bash "$source_dir/scripts/deploy-via-ssm.sh" > "$work/output" 2>&1 && exit 1
  [[ ! -e "$MOCK_CALLS" ]]
done
rm -f "$MOCK_CALLS"
DEPLOY_IMAGE='ghcr.io/other/image:latest' bash "$source_dir/scripts/deploy-via-ssm.sh" > "$work/output" 2>&1 && exit 1
[[ ! -e "$MOCK_CALLS" ]]
printf 'PASS: invalid commit and image rejected before SSM request\n'

MODE=success DEPLOY_SERVICE=ai DEPLOY_IMAGE="ghcr.io/sw-capstone/bareum-ai@sha256:$(printf '%064d' 1)" \
  DEPLOY_COMMIT_SHA='' bash "$source_dir/scripts/deploy-via-ssm.sh" > "$work/output" 2>&1
jq -er '.commands[0]' "$MOCK_PARAMETERS" | grep -Eq '^flock --wait 600 .*deploy.sh ai ghcr.io/sw-capstone/bareum-ai@sha256:'
printf 'PASS: AI deployment retains the existing image-only command\n'

mkdir -p "$work/repo/.github/scripts" "$work/repo/infra/scripts"
cp "$source_dir/scripts/deploy-via-ssm.sh" "$work/repo/.github/scripts/"
export MOCK_SOURCE="$work/repo/infra/scripts/prepare-infra-release.sh" MOCK_APPLIED="$work/applied" MOCK_DOWNLOAD="$work/download-path"
cat > "$MOCK_SOURCE" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ $# == 3 && "$1" == "$DEPLOY_COMMIT_SHA" && "$2" == "$DEPLOY_IMAGE" && "$3" == --apply ]]
touch "$MOCK_APPLIED"
if [[ "$BOOTSTRAP_MODE" == apply-fail ]]; then exit 7; fi
SCRIPT
cat > "$work/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
while [[ "$1" != https://* ]]; do shift; done
[[ "$1" == "https://raw.githubusercontent.com/sw-capstone/bareum-server/$DEPLOY_COMMIT_SHA/infra/scripts/prepare-infra-release.sh" && "$2" == --output ]]
printf '%s\n' "$3" > "$MOCK_DOWNLOAD"
[[ "$BOOTSTRAP_MODE" != download-fail ]] || exit 22
cp "$MOCK_SOURCE" "$3"
if [[ "$BOOTSTRAP_MODE" == tampered ]]; then printf '\n# modified\n' >> "$3"; fi
MOCK
chmod +x "$work/bin/curl"
for bootstrap_mode in success download-fail tampered apply-fail; do
  export MODE=success BOOTSTRAP_MODE="$bootstrap_mode"
  rm -f "$MOCK_APPLIED" "$MOCK_DOWNLOAD"
  bash "$work/repo/.github/scripts/deploy-via-ssm.sh" > "$work/output" 2>&1
  jq -er '.commands[0]' "$MOCK_PARAMETERS" > "$work/remote.sh"
  bash -n "$work/remote.sh"
  result=0
  bash "$work/remote.sh" > "$work/remote-output" 2>&1 || result=$?
  case "$bootstrap_mode" in
    success) [[ "$result" == 0 && -f "$MOCK_APPLIED" ]] ;;
    apply-fail) [[ "$result" == 7 && -f "$MOCK_APPLIED" ]] ;;
    *) [[ "$result" != 0 && ! -e "$MOCK_APPLIED" ]] ;;
  esac
  [[ ! -d "$(dirname -- "$(cat "$MOCK_DOWNLOAD")")" ]]
  printf 'PASS: generated remote command: %s\n' "$bootstrap_mode"
done
