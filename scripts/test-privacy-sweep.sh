#!/usr/bin/env bash
# Checks scripts/privacy-sweep.sh against planted fixtures: a clean folder passes; each kind of private detail fails it,
# in any letter case, in a plain file, UTF-16 text, PNG text chunks, a gzip file, a file name, a jar and a jar inside a
# jar; an unreadable archive fails it; a missing private pattern file or an invalid pattern stops it. Not part of CI;
# run it after changing the sweep or its patterns: scripts/test-privacy-sweep.sh
# The planted values are dummies, split up so this file does not match the patterns itself.
set -euo pipefail

sweep="$(cd "$(dirname "$0")" && pwd)/privacy-sweep.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
printf '# dummy private patterns\nJane Q\\. Place''holder\nbuild-box-[0-9]+\n' > "$tmp/private.txt"
export MAXTECH_PRIVATE_PATTERNS="$tmp/private.txt"

python=""
for candidate in python3 python py; do
    if "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' > /dev/null 2>&1; then
        python="$candidate"
        break
    fi
done
[ -n "$python" ] || { echo "no Python 3.8 or newer found" >&2; exit 2; }

# Writes binary fixtures: make.py <kind> <file>.
cat > "$tmp/make.py" <<'EOF'
import gzip, io, struct, sys, zipfile, zlib
kind, path = sys.argv[1], sys.argv[2]
name = ("Jane Q. Place" + "holder").encode()
def chunk(kind, body):
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
def png(*chunks):
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 0, 0, 0, 0)) + b"".join(chunks) \
        + chunk(b"IEND", b"")
