#!/usr/bin/env bash
# Regenerates port/out and lists every compile error left (port/README.md, "Error map"). Works in Git Bash on Windows.
#   port/tools/errormap.sh [--single] [--symbols] [--write-ownership]
#   port/tools/errormap.sh --mod storage [--single] [--symbols]   (Storage against the generated Lib)
# Default: one javac run per work package (Storage: per area), own files only (honest counts); --single: one run over
# everything. Summary on stdout, every message in port/build/errormap.log (errormap-storage.log). Logic: errormap.py.
set -euo pipefail
port="$(cd "$(dirname "$0")/.." && pwd)"
if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then py=python3; else py=python; fi
mod=lib
prev=
for a in "$@"; do
    if [ "$prev" = "--mod" ]; then mod="$a"; fi
    prev="$a"
done
"$py" -I "$port/convert.py" --mod "$mod" >&2
if [ "$mod" = storage ]; then
    (cd "$port" && ./gradlew --no-configuration-cache -q -PwithStorage=true \
        :storage:common:errorMapInputs :storage:neoforge:errorMapInputs >&2)
else
    (cd "$port" && ./gradlew --no-configuration-cache -q errorMapInputs >&2)
fi
exec "$py" -I "$port/tools/errormap.py" --run "$@"
