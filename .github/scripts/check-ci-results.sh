#!/usr/bin/env bash
set -Eeuo pipefail
: "${CI_RESULTS:?Provide the results of the required CI jobs.}"

if ! jq -e '
  . as $jobs
  | .changes.outputs.server as $server
  | .changes.outputs.infra as $infra
  | ($server == "true" or $server == "false")
    and ($infra == "true" or $infra == "false")
    and ($server == "true" or $infra == "false")
    and all($jobs[]; .result == "success" or .result == "skipped")
    and (["changes", "static", "harness", "secret-scan"]
      | all(.[]; $jobs[.].result == "success"))
    and (["java", "image", "infra-unit"]
      | all(.[]; $jobs[.].result == (if $server == "true" then "success" else "skipped" end)))
    and ($jobs["infra-integration"].result == (if $infra == "true" then "success" else "skipped" end))
' <<< "$CI_RESULTS" > /dev/null; then
  printf 'ERROR: Required CI jobs did not finish according to the selected checks.\n' >&2
  jq -r 'to_entries[] | "\(.key): \(.value.result)"' <<< "$CI_RESULTS" >&2 || true
  exit 1
fi

printf 'PASS: All selected CI jobs succeeded; only unselected jobs were skipped.\n'
