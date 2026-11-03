#!/usr/bin/env bash
set -Eeuo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/bin" "$work/server/infra"
export BAREUM_ROOT="$work/server" RUNNER_TEMP="$work"
export EC2_INSTANCE_ID=i-03d7d975865752a46
export GITHUB_OUTPUT="$work/outputs" GITHUB_STEP_SUMMARY="$work/summary"
export BASELINE_REMOTE="$work/remote.sh" BASELINE_STDOUT="$work/stdout" BASELINE_STDERR="$work/stderr"
cat > "$work/bin/aws" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "$2" == send-command ]]; then
  while [[ "$1" != --parameters ]]; do shift; done
  jq -er '.commands[0]' <<< "$2" > "$BASELINE_REMOTE"
  echo baseline-fixture
else
  result=0
  bash "$BASELINE_REMOTE" > "$BASELINE_STDOUT" 2> "$BASELINE_STDERR" || result=$?
  status=Success
  if (( result != 0 )); then status=Failed; fi
  jq -n --arg status "$status" --argjson response "$result" --rawfile stdout "$BASELINE_STDOUT" \
    '{Status:$status,ResponseCode:$response,StandardOutputContent:$stdout,StandardErrorContent:""}'
fi
MOCK
chmod +x "$work/bin/aws"
export PATH="$work/bin:$PATH"

repo="$work/repo"
git init --quiet --initial-branch=develop "$repo"
git -C "$repo" config user.name Fixture
git -C "$repo" config user.email fixture@example.invalid
git -C "$repo" config commit.gpgsign false
git -C "$repo" config core.hooksPath /dev/null
mkdir -p "$repo/src" "$repo/docs"
printf 'old\n' > "$repo/src/App.java"
git -C "$repo" add .
git -C "$repo" commit --quiet -m deployed
deployed=$(git -C "$repo" rev-parse HEAD)
printf 'new\n' > "$repo/src/App.java"
git -C "$repo" add .
git -C "$repo" commit --quiet -m undeployed
undeployed=$(git -C "$repo" rev-parse HEAD)
printf 'documentation\n' > "$repo/docs/guide.md"
git -C "$repo" add .
git -C "$repo" commit --quiet -m documentation
head=$(git -C "$repo" rev-parse HEAD)

read_base() {
  : > "$GITHUB_OUTPUT"
  result=0
  bash "$source_dir/scripts/deploy-via-ssm.sh" --read-deployed-sha > "$work/read.log" 2>&1 || result=$?
  if (( result != 0 )); then return "$result"; fi
  base=$(sed -n 's/^sha=//p' "$GITHUB_OUTPUT")
}

check_selection() {
  local actual expected
  read_base
  actual=$(cd "$repo" && EVENT_NAME=push BASE_SHA="$base" HEAD_SHA="$head" \
    bash "$source_dir/scripts/select-ci-checks.sh")
  expected=$(printf 'server=%s\ninfra=%s' "$2" "$2")
  [[ "$actual" == "$expected" ]]
  printf 'PASS: %s\n' "$1"
}

check_selection 'first deployment without a state directory runs all checks' true
state="$BAREUM_ROOT/infra/.deploy-state"
mkdir "$state"
printf '%s\n' "$deployed" > "$state/server-deployed-sha"
check_selection 'documentation after a canceled or failed server deployment includes the undeployed change' true
[[ "$base" == "$deployed" ]]
scan_range=$(cd "$repo" && EVENT_NAME=push BASE_SHA="$base" HEAD_SHA="$head" \
  bash "$source_dir/scripts/select-secret-scan-range.sh")
actual_commits=$(git -C "$repo" log --format=%H "$scan_range" -- | sort)
expected_commits=$(printf '%s\n' "$undeployed" "$head" | sort)
[[ "$actual_commits" == "$expected_commits" ]]
printf 'PASS: secret scan also includes all undeployed commits before the documentation push\n'
printf '%s\n' "$head" > "$state/server-deployed-sha"
check_selection 'successful deployment advances the baseline and avoids redundant deployment' false
printf 'more documentation\n' >> "$repo/docs/guide.md"
git -C "$repo" add .
git -C "$repo" commit --quiet -m more-documentation
head=$(git -C "$repo" rev-parse HEAD)
check_selection 'documentation accumulated since a successful deployment skips server work' false

printf 'invalid\n' > "$state/server-deployed-sha"
check_selection 'invalid deployment state runs all checks' true
printf '%040d\n' 1 > "$state/server-deployed-sha"
check_selection 'unavailable deployed commit runs all checks' true
rm "$state/server-deployed-sha"
check_selection 'missing success marker runs all checks' true

printf '%s\n' "$deployed" > "$state/server-deployed-sha"
mkdir "$state/pending"
if read_base; then printf 'FAIL: incomplete recovery must stop baseline lookup\n' >&2; exit 1; fi
[[ ! -s "$GITHUB_OUTPUT" ]]
rmdir "$state/pending"
printf 'PASS: incomplete deployment blocks lookup even with an older success marker\n'
rm "$state/server-deployed-sha"
ln -s "$work/summary" "$state/server-deployed-sha"
if read_base; then printf 'FAIL: baseline symlink must be rejected\n' >&2; exit 1; fi
[[ ! -s "$GITHUB_OUTPUT" ]]
printf 'PASS: baseline symlinks are rejected without exporting their contents\n'