def jar(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for entry, data in entries.items():
            archive.writestr(entry, data)
    return buffer.getvalue()
dirty = b"built in C:" + b"\\Users\\someone\\build\n"
data = {
    "png-text": lambda: png(chunk(b"tEXt", b"Author\0" + name)),
    "png-ztxt": lambda: png(chunk(b"zTXt", b"Author\0\0" + zlib.compress(name))),
    "png-itxt": lambda: png(chunk(b"iTXt", b"Author\0\1\0en\0\0" + zlib.compress(name))),
    "png-clean": lambda: png(chunk(b"tEXt", b"Software\0paint")),
    "binary": lambda: b"\0\1\2" + name + b"\xff\xfe\0",
    "utf16-bom": lambda: "by ".encode("utf-16") + ("by " + name.decode()).encode("utf-16-le"),
    "utf16-plain": lambda: ("made by " + name.decode() + " in 2026").encode("utf-16-le"),
    "gzip": lambda: gzip.compress(b"\x0a\0\0\x08\0\x06author\0\x10" + name),
    "jar-clean": lambda: jar({"META-INF/neoforge.mods.toml": b'authors = "Silencebound"\n'}),
    "jar-dirty": lambda: jar({"META-INF/build.txt": dirty}),
    "jar-nested": lambda: jar({"META-INF/jarjar/inner.jar": jar({"META-INF/build.txt": dirty})}),
    "jar-third-party": lambda: jar({"META-INF/jarjar/lib.jar": jar({"NOTICE": b"by someone@mail" + b"box.de\n"})}),
    "jar-own-e-mail": lambda: jar({"META-INF/notes.txt": b"by someone@mail" + b"box.de\n"}),
    "jar-broken":lambda: b"PK\x03\x04" + b"\0" * 40,
}[kind]()
with open(path, "wb") as target:
    target.write(data)
EOF
make() {
    "$python" -I "$tmp/make.py" "$1" "$2"
}

failures=0
case_number=0

# fixture <name>: a clean tree that every case starts from.
fixture() {
    local dir="$tmp/$1"
    mkdir -p "$dir/src"
    printf 'Made by Silencebound (%s, %s).\n' '45662109+Silencebound@users.noreply.github.com' \
        '45662109+Silencebound@Users.NoReply.GitHub.com' > "$dir/README.md"
    printf 'see https://example.com/docs, mail test@example.%s, run ~/.gradle/caches and 26.1.2.109\n' 'org' > "$dir/src/a.txt"
    printf 'class A { val p = this@Outer.project; @org.jspecify.annotations.Nullable String s; }\n' > "$dir/src/A.kt"
    printf 'package dev.silencebound.maxtech.storage; // github.com/Silencebound/maxtech\n' > "$dir/src/B.java"
    make png-clean "$dir/icon.png"
    echo "$dir"
}

check() {
    # check <description> <wanted exit status> <sweep args...>
    local description="$1" want="$2" status=0
    shift 2
    case_number=$((case_number + 1))
    bash "$sweep" "$@" > "$tmp/out.txt" 2>&1 || status=$?
    if [ "$status" -eq "$want" ]; then
        echo "ok   $case_number $description"
    else
        echo "FAIL $case_number $description (wanted exit $want, got $status)"
        sed 's/^/     /' "$tmp/out.txt"
        failures=$((failures + 1))
    fi
}

# plant <description> <file> <text>: a fresh clean tree with one planted line.
plant() {
    local dir
    dir="$(fixture "case$((case_number + 1))")"
    mkdir -p "$(dirname "$dir/$2")"
    printf '%s\n' "$3" >> "$dir/$2"
    check "$1" 1 "$dir"
}

# planted <description> <kind> <file>: a fresh clean tree with one binary fixture.
planted() {
    local dir
    dir="$(fixture "case$((case_number + 1))")"
    mkdir -p "$(dirname "$dir/$3")"
    make "$2" "$dir/$3"
    check "$1" 1 "$dir"
}

check "clean tree" 0 "$(fixture clean)"

plant "windows user path" docs/a.md 'C:''\Users\someone\project'
plant "windows user path, lower case" docs/a.md 'c:''\users\x\project'
plant "windows user path, forward slashes" docs/a.md 'C:/''Users/someone/project'
plant "escaped windows user path" config.json '{"dir": "D:''\\\\Users\\\\someone"}'
plant "mac user path" docs/a.md 'cd /Users''/someone/code'
plant "linux home path" docs/a.md 'cd /home''/someone/code'
plant "netbird host" docs/a.md 'ssh box.Net''Bird.example'
plant "private ipv4 10/8" docs/a.md 'host 10.''1.2.3 here'
plant "private ipv4 192.168/16" docs/a.md 'at 192.''168.0.17.'
plant "private ipv4 172.16/12" docs/a.md '172.''20.1.1'
plant "cgnat ipv4" docs/a.md 'peer 100.''72.3.4'
plant "github token" .env "GH=ghp""_$(printf 'a%.0s' {1..36})"
plant "github fine-grained token" .env "GH=github""_pat_$(printf 'B%.0s' {1..30})"
plant "aws key" .env "KEY=AKIA""ABCDEFGHIJKLMNOP"
plant "private key" id_key "-----BEGIN OPENSSH PRIVATE"" KEY-----"
plant "planning link" docs/a.md 'https://claude''.ai/artifact/abc'
plant "other e-mail" docs/a.md 'mail someone@mail''box.de'
plant "other e-mail, mixed case" docs/a.md 'mail Some.One@Mail''Box.DE'
plant "private pattern" src/a.txt 'by Jane Q. Place''holder'
plant "private pattern, other case" src/a.txt 'BY JANE Q. PLACE''HOLDER'
plant "private host name" .github/x.yml 'runs on build-box''-2'

planted "private pattern in a binary file" binary data.bin
planted "private pattern in a PNG tEXt chunk" png-text icon2.png
planted "private pattern in a PNG zTXt chunk" png-ztxt icon2.png
planted "private pattern in a compressed PNG iTXt chunk" png-itxt icon2.png
planted "private pattern in UTF-16 text with a BOM" utf16-bom notes.txt
planted "private pattern in UTF-16 text without a BOM" utf16-plain notes.txt
planted "private pattern in a gzip file (NBT)" gzip structure.nbt
planted "private detail in a jar inside the folder" jar-dirty libs/a.jar
planted "private detail in a jar inside a jar" jar-nested libs/a.jar
planted "unreadable archive fails closed" jar-broken libs/a.jar

dir="$(fixture filename)"
touch "$dir/src/notes-for-build-box""-7.txt"
check "private pattern in a file name" 1 "$dir"

dir="$(fixture jars)"
make jar-clean "$tmp/clean.jar"
make jar-dirty "$tmp/dirty.jar"
make jar-nested "$tmp/nested.jar"
check "clean jar argument" 0 "$dir" "$tmp/clean.jar"
check "private detail in a jar argument" 1 "$dir" "$tmp/clean.jar" "$tmp/dirty.jar"
check "private detail in a nested jar argument" 1 "$dir" "$tmp/nested.jar"
make jar-third-party "$tmp/third-party.jar"
make jar-own-e-mail "$tmp/own.jar"
check "third-party e-mail in a bundled jar is allowed" 0 "$dir" "$tmp/third-party.jar"
check "e-mail in the jar's own files fails" 1 "$dir" "$tmp/own.jar"

MAXTECH_PRIVATE_PATTERNS="$tmp/missing.txt" check "missing private pattern file stops the sweep" 2 "$(fixture missing)"
grep -q 'no private pattern file' "$tmp/out.txt" || { echo "FAIL     (message)"; failures=$((failures + 1)); }

printf 'fine\n(unclosed''-group\n' > "$tmp/invalid.txt"
MAXTECH_PRIVATE_PATTERNS="$tmp/invalid.txt" check "invalid private pattern stops the sweep" 2 "$(fixture invalid)"
if ! grep -q 'line 2' "$tmp/out.txt" || grep -q 'unclosed' "$tmp/out.txt"; then
    echo "FAIL     (message must name line 2 and not echo the pattern)"
    failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
    echo "$failures failure(s) in $case_number cases" >&2
    exit 1
fi
echo "all $case_number cases passed"
