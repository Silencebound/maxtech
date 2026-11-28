#!/usr/bin/env bash
# Checks perf-trends.py against the sample log and baseline in this folder: bash .github/scripts/test/test-perf-trends.sh
# The backticks in the expected rows are Markdown, not command substitution.
# shellcheck disable=SC2016
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../perf-trends.py"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail() {
    echo "FAIL: $1" >&2
    exit 1
}

python3 "$script" extract "$tmp/current.json" "$here/perf-gametest.log" "$tmp/no-such.log"
python3 "$script" compare "$tmp/current.json" "$here/perf-baseline.json" >"$tmp/comment.md"
cat "$tmp/comment.md"
grep -q '^<!-- perf-trends -->' "$tmp/comment.md" || fail "marker missing"
grep -qF '| `perf_transfer_idle` | 0.1000 | 0.1300 | +30% |' "$tmp/comment.md" || fail "slower row"
grep -qF '| `perf_resource_keys_intern` | 0.0600 | 0.0400 | -33% |' "$tmp/comment.md" || fail "faster row"
if grep -q 'perf_energy_pass_500' "$tmp/comment.md"; then fail "a 1% change is listed"; fi
if grep -q 'perf_scheduler_idle_tasks' "$tmp/comment.md"; then fail "a 9% change is listed"; fi
grep -qF '| `perf_new_test` | 0.0050 |' "$tmp/comment.md" || fail "missing baseline row"

python3 "$script" compare "$tmp/current.json" "$tmp/no-baseline.json" | grep -q 'no baseline from main' \
    || fail "no-baseline note"
python3 "$script" extract "$tmp/empty.json" "$here/perf-baseline.json" >/dev/null
count=$(python3 -c 'import json, sys; print(len(json.load(open(sys.argv[1]))["results"]))' "$tmp/empty.json")
[ "$count" = 0 ] || fail "empty extract"
echo "perf-trends tests passed"
