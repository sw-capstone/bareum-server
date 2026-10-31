#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ai_root="${1:?Usage: test-pdf-api.sh <bareum-ai repository> <corpus documents.json>}"
manifest="${2:?A corpus documents.json path is required}"
ai_root="$(cd "$ai_root" && pwd)"
manifest="$(cd "$(dirname "$manifest")" && pwd)/$(basename "$manifest")"
mkdir -p "$root/build"
run_dir="$(mktemp -d "$root/build/pdf-api.XXXXXX")"
export PYTHONPATH="$ai_root/src${PYTHONPATH:+:$PYTHONPATH}"
uv run --project "$ai_root" --no-sync python "$root/src/test/resources/report/pdf_api_fixture.py" "$manifest" "$run_dir" >"$run_dir/api.log" 2>&1 &
api_pid=$!
trap 'kill "$api_pid" 2>/dev/null || true; wait "$api_pid" 2>/dev/null || true' EXIT
ready=false
for _ in {1..100}; do
    if [[ -s "$run_dir/url" ]]; then
        api_url="$(<"$run_dir/url")"
        if curl --silent --fail "$api_url/health" >/dev/null; then
            ready=true
            break
        fi
    fi
    if ! kill -0 "$api_pid" 2>/dev/null; then
        cat "$run_dir/api.log"
        exit 1
    fi
    sleep 0.2
done
if [[ "$ready" != true ]]; then
    cat "$run_dir/api.log"
    exit 1
fi
cd "$root"
if BAREUM_PDF_API_URL="$api_url/internal/v1/pdf-parses" \
    BAREUM_PDF_API_TOKEN=example-internal-token \
    BAREUM_PDF_API_CASES="$run_dir/cases.json" \
    ./gradlew --no-daemon test --tests com.bareum.server.domain.report.body.PdfApiIntegrationTests --rerun-tasks; then
    result=0
else
    result=$?
fi
test_report="$root/build/test-results/test/TEST-com.bareum.server.domain.report.body.PdfApiIntegrationTests.xml"
if [[ -f "$test_report" ]]; then
    cp "$test_report" "$run_dir/results.xml"
fi
printf 'PDF API verification artifacts: %s\n' "$run_dir"
exit "$result"
