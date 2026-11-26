#!/usr/bin/env bash
# Boots a mod's dev dedicated server and fails unless it reaches "Done". A dedicated server is the one place
# client-only classes crash on load, and GameTests run on a different server type that does not catch all of it.
# Usage: server-smoke.sh <mod>   (storage; lib has no server of its own)
set -euo pipefail

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) windows=true ;;
    *) windows=false ;;
esac
if ! $windows; then
    set -m # background jobs get their own process group, so the Gradle JVM and the server JVM stop together
fi

mod="$1"
dir="runs/${mod}-neoforge/server"
log="$dir/smoke.log"
mkdir -p "$dir"
rm -rf "$dir/world"
echo "eula=true" > "$dir/eula.txt"
# Three runners and local dev servers share the machine, so a fixed port would collide; 0 picks a free one.
if [ -f "$dir/server.properties" ]; then
    sed -i.bak '/^server-port=/d' "$dir/server.properties"
fi
echo "server-port=0" >> "$dir/server.properties"

./gradlew ":${mod}:neoforge:runServer" --no-daemon > "$log" 2>&1 &
pid=$!

stop_server() {
    if $windows; then
        # No process groups in Git Bash: kill the whole Windows process tree (Gradle JVM and server JVM) by its Windows pid.
        local winpid
        winpid=$(cat "/proc/$pid/winpid" 2>/dev/null || echo "$pid")
        taskkill //F //T //PID "$winpid" > /dev/null 2>&1 || true
    else
        kill -TERM -"$pid" 2>/dev/null || true
    fi
}
trap stop_server EXIT

for _ in $(seq 1 300); do
    if grep -q 'Done (' "$log"; then
        echo "$mod server started"
        exit 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
        break
    fi
    sleep 1
done

tail -n 80 "$log"
echo "::error::$mod dedicated server did not start"
exit 1
