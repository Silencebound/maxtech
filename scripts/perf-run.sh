#!/usr/bin/env bash
# Runs every perf_* GameTest of Lib and Storage, appends the results to a per-machine CSV and compares them with the
# earlier runs on that machine. Run it alone on a quiet machine, like the perf skill says.
#
#   scripts/perf-run.sh [-n runs] [history-dir]   # default: 1 run; $MAXTECH_PERF_HISTORY, else ~/notes/maxtech/perf
#
# With -n N the suites run N times and every run is recorded; each test's median over these N runs is compared with
# the median of its N results before them, since single runs of the microsecond tests vary by a factor of two.
#
# CSV columns: run (UTC time), commit, test, measured_ns, budget_ns, outcome.
set -euo pipefail
cd "$(dirname "$0")/.."

runs=1
while getopts "n:" opt; do
    case "$opt" in
        n) runs="$OPTARG" ;;
        *) echo "usage: $0 [-n runs] [history-dir]" >&2; exit 2 ;;
    esac
done
shift $((OPTIND - 1))
if ! [[ "$runs" =~ ^[1-9][0-9]*$ ]]; then
    echo "-n needs a positive number of runs, not '$runs'" >&2
    exit 2
fi

history_dir="${1:-${MAXTECH_PERF_HISTORY:-$HOME/notes/maxtech/perf}}"
mkdir -p "$history_dir"
csv="$history_dir/$(hostname -s).csv"
commit="$(git rev-parse --short HEAD)$(git diff --quiet HEAD || echo '-dirty')"
logs="$(mktemp -d)"
trap 'rm -rf "$logs"' EXIT

[[ -f "$csv" ]] || echo "run,commit,test,measured_ns,budget_ns,outcome" >"$csv"
status=0
for ((i = 1; i <= runs; i++)); do
    run="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    ((runs == 1)) || echo "run $i of $runs ($run)"
    ./gradlew -q :lib:neoforge:runGameTestServer -Pgametest='maxtech_lib:perf_*' >"$logs/lib.log" 2>&1 || status=1
    ./gradlew -q :storage:neoforge:runGameTestServer -Pgametest='maxtech_storage:perf_*' >"$logs/storage.log" 2>&1 \
        || status=1

    new="$logs/new.csv"
    grep -h -o 'perf-result .*' "$logs/lib.log" "$logs/storage.log" \
        | awk -v run="$run" -v commit="$commit" '{ print run "," commit "," $2 "," $3 "," $4 "," $5 }' >"$new" || true
    if [[ ! -s "$new" ]]; then
        echo "no perf results found in run $i; the runs failed before measuring:" >&2
        tail -n 30 "$logs/lib.log" "$logs/storage.log" >&2
        exit 1
    fi
    cat "$new" >>"$csv"
    echo "$run" >>"$logs/runs.txt"
    grep -h -E 'tests? (passed|failed)|required tests' "$logs/lib.log" "$logs/storage.log" || true
done

# Per test: the median of this invocation's runs against the median of its N results before them. A test that some
# runs missed is compared on the runs it has; one that the run before had but none of these runs did is listed as
# missing (a test renamed or removed longer ago is not).
awk -F, -v runs="$runs" -v current="$logs/runs.txt" '
    function median(list, n,    i, j, v, s, k) {
        for (i = 1; i <= n; i++) {
            v = list[i]
            for (j = i - 1; j > 0 && s[j] > v; j--) s[j + 1] = s[j]
            s[j + 1] = v
        }
        k = int((n + 1) / 2)
        return n % 2 ? s[k] : (s[k] + s[k + 1]) / 2
    }
    FILENAME == current { mine[$1] = 1; next }
    FNR == 1 { next }
    {
        t = $3
        if ($1 in mine) {
            now[t, ++nows[t]] = $4
            if ($6 != "PASS") fails[t]++
            budget[t] = $5
            outcome[t] = $6
        } else {
            past[t, ++pasts[t]] = $4
            lastIn[t] = $1
            previousRun = $1
        }
        known[t] = 1
    }
    END {
        for (t in known) {
            if (!(t in nows)) {
                if (lastIn[t] != previousRun) continue
                printf "%-45s missing from this run\n", t
                continue
            }
            delete a
            for (i = 1; i <= nows[t]; i++) a[i] = now[t, i]
            m = median(a, nows[t])
            change = "new"
            if (pasts[t] > 0) {
                first = pasts[t] - runs + 1
                if (first < 1) first = 1
                delete b
                n = 0
                for (i = first; i <= pasts[t]; i++) b[++n] = past[t, i]
                before = median(b, n)
                if (before > 0) change = sprintf("%+.0f%%", (m - before) * 100 / before)
            }
            # The latest outcome, and how many of the runs did not pass when some did not.
            shown = fails[t] > 0 && nows[t] > 1 ? outcome[t] " " fails[t] "/" nows[t] : outcome[t]
            printf "%-45s %12.4f ms  budget %10.4f ms  %5.0f%%  %-13s %s\n", t, m / 1e6, budget[t] / 1e6,
                m * 100 / budget[t], shown, change
        }
    }
' "$logs/runs.txt" "$csv" | sort
echo "results of $runs run(s) appended to $csv"
exit "$status"
