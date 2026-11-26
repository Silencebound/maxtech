#!/usr/bin/env bash
# Stops Gradle daemons that are IDLE right now and were started more than N hours ago (default 2), nothing else.
# Safe next to running work: BUSY daemons, their workers, CI jobs, dev games and other worktrees' builds are
# never touched. Never use `./gradlew --stop` instead: it stops every daemon, busy ones included.
#
# Usage: scripts/gradle-idle-cleanup.sh [min-age-hours]   (dry run: DRY_RUN=1 scripts/gradle-idle-cleanup.sh)
set -euo pipefail

min_hours="${1:-2}"
root="$(git rev-parse --show-toplevel)"
min_seconds=$((min_hours * 3600))

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) windows=true ;;
    *) windows=false ;;
esac

elapsed_seconds() {
    if $windows; then
        # Git Bash's ps has no -o; gradle --status prints Windows pids, so ask PowerShell for the process age.
        local secs
        secs=$(powershell.exe -NoProfile -Command \
            "try { [int]((Get-Date) - (Get-Process -Id $1 -ErrorAction Stop).StartTime).TotalSeconds } catch { exit 1 }" | tr -d '\r') || return 1
        [ -n "$secs" ] || return 1
        echo "$secs"
        return 0
    fi
    # ps etime: [[dd-]hh:]mm:ss
    local t d=0 h=0 m=0 s=0
    t=$(ps -o etime= -p "$1" | tr -d ' ') || return 1
    [ -n "$t" ] || return 1
    if [[ "$t" == *-* ]]; then d=${t%%-*}; t=${t#*-}; fi
    IFS=: read -r a b c <<<"$t"
    if [ -n "${c:-}" ]; then h=$a; m=$b; s=$c; else m=$a; s=$b; fi
    echo $((10#$d * 86400 + 10#$h * 3600 + 10#$m * 60 + 10#$s))
}

stopped=0
while read -r pid status _; do
    [ "$status" = IDLE ] || continue
    age=$(elapsed_seconds "$pid") || continue
    if [ "$age" -lt "$min_seconds" ]; then
        echo "keep  $pid (idle, started $((age / 60)) min ago)"
        continue
    fi
    # Re-check just before stopping, so a daemon a build picked up in the meantime is left alone.
    if ! "$root/gradlew" -p "$root" --status --quiet 2>/dev/null | awk -v p="$pid" '$1 == p && $2 == "IDLE" {f = 1} END {exit !f}'; then
        echo "keep  $pid (picked up by a build)"
        continue
    fi
    if [ "${DRY_RUN:-}" = 1 ]; then
        echo "would stop $pid (idle, started $((age / 3600)) h ago)"
    else
        # SIGTERM lets the daemon shut down cleanly; its idle workers exit with it. A console-less JVM on Windows has
        # no SIGTERM, so the daemon is stopped by pid there (it is idle, so there is nothing to lose).
        if $windows; then stop_cmd=(taskkill //F //PID "$pid"); else stop_cmd=(kill -TERM "$pid"); fi
        "${stop_cmd[@]}" > /dev/null && echo "stop  $pid (idle, started $((age / 3600)) h ago)" && stopped=$((stopped + 1))
    fi
done < <("$root/gradlew" -p "$root" --status --quiet 2>/dev/null | awk 'NR > 1 && $1 ~ /^[0-9]+$/')

echo "stopped $stopped idle daemon(s); busy daemons were left alone"
