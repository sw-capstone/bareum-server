#!/usr/bin/env bash
set -Eeuo pipefail
: "${EC2_INSTANCE_ID:?Set the EC2_INSTANCE_ID repository variable.}"
: "${DEPLOY_SERVICE:?Deployment service is required.}"
: "${DEPLOY_IMAGE:?Deployment image is required.}"
[[ "$EC2_INSTANCE_ID" =~ ^i-[a-f0-9]{17}$ ]] || { echo 'Invalid EC2 instance ID.' >&2; exit 1; }
[[ "$DEPLOY_SERVICE" == server || "$DEPLOY_SERVICE" == ai ]] || exit 1
[[ "$DEPLOY_IMAGE" =~ ^ghcr\.io/sw-capstone/bareum-${DEPLOY_SERVICE}@sha256:[a-f0-9]{64}$ ]] \
  || { echo 'Expected the published image digest for the target service.' >&2; exit 1; }

if [[ "$DEPLOY_SERVICE" == server ]]; then
  [[ "${DEPLOY_COMMIT_SHA:-}" =~ ^[a-f0-9]{40}$ ]] \
    || { echo 'A full deployment commit SHA is required.' >&2; exit 1; }
  source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
  prepare_checksum=$(sha256sum "$source_dir/infra/scripts/prepare-infra-release.sh")
  prepare_checksum="${prepare_checksum%% *}"
  remote_command=$(
    printf "bash -s -- '%s' '%s' '%s' <<'BAREUM_DEPLOY'\n" "$DEPLOY_COMMIT_SHA" "$DEPLOY_IMAGE" "$prepare_checksum"
    cat <<'REMOTE'
set -Eeuo pipefail
umask 077
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
curl --proto '=https' --tlsv1.2 --fail --silent --show-error --location \
  --retry 2 --connect-timeout 10 --max-time 60 \
  "https://raw.githubusercontent.com/sw-capstone/bareum-server/$1/infra/scripts/prepare-infra-release.sh" \
  --output "$work/prepare-infra-release.sh"
printf '%s  %s\n' "$3" "$work/prepare-infra-release.sh" | sha256sum --check --status
bash -n "$work/prepare-infra-release.sh"
bash "$work/prepare-infra-release.sh" "$1" "$2" --apply
BAREUM_DEPLOY
REMOTE
  )
else
  remote_command="flock --wait 600 /opt/bareum/.actions-deploy.lock bash /opt/bareum/infra/scripts/deploy.sh $DEPLOY_SERVICE $DEPLOY_IMAGE"
fi
parameters=$(jq -n --arg command "$remote_command" \
  '{commands: [$command], executionTimeout: ["1200"]}')
command_id=$(aws ssm send-command \
  --instance-ids "$EC2_INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --timeout-seconds 60 \
  --parameters "$parameters" \
  --query Command.CommandId --output text)
printf 'SSM command ID: %s\n' "$command_id"
printf 'SSM command ID: %s\n' "$command_id" >> "$GITHUB_STEP_SUMMARY"

result_file="$RUNNER_TEMP/ssm-result.json"
error_file="$RUNNER_TEMP/ssm-error.txt"
deadline=$((SECONDS + 1500))
while (( SECONDS < deadline )); do
  if aws ssm get-command-invocation --command-id "$command_id" \
    --instance-id "$EC2_INSTANCE_ID" --output json > "$result_file" 2> "$error_file"; then
    status=$(jq -r '.Status' "$result_file")
    case "$status" in
      Pending|InProgress|Delayed|Cancelling) ;;
      Success|Failed|Cancelled|TimedOut)
        jq -r '.StandardOutputContent, .StandardErrorContent' "$result_file"
        printf 'Deployment status: %s\n' "$status" >> "$GITHUB_STEP_SUMMARY"
        [[ "$status" == Success && "$(jq -r '.ResponseCode' "$result_file")" == 0 ]]
        exit "$?"
        ;;
      *) printf 'Unexpected SSM status: %s\n' "$status" >&2; exit 1 ;;
    esac
  elif ! grep -q 'InvocationDoesNotExist' "$error_file"; then
    cat "$error_file" >&2
    exit 1
  fi
  sleep 5
done
printf 'Polling timed out. Check SSM command %s before retrying; it may still be running.\n' "$command_id" >&2
exit 1
