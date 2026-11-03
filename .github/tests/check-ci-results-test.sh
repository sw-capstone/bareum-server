#!/usr/bin/env bash
set -Eeuo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
validator="$source_dir/scripts/check-ci-results.sh"
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
passed=0
baseline='{
  "changes":{"result":"success","outputs":{"server":"true","infra":"true"}},
  "static":{"result":"success"},
  "harness":{"result":"success"},
  "secret-scan":{"result":"success"},
  "java":{"result":"success"},
  "image":{"result":"success"},
  "infra-unit":{"result":"success"},
  "infra-integration":{"result":"success"}
}'
documents=$(jq '
  .changes.outputs = {server:"false",infra:"false"}
  | .java.result = "skipped"
  | .image.result = "skipped"
  | .["infra-unit"].result = "skipped"
  | .["infra-integration"].result = "skipped"
' <<< "$baseline")

check_result() {
  local label="$1" expected="$2" actual=0
  CI_RESULTS="$results" bash "$validator" > "$work/output" 2>&1 || actual=$?
  if [[ "$expected" == pass && "$actual" != 0 ]] || [[ "$expected" == fail && "$actual" == 0 ]]; then
    printf 'FAIL: %s (exit %s)\n' "$label" "$actual" >&2
    cat "$work/output" >&2
    exit 1
  fi
  passed=$((passed + 1))
  printf 'PASS: %s\n' "$label"
}

results="$baseline"
check_result 'all selected jobs succeed' pass
results="$documents"
check_result 'documentation-only changes allow only planned skips' pass
results=$(jq '.changes.outputs.infra = "false" | .["infra-integration"].result = "skipped"' <<< "$baseline")
check_result 'server-only changes allow skipped infrastructure integration tests' pass

for job in changes static harness secret-scan java image infra-unit infra-integration; do
  for status in failure cancelled skipped; do
    results=$(jq --arg job "$job" --arg status "$status" '.[$job].result = $status' <<< "$baseline")
    check_result "$job $status blocks the required check" fail
  done
  results=$(jq --arg job "$job" 'del(.[$job])' <<< "$baseline")
  check_result "missing $job blocks the required check" fail
done

for job in changes static harness secret-scan; do
  for status in failure cancelled skipped; do
    results=$(jq --arg job "$job" --arg status "$status" '.[$job].result = $status' <<< "$documents")
    check_result "$job $status still blocks documentation-only changes" fail
  done
done

for job in java image infra-unit infra-integration; do
  for status in success failure cancelled; do
    results=$(jq --arg job "$job" --arg status "$status" '.[$job].result = $status' <<< "$documents")
    check_result "unplanned $job $status rejects documentation-only results" fail
  done
done

for flag in server infra; do
  for value in '""' '"yes"' true false null; do
    results=$(jq --arg flag "$flag" --argjson value "$value" '.changes.outputs[$flag] = $value' <<< "$baseline")
    check_result "invalid $flag output $value blocks the required check" fail
  done
  results=$(jq --arg flag "$flag" 'del(.changes.outputs[$flag])' <<< "$baseline")
  check_result "missing $flag output blocks the required check" fail
done

results=$(jq '.changes.outputs.server = "false"' <<< "$baseline")
check_result 'infrastructure cannot be selected without server checks' fail
for invalid in '' invalid null '[]'; do
  results="$invalid"
  check_result 'invalid results fail closed' fail
done

printf 'PASS: %s CI result scenarios\n' "$passed"
