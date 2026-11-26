#!/usr/bin/env bash
# Fails if a folder (and optionally built jars) holds private details: the generic patterns in privacy-patterns.txt
# (local paths, private network addresses, token shapes, private planning links, the shelved mods), e-mail addresses
# other than the allowed public ones, and the owner's own patterns from a file outside the repository. All matching is
# case-insensitive. Every file is read, binaries included: UTF-16 text, PNG text chunks, gzip files and zip archives
# (jars, nested jars too) are unpacked, file and entry names are checked, and anything unreadable fails the sweep.
#
# Usage: scripts/privacy-sweep.sh <dir> [<jar>...]
# Private patterns: one regular expression per line (Python syntax, which covers ordinary extended regexes; `#`
# comments), read from $MAXTECH_PRIVATE_PATTERNS, default ~/.config/maxtech/private-patterns.txt. A line that is not a
# valid pattern stops the sweep with its line number. Hits of private patterns print only the file and line, never the
# matched text. Exit status: 0 clean, 1 hits, 2 usage or setup error. Needs Python 3.8 or newer.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
private="${MAXTECH_PRIVATE_PATTERNS:-$HOME/.config/maxtech/private-patterns.txt}"

# python3 is a Microsoft Store stub on Windows and python is missing on some systems: take the first that runs.
for candidate in python3 python py; do
    if "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' > /dev/null 2>&1; then
        exec "$candidate" -I "$here/privacy-sweep.py" "$here/privacy-patterns.txt" "$private" "$@"
    fi
done
echo "privacy-sweep: no Python 3.8 or newer found" >&2
exit 2
