#!/usr/bin/env bash
# Checks gametests.sh and flaky-tests.py with a fake Gradle: bash .github/scripts/test/test-flaky-tests.sh
# The backticks in the expected rows are Markdown, not command substitution.
# shellcheck disable=SC2016
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
scripts="$here/.."
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail() {
    echo "FAIL: $1" >&2
    exit 1
}

cat >"$tmp/list.txt" <<'EOF'
# comment
maxtech_lib:flaky_one #80 sometimes slow
maxtech_lib:flaky_two https://github.com/Silencebound/maxtech/issues/81 sometimes slower
maxtech_storage:flaky_storage #82 only in storage
EOF

# The fake Gradle prints call-<n>.log and exits with call-<n>.status; calls.txt records each call's arguments.
cat >"$tmp/gradlew" <<'EOF'
#!/usr/bin/env bash
n=$(( $(wc -l < "$FAKE/calls.txt") + 1 ))
echo "$*" >> "$FAKE/calls.txt"
cat "$FAKE/call-$n.log"
exit "$(cat "$FAKE/call-$n.status")"
EOF
chmod +x "$tmp/gradlew"

summary() { # <count> <test>...
    echo "[12:00:00] [Server thread/INFO] [minecraft/GameTestServer]: ========= 64 GAME TESTS COMPLETE IN 3.3 s ====="
    echo "[12:00:00] [Server thread/ERROR] [minecraft/GameTestServer]: $1 required tests failed :("
    shift
    for test in "$@"; do
        echo "[12:00:00] [Server thread/ERROR] [minecraft/GameTestServer]:    - $test: no result yet on tick 102"
    done
    echo ""
    echo "[12:00:00] [Server thread/INFO] [minecraft/GameTestServer]: ===================="
}
passed() {
    echo "[12:00:00] [Server thread/INFO] [minecraft/GameTestServer]: All ${1:-1} required tests passed :)"
}

# A case: <name> <mod> then pairs of (log, status) per Gradle call; $extra holds the script's Gradle arguments.
# Leaves status, output and results in $case.
extra=()
run_case() {
    local name="$1" mod="$2"
    shift 2
    case="$tmp/$name"
    mkdir -p "$case/temp"
    : >"$case/calls.txt"
    local n=1
    while [ $# -gt 0 ]; do
        printf '%s\n' "$1" >"$case/call-$n.log"
        echo "$2" >"$case/call-$n.status"
        shift 2
        n=$((n + 1))
    done
    status=0
    FAKE="$case" GRADLEW="$tmp/gradlew" FLAKY_LIST="$tmp/list.txt" RUNNER_TEMP="$case/temp" \
        GITHUB_REF=refs/pull/1/merge GITHUB_SHA=abc123 GITHUB_RUN_ID=42 \
        bash "$scripts/gametests.sh" "$mod" ${extra[@]+"${extra[@]}"} >"$case/out.txt" 2>&1 || status=$?
    calls=$(wc -l <"$case/calls.txt" | tr -d ' ')
}
outcome() { # <case> <test>
    python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["results"].get(sys.argv[2], "absent"))' \
        "$tmp/$1/temp/flaky-results.json" "$2"
}

# Green run: every listed test of the mod passes, other mods' tests are not recorded.
run_case green lib "$(passed 64)" 0
[ "$status" = 0 ] || fail "green: status $status"
[ "$calls" = 1 ] || fail "green: $calls calls"
[ "$(outcome green maxtech_lib:flaky_one)" = pass ] || fail "green: flaky_one not pass"
[ "$(outcome green maxtech_storage:flaky_storage)" = absent ] || fail "green: storage test recorded for lib"
grep -q 'All 64 required tests passed' "$tmp/green/temp/gametests.log" || fail "green: log not teed for perf trends"

# Every failure listed: each is retried alone; green retries pass the step with a warning.
run_case retry lib "$(summary 2 maxtech_lib:flaky_one maxtech_lib:flaky_two)" 1 "$(passed)" 0 "$(passed)" 0
cat "$tmp/retry/out.txt"
[ "$status" = 0 ] || fail "retry: status $status"
[ "$calls" = 3 ] || fail "retry: $calls calls"
grep -qx -- ':lib:neoforge:runGameTestServer --no-daemon -Pgametest=maxtech_lib:flaky_one' "$tmp/retry/calls.txt" ||
    fail "retry: flaky_one not retried alone"
grep -qx -- ':lib:neoforge:runGameTestServer --no-daemon -Pgametest=maxtech_lib:flaky_two' "$tmp/retry/calls.txt" ||
    fail "retry: flaky_two not retried alone"
grep -qxF '::warning title=Flaky test retried::maxtech_lib:flaky_one failed, then passed on retry (#80)' \
    "$tmp/retry/out.txt" || fail "retry: warning annotation"
[ "$(outcome retry maxtech_lib:flaky_one)" = failed_then_passed ] || fail "retry: flaky_one outcome"
[ "$(outcome retry maxtech_lib:flaky_two)" = failed_then_passed ] || fail "retry: flaky_two outcome"

