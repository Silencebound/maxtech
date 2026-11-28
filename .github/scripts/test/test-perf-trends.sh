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
# repeated results keep the median (200000 ns), not the mean or the last value
grep -qF '| `perf_median` | 0.1000 | 0.2000 | +100% |' "$tmp/comment.md" || fail "median of repeated results"
# busy and failed runs are not compared, and a failed main run is no baseline
if grep -E '^\| `perf_(busy|failed)`' "$tmp/comment.md"; then fail "busy or failed result in a table"; fi
grep -qF 'Not compared (busy or failed): `perf_busy`, `perf_failed`' "$tmp/comment.md" || fail "not compared line"
grep -qF '| `perf_was_failed` | 0.0900 |' "$tmp/comment.md" || fail "failed baseline used"
# '|' in a test name is escaped
grep -qF '| `perf_pipe\|tick` | 0.0200 | 0.0400 | +100% |' "$tmp/comment.md" || fail "pipe in test name"

python3 "$script" compare "$tmp/current.json" "$tmp/no-baseline.json" >"$tmp/nobase.md"
grep -q 'no baseline from main' "$tmp/nobase.md" || fail "no-baseline note"
if grep -q 'Slower by' "$tmp/nobase.md"; then fail "slower table without baseline"; fi
if grep -q 'No baseline on main' "$tmp/nobase.md"; then fail "header-only table without baseline"; fi
python3 "$script" extract "$tmp/empty.json" "$here/perf-baseline.json" >/dev/null
count=$(python3 -c 'import json, sys; print(len(json.load(open(sys.argv[1]))["results"]))' "$tmp/empty.json")
[ "$count" = 0 ] || fail "empty extract"
echo "perf-trends tests passed"
