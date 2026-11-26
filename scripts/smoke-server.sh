#!/usr/bin/env bash
# Boots the release jars on a real (production) NeoForge dedicated server and fails unless every scenario starts,
# generates its world, stops cleanly and logs no error. Dev runs and GameTests use a different launcher and classpath,
# so only this catches a jar-in-jar or packaging problem, or a client-only class that loads on a dedicated server.
#
# Usage: scripts/smoke-server.sh [--jars <dir>] [scenario...]
#   scenarios: lib (Lib alone), storage (Lib + Storage); default: both.
#   --jars <dir>  take maxtech_lib-*.jar and maxtech_storage-*.jar from <dir> instead of building them.
# Environment:
#   SMOKE_DIR         work directory (default build/server-smoke); the server install is reused between runs.
#   SMOKE_CACHE       where the NeoForge installer is cached (default ~/.cache/maxtech-smoke).
#   SMOKE_XMX         server heap (default 1G).
#   SMOKE_TIMEOUT     seconds a scenario may take from launch to "Done" (default 240).
#   SMOKE_STOP_GRACE  seconds the server gets to exit after "stop" before it is killed (default 60).
#   JAVA_HOME         Java 25; falls back to java on PATH.
# Every scenario is bounded by SMOKE_TIMEOUT + SMOKE_STOP_GRACE; on a timeout, a failure or an interrupt the server
# process tree is killed, so no JVM outlives the script.
# Known harmless log lines are listed as extended regexes in scripts/smoke-server-allow.txt.
# Works in Git Bash on Windows and in bash on Linux and macOS.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
allow="$root/scripts/smoke-server-allow.txt"
work="${SMOKE_DIR:-$root/build/server-smoke}"
cache="${SMOKE_CACHE:-$HOME/.cache/maxtech-smoke}"
xmx="${SMOKE_XMX:-1G}"
limit="${SMOKE_TIMEOUT:-240}"
grace="${SMOKE_STOP_GRACE:-60}"
java="java"
if [ -n "${JAVA_HOME:-}" ]; then
    java="$JAVA_HOME/bin/java"
fi

jars=""
scenarios=()
while [ $# -gt 0 ]; do
    case "$1" in
        --jars) jars="$2"; shift 2 ;;
        lib | storage) scenarios+=("$1"); shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
if [ ${#scenarios[@]} -eq 0 ]; then
    scenarios=(lib storage)
fi

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) os=windows argsfile=win_args.txt ;;
    Darwin) os=macos argsfile=unix_args.txt ;;
    *) os=linux argsfile=unix_args.txt ;;
esac

# The server runs as the last element of a background pipeline; server_pid is that element's pid.
server_pid=""
stop_flag=""
kill_server() {
    if [ -n "$stop_flag" ]; then
        touch "$stop_flag" 2>/dev/null || true # lets the stdin feeder end
    fi
    if [ -z "$server_pid" ] || ! kill -0 "$server_pid" 2>/dev/null; then
        server_pid=""
        return 0
    fi
    if [ "$os" = windows ]; then
        # Git Bash has no process groups for native programs: kill the Windows process tree by its Windows pid.
        local winpid
        winpid=$(cat "/proc/$server_pid/winpid" 2>/dev/null || echo "$server_pid")
        taskkill //F //T //PID "$winpid" > /dev/null 2>&1 || true
    else
        pkill -KILL -P "$server_pid" 2>/dev/null || true
        kill -KILL "$server_pid" 2>/dev/null || true
    fi
    wait "$server_pid" 2>/dev/null || true
    server_pid=""
}
trap kill_server EXIT
trap 'kill_server; exit 130' INT
trap 'kill_server; exit 143' TERM

neoforge=$(sed -n 's/^neoforge = "\(.*\)"/\1/p' "$root/gradle/libs.versions.toml")
if [ -z "$neoforge" ]; then
    echo "no neoforge version in gradle/libs.versions.toml" >&2
    exit 1
fi

