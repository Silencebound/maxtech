#!/usr/bin/env bash
# Runs one mod's GameTests in CI: .github/scripts/gametests.sh <mod> [gradle args...]
# The log goes to the step and to $RUNNER_TEMP/gametests.log (perf trends). When the run fails and every failed test is
# on .github/flaky-tests.txt, each is run once more on its own (-Pgametest takes one pattern); green on all retries
# passes with a "Flaky test retried" warning. Anything else exits with the run's status. The listed tests' outcomes go
# to $RUNNER_TEMP/flaky-results.json (flaky-tests.py). GRADLEW and FLAKY_LIST override the defaults for the tests.
set -euo pipefail
mod="$1"
shift
here="$(cd "$(dirname "$0")" && pwd)"
gradlew="${GRADLEW:-./gradlew}"
list="${FLAKY_LIST:-$here/../flaky-tests.txt}"
tmp="${RUNNER_TEMP:?RUNNER_TEMP is not set}"
results="$tmp/flaky-results.json"
flaky() {
    python3 "$here/flaky-tests.py" "$@"
}

# Runs the mod's GameTests with the given arguments into the log $1; returns Gradle's status (pipefail).
run() {
    local log="$1"
    shift
    "$gradlew" ":$mod:neoforge:runGameTestServer" --no-daemon "$@" 2>&1 | tee "$log" | tee -a "$tmp/gametests.log"
}

pattern="*"
args=()
for arg in "$@"; do
    case "$arg" in
        -Pgametest=*) pattern="${arg#-Pgametest=}" ;;
    esac
    args+=("$arg")
done

status=0
run "$tmp/gametests-$mod.log" ${args[@]+"${args[@]}"} || status=$?
# A broken list or script never changes Gradle's result; it only costs the retry (the script tests check the list).
if ! retry=$(flaky first "$results" "$list" "$tmp/gametests-$mod.log" "maxtech_$mod" "$pattern" "$status"); then
    echo "::warning title=Flaky test list::flaky-tests.py failed; no retry, Gradle's status stands"
    exit "$status"
fi
if [ "$status" -eq 0 ] || [ -z "$retry" ]; then
    exit "$status"
fi

# The retry keeps every argument but the test pattern, such as -PwithAe2=true.
others=()
for arg in ${args[@]+"${args[@]}"}; do
    case "$arg" in
        -Pgametest=*) ;;
        *) others+=("$arg") ;;
    esac
done
failed=0
for test in $retry; do
    echo "Retrying flaky test $test"
    again=0
    run "$tmp/gametests-retry.log" ${others[@]+"${others[@]}"} "-Pgametest=$test" || again=$?
    flaky retried "$results" "$list" "$test" "$tmp/gametests-retry.log" "$again" || failed=1
done
if [ "$failed" -ne 0 ]; then
    exit "$status"
fi
