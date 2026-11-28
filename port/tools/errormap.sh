#!/usr/bin/env bash
# Regenerates port/out and lists every compile error left (port/README.md, "Error map"). Works in Git Bash on Windows.
#   port/tools/errormap.sh [--single] [--symbols] [--write-ownership]
# Default: one javac run per work package, own files only (honest counts); --single: one run over everything.
# Summary on stdout, every message in port/build/errormap.log. All logic is in errormap.py.
set -euo pipefail
port="$(cd "$(dirname "$0")/.." && pwd)"
if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then py=python3; else py=python; fi
"$py" -I "$port/convert.py" >&2
(cd "$port" && ./gradlew --no-configuration-cache -q errorMapInputs >&2)
exec "$py" -I "$port/tools/errormap.py" --run "$@"
