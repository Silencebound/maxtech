#!/usr/bin/env bash
# Regenerates port/out and lists every compile error left (port/README.md, "Error map").
#   port/tools/errormap.sh [--symbols] [--write-ownership]   -> port/build/errormap.log, summary on stdout
set -euo pipefail
port="$(cd "$(dirname "$0")/.." && pwd)"
python3 -I "$port/convert.py"
(cd "$port" && ./gradlew --no-configuration-cache -q errorMapInputs)
work="$port/build/errorMap"
mkdir -p "$work"
common="$port/projects/lib-common/build/errorMap"
neoforge="$port/projects/lib-neoforge/build/errorMap"
cat "$common/sources.txt" "$neoforge/sources.txt" > "$work/sources.txt"
cp="$(cat "$common/classpath.txt" "$neoforge/classpath.txt" | sort -u | paste -sd: -)"
# javac exits 1 while errors remain; that is the point of the run.
cd "$work"  # javac drops a javac.<date>.args file into the working directory on errors
"$(cat "$neoforge/javac.txt")" -proc:none -encoding UTF-8 -Xmaxerrs 100000 -Xmaxwarns 0 -nowarn \
    -d "$work/classes" -cp "$cp" @"$work/sources.txt" > "$port/build/errormap.log" 2>&1 || true
python3 -I "$port/tools/errormap.py" "$port/build/errormap.log" "$@"