# One unlisted failure: no retry, Gradle's status.
run_case unlisted lib "$(summary 2 maxtech_lib:flaky_one maxtech_lib:real_bug)" 3
[ "$status" = 3 ] || fail "unlisted: status $status"
[ "$calls" = 1 ] || fail "unlisted: retried ($calls calls)"
grep -q 'not on the flaky list, no retry: maxtech_lib:real_bug' "$tmp/unlisted/out.txt" || fail "unlisted: reason"
[ "$(outcome unlisted maxtech_lib:flaky_one)" = failed ] || fail "unlisted: flaky_one outcome"
[ "$(outcome unlisted maxtech_lib:flaky_two)" = pass ] || fail "unlisted: flaky_two outcome"

# A listed test failing twice fails the step, after the other retry passed.
run_case twice lib "$(summary 2 maxtech_lib:flaky_one maxtech_lib:flaky_two)" 1 "$(summary 1 maxtech_lib:flaky_one)" 1 \
    "$(passed)" 0
[ "$status" = 1 ] || fail "twice: status $status"
[ "$calls" = 3 ] || fail "twice: $calls calls"
grep -q 'maxtech_lib:flaky_one failed again on retry (#80)' "$tmp/twice/out.txt" || fail "twice: reason"
[ "$(outcome twice maxtech_lib:flaky_one)" = failed ] || fail "twice: flaky_one outcome"
[ "$(outcome twice maxtech_lib:flaky_two)" = failed_then_passed ] || fail "twice: flaky_two outcome"

# A retry that ran nothing (exit 0 without a pass line) is no pass.
run_case empty lib "$(summary 1 maxtech_lib:flaky_one)" 1 "[12:00:00] no tests" 0
[ "$status" = 1 ] || fail "empty retry: status $status"
[ "$calls" = 2 ] || fail "empty retry: $calls calls"
grep -q 'maxtech_lib:flaky_one failed again on retry (#80)' "$tmp/empty/out.txt" || fail "empty retry: reason"
! grep -q 'Flaky test retried' "$tmp/empty/out.txt" || fail "empty retry: warned of a pass"
[ "$(outcome empty maxtech_lib:flaky_one)" = failed ] || fail "empty retry: outcome"

# A perf budget failure is never retried: PerfBudget already measured twice.
run_case budget lib "[12:00:00] [Server thread/INFO] [PerfBudget/]: perf-result flaky_one 300 200 FAIL
$(summary 1 maxtech_lib:flaky_one)" 1
[ "$status" = 1 ] || fail "budget: status $status"
[ "$calls" = 1 ] || fail "budget: retried ($calls calls)"
grep -q 'perf budget failed (PerfBudget measured twice already), no retry: maxtech_lib:flaky_one' "$tmp/budget/out.txt" ||
    fail "budget: reason"
[ "$(outcome budget maxtech_lib:flaky_one)" = failed ] || fail "budget: outcome"

# A failure without a complete summary (a crash, or fewer names than the count) is no retry and records nothing.
run_case crash lib "Execution failed for task ':lib:neoforge:runGameTestServer'" 1
[ "$status" = 1 ] || fail "crash: status $status"
[ "$calls" = 1 ] || fail "crash: retried"
[ "$(outcome crash maxtech_lib:flaky_one)" = absent ] || fail "crash: outcome recorded"
run_case short lib "$(summary 2 maxtech_lib:flaky_one)" 1
[ "$status" = 1 ] || fail "short summary: status $status"
[ "$calls" = 1 ] || fail "short summary: retried"
grep -q 'no complete failure summary in the log: no retry' "$tmp/short/out.txt" || fail "short summary: reason"
[ "$(outcome short maxtech_lib:flaky_one)" = absent ] || fail "short summary: outcome recorded"

# A name that only starts like a listed one, or lives in another namespace, is not listed.
run_case prefix lib "$(summary 1 maxtech_lib:flaky_one_extra)" 1
[ "$status" = 1 ] || fail "prefix: status $status"
[ "$calls" = 1 ] || fail "prefix: retried"
grep -q 'not on the flaky list, no retry: maxtech_lib:flaky_one_extra' "$tmp/prefix/out.txt" || fail "prefix: reason"
run_case namespace lib "$(summary 1 maxtech_storage:flaky_one)" 1
[ "$status" = 1 ] || fail "namespace: status $status"
[ "$calls" = 1 ] || fail "namespace: retried"
grep -q 'not on the flaky list, no retry: maxtech_storage:flaky_one' "$tmp/namespace/out.txt" || fail "namespace: reason"

# NeoForge logs a rotated test as "- <id> with rotation <r>: ..."; the names then fall short of the count: no retry.
run_case rotation lib "$(summary 1 'maxtech_lib:flaky_one with rotation 90')" 1
[ "$status" = 1 ] || fail "rotation: status $status"
[ "$calls" = 1 ] || fail "rotation: retried"
grep -q 'no complete failure summary in the log: no retry' "$tmp/rotation/out.txt" || fail "rotation: reason"

