#!/usr/bin/env bash
set -Eeuo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
selector="$source_dir/scripts/select-ci-checks.sh"
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
passed=0
fixture=0

new_repo() {
  fixture=$((fixture + 1))
  repo="$work/repo-$fixture"
  git init --quiet --initial-branch=develop "$repo"
  git -C "$repo" config user.name Fixture
  git -C "$repo" config user.email fixture@example.invalid
  git -C "$repo" config commit.gpgsign false
  git -C "$repo" config core.hooksPath /dev/null
  mkdir -p "$repo/docs" "$repo/src/main/java"
  printf 'initial\n' > "$repo/README.md"
  printf 'initial\n' > "$repo/docs/guide.md"
  printf 'initial\n' > "$repo/src/main/java/App.java"
  git -C "$repo" add .
  git -C "$repo" commit --quiet -m base
  base=$(git -C "$repo" rev-parse HEAD)
}

commit_changes() {
  git -C "$repo" add -A
  git -C "$repo" commit --quiet -m change
  head=$(git -C "$repo" rev-parse HEAD)
}

check_selection() {
  local label="$1" expected actual
  expected=$(printf 'server=%s\ninfra=%s' "$2" "${3:-false}")
  actual=$(cd "$repo" && EVENT_NAME="$event" BASE_SHA="$base" HEAD_SHA="$head" bash "$selector")
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL: %s\nExpected:\n%s\nActual:\n%s\n' "$label" "$expected" "$actual" >&2
    exit 1
  fi
  passed=$((passed + 1))
  printf 'PASS: %s\n' "$label"
}

check_file() {
  local file="$1"
  new_repo
  mkdir -p "$repo/$(dirname -- "$file")"
  printf 'changed\n' > "$repo/$file"
  commit_changes
  event=pull_request
  check_selection "$file" "$2" "${3:-false}"
}

for file in README.md AGENTS.md docs/guide/development.md .github/pull_request_template.md \
  harness/README.md harness/AGENTS.md; do
  check_file "$file" false
done
for file in harness/src/project_harness/checks.py harness/policy.json .gitleaks.toml \
  scripts/run-harness.sh scripts/test-harness.sh scripts/run-secret-scan.sh \
  src/main/java/App.java src/main/resources/application.yml src/main/resources/help.md src/test/java/AppTests.java \
  build.gradle.kts settings.gradle.kts gradlew gradlew.bat gradle/wrapper/gradle-wrapper.properties \
  gradle/testing.gradle.kts config/formatter/java.xml .github/compose.smoke.yml .github/fixtures/ai-nginx.conf scripts/new-script.sh \
  packages/contracts/content.schema.json .editorconfig docs/example.java CONTRIBUTING.md notes/meeting.md; do
  check_file "$file" true
done
for file in Dockerfile .dockerignore infra/nginx/default.conf infra/scripts/deploy.sh infra/env/.env.deploy.example \
  .github/workflows/ci.yml .github/scripts/check-ci.sh .github/tests/new-test.sh; do
  check_file "$file" true true
done
check_file 'src/main/java/Name With Spaces.java' true
check_file $'docs/line\nbreak.md' false

new_repo
mkdir -p "$repo/infra"
printf 'changed\n' > "$repo/infra/docker-compose.yml"
printf 'changed\n' > "$repo/src/main/java/App.java"
commit_changes
event=pull_request
check_selection 'source and infrastructure changes run all checks' true true

new_repo
mkdir -p "$repo/infra"
printf 'changed\n' > "$repo/.editorconfig"
printf 'changed\n' > "$repo/infra/docker-compose.yml"
commit_changes
check_selection 'non-infrastructure file first does not hide infrastructure changes' true true

new_repo
mkdir -p "$repo/infra"
printf 'baseline\n' > "$repo/infra/docker-compose.yml"
commit_changes
base="$head"
rm "$repo/infra/docker-compose.yml"
commit_changes
check_selection 'deleted infrastructure file runs integration tests' true true

