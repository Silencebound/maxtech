#!/usr/bin/env bash
# Checks changed-areas.sh on a throwaway repository: bash .github/scripts/test/test-changed-areas.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../changed-areas.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail() {
    echo "FAIL: $1" >&2
    exit 1
}

cd "$tmp"
git init -q
git config user.email t@example.com
git config user.name t
echo base >README.md
git add -A
git commit -q -m base

# flags <file>...: commits the files, then prints the flags of that commit.
flags() {
    local file
    for file in "$@"; do
        mkdir -p "$(dirname "$file")"
        echo "$RANDOM" >"$file"
    done
    git add -A
    git commit -q -m change
    GITHUB_EVENT_NAME=pull_request bash "$script" HEAD^1 HEAD
}
expect() { # <case> <flags> <key=value>...
    local name="$1" out="$2"
    shift 2
    for pair in "$@"; do
        grep -qx -- "$pair" <<<"$out" || fail "$name: expected $pair in: $(tr '\n' ' ' <<<"$out")"
    done
}

out=$(flags port/convert.py port/rules/a.json)
expect "port only" "$out" port=true docs_only=true all=false lib=false storage=false farms=false
out=$(flags port/convert.py docs/x.md)
expect "port and docs" "$out" port=true docs_only=true all=false
out=$(flags port/convert.py storage/common/src/main/Foo.java)
expect "port and storage" "$out" port=true docs_only=false storage=true lib=false
out=$(flags port/convert.py gradlew)
expect "port and unknown path" "$out" all=true docs_only=false lib=true
out=$(flags docs/y.md)
expect "docs only" "$out" port=false docs_only=true
out=$(flags .github/workflows/x.yml port/a)
expect "workflow and port" "$out" all=true docs_only=false
echo "changed-areas tests passed"