# More listed failures than FLAKY_MAX_RETRIES: no retries, Gradle's status, and the reason.
export FLAKY_MAX_RETRIES=1
run_case cap lib "$(summary 2 maxtech_lib:flaky_one maxtech_lib:flaky_two)" 1
[ "$status" = 1 ] || fail "cap: status $status"
[ "$calls" = 1 ] || fail "cap: retried ($calls calls)"
grep -q 'more than 1: no retries' "$tmp/cap/out.txt" || fail "cap: reason"
export FLAKY_MAX_RETRIES=2
run_case capok lib "$(summary 2 maxtech_lib:flaky_one maxtech_lib:flaky_two)" 1 "$(passed)" 0 "$(passed)" 0
[ "$status" = 0 ] || fail "cap at the limit: status $status"
[ "$calls" = 3 ] || fail "cap at the limit: $calls calls"
unset FLAKY_MAX_RETRIES

# The retry keeps other arguments and drops the original pattern; a pattern limits what counts as run.
extra=(-PwithAe2=true "-Pgametest=maxtech_storage:flaky_*")
run_case args storage "$(summary 1 maxtech_storage:flaky_storage)" 1 "$(passed)" 0
[ "$status" = 0 ] || fail "args: status $status"
grep -qx -- ':storage:neoforge:runGameTestServer --no-daemon -PwithAe2=true -Pgametest=maxtech_storage:flaky_storage' \
    "$tmp/args/calls.txt" || fail "args: retry arguments"
extra=("-Pgametest=maxtech_lib:flaky_o*")
run_case pattern lib "$(passed)" 0
[ "$(outcome pattern maxtech_lib:flaky_one)" = pass ] || fail "pattern: matched test not recorded"
[ "$(outcome pattern maxtech_lib:flaky_two)" = absent ] || fail "pattern: unmatched test recorded"
extra=()

# Stats JSON shape.
python3 - "$tmp/retry/temp/flaky-results.json" <<'EOF' || fail "stats JSON shape"
import json, sys
doc = json.load(open(sys.argv[1]))
assert set(doc) == {"commit", "run_id", "ref", "kind", "results"}, doc
assert (doc["commit"], doc["run_id"], doc["kind"]) == ("abc123", "42", "pr"), doc
assert doc["results"] == {"maxtech_lib:flaky_one": "failed_then_passed", "maxtech_lib:flaky_two": "failed_then_passed"}
EOF

# Rates over several runs, per kind.
sed 's/"pr"/"main"/' "$tmp/green/temp/flaky-results.json" >"$tmp/main.json"
python3 "$scripts/flaky-tests.py" rates "$tmp/main.json" "$tmp/retry/temp/flaky-results.json" \
    "$tmp/twice/temp/flaky-results.json" "$tmp/unlisted/temp/flaky-results.json" >"$tmp/rates.md"
cat "$tmp/rates.md"
grep -qF 'Flake rates over 4 runs' "$tmp/rates.md" || fail "rates: header"
grep -qF '| `maxtech_lib:flaky_one` | pr | 3 | 0 | 1 | 2 | 100% |' "$tmp/rates.md" || fail "rates: flaky_one pr"
grep -qF '| `maxtech_lib:flaky_two` | pr | 3 | 1 | 2 | 0 | 67% |' "$tmp/rates.md" || fail "rates: flaky_two pr"
grep -qF '| `maxtech_lib:flaky_one` | main | 1 | 1 | 0 | 0 | 0% |' "$tmp/rates.md" || fail "rates: main row"

# Every entry links an issue.
echo 'maxtech_lib:no_issue just flaky' >"$tmp/bad.txt"
if python3 "$scripts/flaky-tests.py" first "$tmp/bad.json" "$tmp/bad.txt" /dev/null maxtech_lib '*' 0 2>/dev/null; then
    fail "entry without an issue accepted"
fi
# A broken list never changes Gradle's result: green stays green, red stays red without a retry.
for code in 0 1; do
    mkdir -p "$tmp/broken-$code/temp"
    passed >"$tmp/broken-$code/call-1.log"
    echo "$code" >"$tmp/broken-$code/call-1.status"
    : >"$tmp/broken-$code/calls.txt"
    got=0
    FAKE="$tmp/broken-$code" GRADLEW="$tmp/gradlew" FLAKY_LIST="$tmp/bad.txt" RUNNER_TEMP="$tmp/broken-$code/temp" \
        bash "$scripts/gametests.sh" lib >/dev/null 2>&1 || got=$?
    [ "$got" = "$code" ] || fail "broken list: Gradle status $code became $got"
done
python3 "$scripts/flaky-tests.py" first "$tmp/real.json" "$here/../../flaky-tests.txt" /dev/null maxtech_lib '*' 0 ||
    fail "the real list does not parse"
echo "flaky-tests tests passed"
