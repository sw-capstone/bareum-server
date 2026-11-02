#!/usr/bin/env bash
set -Eeuo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
selector="$source_dir/scripts/select-secret-scan-range.sh"
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
passed=0

repo="$work/repo"
git init --quiet --initial-branch=develop "$repo"
git -C "$repo" config user.name Fixture
git -C "$repo" config user.email fixture@example.invalid
git -C "$repo" config commit.gpgsign false
git -C "$repo" config core.hooksPath /dev/null
mkdir -p "$repo/harness"
cp "$source_dir/../.gitleaks.toml" "$repo/.gitleaks.toml"
cp "$source_dir/../harness/secret-scan-policy.toml" "$repo/harness/secret-scan-policy.toml"
printf 'old history\n' > "$repo/README.md"
git -C "$repo" add .
git -C "$repo" commit --quiet -m base
base=$(git -C "$repo" rev-parse HEAD)

git -C "$repo" switch --quiet -c unmerged
printf 'GITHUB_TOKEN=%s%s\n' 'ghp_' 'Ab3Cd4Ef5Gh6Ij7Kl8Mn9Op0Qr1St2Uv3Wx4' > "$repo/unmerged.env"
git -C "$repo" add .
git -C "$repo" commit --quiet -m unmerged
unmerged=$(git -C "$repo" rev-parse HEAD)
git -C "$repo" update-ref refs/remotes/origin/unmerged "$unmerged"
git -C "$repo" switch --quiet develop
git -C "$repo" branch --quiet -D unmerged

printf 'GITHUB_TOKEN=%s%s\n' 'ghp_' 'Ab3Cd4Ef5Gh6Ij7Kl8Mn9Op0Qr1St2Uv3Wx4' > "$repo/pushed.env"
git -C "$repo" add .
git -C "$repo" commit --quiet -m intermediate
intermediate=$(git -C "$repo" rev-parse HEAD)
rm "$repo/pushed.env"
git -C "$repo" add -u
git -C "$repo" commit --quiet -m remove
removed=$(git -C "$repo" rev-parse HEAD)
printf 'clean change\n' >> "$repo/README.md"
git -C "$repo" add .
git -C "$repo" commit --quiet -m clean
head=$(git -C "$repo" rev-parse HEAD)
event=push
new_commits=$(printf '%s\n' "$intermediate" "$removed" "$head")
head_history=$(printf '%s\n' "$base" "$intermediate" "$removed" "$head")
all_history=$(printf '%s\n' "$base" "$unmerged" "$intermediate" "$removed" "$head")

check_commits() {
  local label="$1" expected actual
  expected=$(printf '%s\n' "$2" | sort)
  scan_range=$(cd "$repo" && EVENT_NAME="$event" BASE_SHA="$base" HEAD_SHA="$head" bash "$selector")
  actual=$(git -C "$repo" log --format=%H "$scan_range" -- | sort)
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL: %s\nExpected commits:\n%s\nActual commits:\n%s\n' "$label" "$expected" "$actual" >&2
    exit 1
  fi
  passed=$((passed + 1))
  printf 'PASS: %s\n' "$label"
}

check_commits 'push excludes old and unmerged history but includes deleted intermediate content' "$new_commits"
event=pull_request
check_commits 'PR retains its included commit range' "$new_commits"
event=workflow_dispatch
check_commits 'manual execution includes remote branch history' "$all_history"
event=unknown
check_commits 'unknown event retains full history scan' "$all_history"
event=''
check_commits 'missing event retains full history scan' "$all_history"

event=push
original_base="$base"
for base in '' invalid "$(printf '%040d' 0)" "$(printf '%040d' 1)"; do
  check_commits 'missing, invalid or unavailable base falls back to head history' "$head_history"
done
base="$original_base"
original_head="$head"
for head in '' invalid "$(printf '%040d' 1)"; do
  check_commits 'missing, invalid or unavailable head falls back to checked-out history' "$head_history"
done
head="$original_head"

base="$head"
check_commits 'empty push range contains no new commits' ''
base="$unmerged"
check_commits 'rewritten history scans newly reachable commits without the old branch' "$new_commits"
base="$original_base"

for file in .gitleaks.toml harness/secret-scan-policy.toml; do
  printf '\n# changed\n' >> "$repo/$file"
  git -C "$repo" add "$file"
  git -C "$repo" commit --quiet -m rules
  head=$(git -C "$repo" rev-parse HEAD)
  head_history=$(printf '%s\n' "$head_history" "$head")
  for event in push pull_request; do
    check_commits "$event rule change rescans head history without unrelated refs: $file" "$head_history"
  done
  base="$head"
done

original_repo="$repo"
repo="$work/shallow"
git clone --quiet --depth=1 "file://$original_repo" "$repo"
event=push
base="$original_base"
check_commits 'unavailable shallow base scans all available head history' "$head"
repo="$original_repo"

mkdir "$work/bin"
export REAL_GIT
REAL_GIT=$(command -v git)
cat > "$work/bin/git" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "$1" == diff ]]; then
  exit 2
fi
exec "$REAL_GIT" "$@"
MOCK
chmod +x "$work/bin/git"
PATH="$work/bin:$PATH" check_commits 'failed rule comparison falls back to head history' "$head_history"

if [[ -n "${GITLEAKS_BINARY:-}" ]]; then
  base="$original_base"
  head="$original_head"
  export PYTHONPATH="$source_dir/../harness/src"
  for event in push workflow_dispatch; do
    scan_range=$(cd "$repo" && EVENT_NAME="$event" BASE_SHA="$base" HEAD_SHA="$head" bash "$selector")
    status=0
    python3 -m project_harness.secret_scan --root "$repo" --log-opts="$scan_range" > "$work/scan.log" 2>&1 || status=$?
    if [[ "$status" != 1 ]]; then
      printf 'FAIL: %s must block the synthetic token in intermediate history\n' "$event" >&2
      exit 1
    fi
    if [[ "$event" == push ]]; then
      jq -e --arg commit "$intermediate" '.errors > 0 and all(.findings[]; .commit == $commit)' \
        "$repo/harness/reports/secret-scan.json" > /dev/null
    else
      jq -e --arg commit "$unmerged" '.errors > 0 and any(.findings[]; .commit == $commit)' \
        "$repo/harness/reports/secret-scan.json" > /dev/null
    fi
    passed=$((passed + 1))
    printf 'PASS: %s scanner findings match the selected history\n' "$event"
  done
  event=push
  base="$removed"
  scan_range=$(cd "$repo" && EVENT_NAME="$event" BASE_SHA="$base" HEAD_SHA="$head" bash "$selector")
  if ! python3 -m project_harness.secret_scan --root "$repo" --log-opts="$scan_range" > "$work/scan.log" 2>&1; then
    printf 'FAIL: clean push must pass despite tokens in old and unmerged history\n' >&2
    exit 1
  fi
  jq -e '.errors == 0 and .findings == []' "$repo/harness/reports/secret-scan.json" > /dev/null
  passed=$((passed + 1))
  printf 'PASS: clean push is not blocked by old or unrelated findings\n'
fi

printf 'PASS: %s secret scan range scenarios\n' "$passed"