# Jars: build them unless a directory was given.
if [ -z "$jars" ]; then
    (cd "$root" && ./gradlew :lib:neoforge:assemble :storage:neoforge:assemble --no-daemon -q)
    jars="$work/jars"
    rm -rf "$jars"
    mkdir -p "$jars"
    cp "$root"/lib/neoforge/build/libs/maxtech_lib-*.jar "$root"/storage/neoforge/build/libs/maxtech_storage-*.jar "$jars/"
    rm -f "$jars"/*-sources.jar
fi
find_jar() {
    local found
    found=$(find "$jars" -maxdepth 1 -name "$1-*.jar" ! -name '*-sources.jar' ! -name '*-api-*.jar' -print -quit)
    if [ -z "$found" ]; then
        echo "no $1 jar in $jars" >&2
        exit 1
    fi
    echo "$found"
}
lib_jar=$(find_jar maxtech_lib)
storage_jar=$(find_jar maxtech_storage)

sha256() {
    if command -v sha256sum > /dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

# Server: the installer is cached (checked against Maven's .sha256); the install takes seconds and stays in $work.
server="$work/neoforge-$neoforge"
installer="$cache/neoforge-$neoforge-installer.jar"
if [ ! -f "$server/libraries/net/neoforged/neoforge/$neoforge/$argsfile" ]; then
    mkdir -p "$cache"
    if [ ! -f "$installer" ]; then
        url="https://maven.neoforged.net/releases/net/neoforged/neoforge/$neoforge/neoforge-$neoforge-installer.jar"
        part=$(mktemp "$cache/installer.XXXXXX")
        curl -fsSL --connect-timeout 20 --max-time 300 -o "$part" "$url"
        expected=$(curl -fsSL --connect-timeout 20 --max-time 60 "$url.sha256" | tr -d '[:space:]')
        actual=$(sha256 "$part")
        if [ "$expected" != "$actual" ]; then
            rm -f "$part"
            echo "::error::NeoForge installer checksum mismatch (expected $expected, got $actual)"
            exit 1
        fi
        mv -f "$part" "$installer"
    fi
    rm -rf "$server"
    mkdir -p "$server"
    (cd "$server" && "$java" -jar "$installer" --installServer . > install.log 2>&1) || {
        tail -n 40 "$server/install.log"
        echo "::error::NeoForge $neoforge server install failed"
        exit 1
    }
fi

# Allow-list entries without a tag apply everywhere; "[windows] ", "[linux] " or "[macos] " limit one to that OS.
patterns="$work/allow-$os.txt"
grep -vE '^\s*(#|$)' "$allow" | sed -n -e "s/^\[$os\] //p" -e '/^\[[a-z]*\] /!p' > "$patterns"

run_scenario() {
    local name="$1"
    shift
    echo "== $name: $*"
    (cd "$server" && rm -rf mods world logs crash-reports config defaultconfigs console.log stop-now)
    mkdir -p "$server/mods"
    local jar
    for jar in "$@"; do
        cp "$jar" "$server/mods/"
    done
    echo "eula=true" > "$server/eula.txt"
    # Port 0 picks a free port: CI runners and dev servers share the machine. Small distances keep the start short;
    # the spawn area is still generated with normal terrain.
    cat > "$server/server.properties" <<'EOF'
server-port=0
online-mode=false
level-name=world
level-seed=maxtech-smoke
view-distance=4
simulation-distance=4
enable-query=false
enable-rcon=false
EOF

    local log="$server/console.log"
    stop_flag="$server/stop-now"
    # The feeder types "stop" once stop-now exists (and gives up after the hard limit, so it never lingers).
    (
        for _ in $(seq 1 $((limit + grace + 10))); do
            if [ -f "$stop_flag" ]; then
                break
            fi
            sleep 1
        done
        echo stop
    ) | (cd "$server" && exec "$java" "-Xmx$xmx" @user_jvm_args.txt \
        "@libraries/net/neoforged/neoforge/$neoforge/$argsfile" nogui > console.log 2>&1) &
    server_pid=$!

    local failed=0
    local waited=0
    while [ "$waited" -lt "$limit" ] && kill -0 "$server_pid" 2>/dev/null && ! grep -q 'Done (' "$log" 2>/dev/null; do
        sleep 1
        waited=$((waited + 1))
    done
    touch "$stop_flag"
    waited=0
    while [ "$waited" -lt "$grace" ] && kill -0 "$server_pid" 2>/dev/null; do
        sleep 1
        waited=$((waited + 1))
    done
    local status=0
    if kill -0 "$server_pid" 2>/dev/null; then
        echo "::error::$name: the server did not exit within ${grace}s of stop; killed"
        kill_server
        status=killed
        failed=1
    else
        wait "$server_pid" || status=$?
        server_pid=""
    fi

    local started=0
    if grep -q 'Done (' "$log"; then
        started=1
    else
        echo "::error::$name: the server never reached Done"
        failed=1
    fi
    if [ "$status" != 0 ]; then
        echo "::error::$name: the server exited with $status"
        failed=1
    fi
    # The world is saved once right after "Done" anyway; only a save after "Stopping server" proves a clean stop.
    if [ "$started" = 1 ] &&
        ! awk '/Stopping server/ { s = 1 } s && /All dimensions are saved/ { f = 1 } END { exit !f }' "$log"; then
        echo "::error::$name: the server did not stop cleanly"
        failed=1
    fi
    if [ -n "$(ls -A "$server/crash-reports" 2>/dev/null)" ]; then
        echo "::error::$name: crash report written"
        failed=1
    fi
    local modid
    for jar in "$@"; do
        modid=$(basename "$jar" | sed 's/-[0-9].*//')
        if ! grep -q "^ - $modid (jar(" "$log"; then
            echo "::error::$name: FML did not load $modid"
            failed=1
        fi
    done
    if [ $# -gt 1 ] && ! grep -q '^ - guideme (jar(mods/maxtech_storage-' "$log"; then
        echo "::error::$name: GuideME was not loaded from Storage's jar"
        failed=1
    fi
    # Warnings, errors, exceptions and client classes on the server, minus the allow-list. Stack frames and
    # "... n more" are skipped: the allow-list matches a trace by its head and "Caused by" lines.
    local bad
    bad=$(grep -nE '/(WARN|ERROR|FATAL)\]| (ERROR|FATAL) |Exception|[a-z]Error\b|NoClassDefFound|ClassNotFound|invalid dist' \
        "$log" | grep -vE '^[0-9]+:\s+(at |\.\.\. [0-9]+ more)' | grep -vEf "$patterns" || true)
    if [ -n "$bad" ]; then
        echo "::error::$name: unexpected log lines"
        printf '%s\n' "$bad" | sed -n '1,60p'
        failed=1
    fi
    if [ "$failed" = 1 ]; then
        echo "-- last lines of $log"
        tail -n 60 "$log"
        return 1
    fi
    echo "$name: ok ($(grep -o 'Done ([^)]*)' "$log" | sed -n 1p))"
}

result=0
for scenario in "${scenarios[@]}"; do
    case "$scenario" in
        lib) run_scenario "lib alone" "$lib_jar" || result=1 ;;
        storage) run_scenario "lib + storage" "$lib_jar" "$storage_jar" || result=1 ;;
    esac
    cp "$server/console.log" "$work/$scenario.log" 2>/dev/null || true
done
exit $result