new_repo
mkdir -p "$repo/infra"
mv "$repo/docs/guide.md" "$repo/infra/new.conf"
commit_changes
check_selection 'documentation renamed into infrastructure runs integration tests' true true
base="$head"
mv "$repo/infra/new.conf" "$repo/docs/restored.md"
commit_changes
check_selection 'infrastructure renamed into documentation runs integration tests' true true

new_repo
printf 'changed\n' > "$repo/docs/guide.md"
mkdir -p "$repo/harness"
printf 'changed\n' > "$repo/harness/policy.json"
commit_changes
check_selection 'documentation mixed with harness changes runs server checks' true

new_repo
rm "$repo/src/main/java/App.java"
commit_changes
event=pull_request
check_selection 'deleted source file' true

new_repo
rm "$repo/docs/guide.md"
commit_changes
check_selection 'deleted documentation' false

new_repo
mv "$repo/src/main/java/App.java" "$repo/docs/App.md"
commit_changes
check_selection 'source renamed to documentation' true

new_repo
mv "$repo/docs/guide.md" "$repo/src/main/java/Guide.java"
commit_changes
check_selection 'documentation renamed to source' true

new_repo
mv "$repo/docs/guide.md" "$repo/docs/renamed.md"
commit_changes
check_selection 'documentation renamed within docs' false

new_repo
printf 'changed\n' > "$repo/src/main/java/App.java"
commit_changes
printf 'changed\n' > "$repo/docs/guide.md"
commit_changes
event=push
check_selection 'non-documentation push retains all deployment checks' true true

new_repo
printf 'changed\n' > "$repo/docs/guide.md"
commit_changes
check_selection 'documentation-only push' false
event=workflow_dispatch
check_selection 'manual execution runs all checks' true true
event=unknown
check_selection 'unknown event runs all checks' true true

new_repo
git -C "$repo" switch --quiet -c feature
printf 'PR documentation\n' > "$repo/docs/guide.md"
commit_changes
git -C "$repo" switch --quiet develop
printf 'target branch source\n' > "$repo/src/main/java/App.java"
commit_changes
base="$head"
git -C "$repo" merge --quiet --no-ff --no-edit feature
head=$(git -C "$repo" rev-parse HEAD)
event=pull_request
check_selection 'PR compares base with checked-out merge commit' false

new_repo
head="$base"
check_selection 'empty tree diff skips product checks' false
base='not-a-sha'
check_selection 'invalid base SHA runs all checks' true true
base=''
check_selection 'missing base SHA runs all checks' true true
base=$(printf '%040d' 0)
event=push
check_selection 'new-branch push without a base runs all checks' true true
base=$(printf '%040d' 1)
check_selection 'unavailable push base runs all checks' true true
base=$(git -C "$repo" rev-parse HEAD)
head='not-a-sha'
check_selection 'invalid head SHA runs all checks' true true
head=''
check_selection 'missing head SHA runs all checks' true true
head=$(printf '%040d' 1)
check_selection 'unavailable commit runs all checks' true true

new_repo
printf 'changed\n' > "$repo/docs/guide.md"
commit_changes
original_repo="$repo"
repo="$work/shallow"
git clone --quiet --depth=1 "file://$original_repo" "$repo"
check_selection 'shallow history without base runs all checks' true true

new_repo
printf 'changed\n' > "$repo/docs/guide.md"
commit_changes
mkdir "$work/bin"
export REAL_GIT
REAL_GIT=$(command -v git)
cat > "$work/bin/git" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "$1" == diff ]]; then
  printf 'docs/guide.md\0'
  exit 1
fi
exec "$REAL_GIT" "$@"
MOCK
chmod +x "$work/bin/git"
PATH="$work/bin:$PATH" check_selection 'failed diff discards partial output and runs all checks' true true

printf 'PASS: %s CI selection scenarios\n' "$passed"
