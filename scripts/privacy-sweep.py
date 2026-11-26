"""The privacy sweep behind scripts/privacy-sweep.sh (which picks the Python interpreter); see that script for usage.

Searches every file under a folder and every jar given, case-insensitively, for the generic patterns in
privacy-patterns.txt, for e-mail addresses that are not allowed, and for the owner's private patterns. Besides plain
text it reads UTF-16 text, the text chunks of PNG images (tEXt, zTXt, iTXt), gzip files (NBT structures) and zip
archives (jars, also nested ones), and it checks file and entry names. Anything it cannot read fails the sweep instead
of being skipped. Exit status: 0 clean, 1 hits, 2 usage or setup error.
"""

import gzip
import io
import os
import re
import struct
import sys
import zipfile
import zlib

MAX_DEPTH = 4  # nested archives
MAX_UNPACKED = 256 * 1024 * 1024  # per decompressed member, against zip bombs

EMAIL = re.compile(r"(?<![\w.%+-])([a-z0-9._%+-]*[a-z0-9])@([a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,})(?![\w-])", re.I)
ALLOWED_EMAIL = re.compile(
    r"([0-9]+\+[a-z0-9-]+@users\.noreply\.github\.com|noreply@github\.com|git@github\.com|[^@]+@example\.(com|org|net))",
    re.I)
# Kotlin labels (this@Outer.x, return@forEach) look like addresses.
CODE_LABELS = {"this", "super", "return", "break", "continue"}


class Sweep:
    def __init__(self, generic, private):
        self.generic = generic  # [(label, regex)]
        self.private = private  # [regex]
        self.hits = 0

    def hit(self, label, where, detail=""):
        self.hits += 1
        print(f"  [{label}] {where}{': ' + detail if detail else ''}")

    def check_name(self, name):
        for label, regex in self.generic:
            if regex.search(name):
                self.hit(label + " (name)", name)
        for regex in self.private:
            if regex.search(name):
                self.hit("private (name)", "a file or entry name (not shown)")
                break

    def check_text(self, where, text):
        for label, regex in self.generic:
            for match in regex.finditer(text):
                self.hit(label, f"{where}:{line_of(text, match.start())}", excerpt(text, match))
        # Bundled third-party jars (jar-in-jar, e.g. GuideME) carry their authors' public addresses in licence notices;
        # every other pattern still applies to them.
        third_party = "!/META-INF/jarjar/" in where
        for match in () if third_party else EMAIL.finditer(text):
            if match.group(1).lower() in CODE_LABELS or ALLOWED_EMAIL.fullmatch(match.group(0)):
                continue
            self.hit("e-mail", f"{where}:{line_of(text, match.start())}", match.group(0))
        for regex in self.private:
            for match in regex.finditer(text):
                # Never print what a private pattern matched.
                self.hit("private", f"{where}:{line_of(text, match.start())}")

    def check_bytes(self, where, data, depth):
        """Checks one file's content, unpacking what it can and failing on what it cannot read."""
        if data.startswith(b"PK\x03\x04") or data.startswith(b"PK\x05\x06"):
            self.check_zip(where, data, depth)
            return
        if data.startswith(b"\x1f\x8b"):
            try:
                self.check_bytes(where + "!gunzip", read_limited(gzip.GzipFile(fileobj=io.BytesIO(data))), depth + 1)
            except (OSError, EOFError, zlib.error, ValueError) as error:
                self.hit("unreadable", where, f"gzip: {error}")
            return
        if data.startswith(b"\x89PNG\r\n\x1a\n"):
            self.check_png(where, data)
        for text in decodings(data):
            self.check_text(where, text)

    def check_zip(self, where, data, depth):
        if depth > MAX_DEPTH:
            self.hit("unreadable", where, f"archives nested deeper than {MAX_DEPTH}")
            return
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for info in archive.infolist():
                    name = f"{where}!/{info.filename}"
                    self.check_name(info.filename)
                    if info.is_dir():
                        continue
                    if info.file_size > MAX_UNPACKED:
                        self.hit("unreadable", name, "entry too large to check")
                        continue
                    self.check_bytes(name, archive.read(info), depth + 1)
        except (zipfile.BadZipFile, zlib.error, NotImplementedError, RuntimeError, ValueError) as error:
            self.hit("unreadable", where, f"zip: {error}")

    def check_png(self, where, data):
        position = 8
        while position + 8 <= len(data):
            length, kind = struct.unpack(">I4s", data[position:position + 8])
            body = data[position + 8:position + 8 + length]
            position += 12 + length
            try:
                if kind == b"tEXt":
                    self.check_text(f"{where}[tEXt]", body.decode("latin-1"))
                elif kind == b"zTXt":
                    keyword, _, rest = body.partition(b"\0")
                    text = zlib.decompress(rest[1:])
                    self.check_text(f"{where}[zTXt]", (keyword + b" " + text).decode("latin-1"))
                elif kind == b"iTXt":
                    keyword, _, rest = body.partition(b"\0")
                    compressed, rest = rest[0], rest[2:]
                    language, _, rest = rest.partition(b"\0")
                    translated, _, text = rest.partition(b"\0")
                    if compressed:
                        text = zlib.decompress(text)
                    self.check_text(f"{where}[iTXt]", b" ".join((keyword, language, translated, text))
                                    .decode("utf-8", "replace"))
            except (zlib.error, IndexError) as error:
                self.hit("unreadable", where, f"PNG {kind.decode('latin-1')} chunk: {error}")
            if kind == b"IEND":
                break


def read_limited(stream):
    data = stream.read(MAX_UNPACKED + 1)
    if len(data) > MAX_UNPACKED:
        raise ValueError("too large to check")
    return data


def decodings(data):
    """The text views of a file: UTF-8 (lossy, so binaries are searched too) and UTF-16 when it looks like UTF-16."""
    views = [data.decode("utf-8", "replace")]
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        views.append(data.decode("utf-16", "replace"))
    elif len(data) >= 4:
        # No BOM: many zero bytes on one parity mean UTF-16 (or a binary, where the extra view costs nothing).
        sample = data[:4096]
        even, odd = sample[0::2].count(0), sample[1::2].count(0)
        if odd > len(sample) // 8 and odd > 4 * even:
            views.append(data.decode("utf-16-le", "replace"))
        elif even > len(sample) // 8 and even > 4 * odd:
            views.append(data.decode("utf-16-be", "replace"))
    return views


def line_of(text, index):
    return text.count("\n", 0, index) + 1


def excerpt(text, match):
    start = text.rfind("\n", 0, match.start()) + 1
    end = text.find("\n", match.end())
    line = text[start:end if end >= 0 else len(text)]
    return "".join(c if c.isprintable() else "?" for c in line.strip())[:160]


def compile_lines(path, private):
    """Compiles one pattern per line, failing closed on any line that is not a valid pattern."""
    patterns = []
    with open(path, encoding="utf-8") as source:
        for number, raw in enumerate(source, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if private:
                label, regex = "private", line
            else:
                parts = line.split(None, 1)
                if len(parts) != 2:
                    fail(f"{path} line {number}: expected a label and a pattern")
                label, regex = parts
            try:
                compiled = re.compile(regex, re.I | re.M)
            except re.error as error:
                if private:
                    fail(f"the private pattern file, line {number}, is not a valid regular expression ({error.msg})")
                fail(f"{path} line {number}: not a valid regular expression ({error.msg})")
            if compiled.search(""):
                fail(f"{'the private pattern file' if private else path}, line {number}, matches empty text")
            patterns.append((label, compiled))
    return patterns


def fail(message):
    print(f"privacy-sweep: {message}", file=sys.stderr)
    sys.exit(2)


def main(argv):
    if len(argv) < 3:
        fail("usage: scripts/privacy-sweep.sh <dir> [<jar>...]")
    generic_path, private_path, folder, jars = argv[0], argv[1], argv[2], argv[3:]
    if not os.path.isdir(folder):
        fail(f"not a folder: {folder}")
    if not os.path.isfile(private_path):
        fail(f"no private pattern file at {private_path}. Create it with one regular expression per line (your real "
             "name and its spellings, private e-mail addresses, host and machine names, local user names), or point "
             "MAXTECH_PRIVATE_PATTERNS at another file. It must stay outside the repository.")
    generic = compile_lines(generic_path, False)
    private = [regex for _, regex in compile_lines(private_path, True)]
    if not private:
        fail("the private pattern file has no patterns")
    sweep = Sweep(generic, private)

    for top, dirs, files in os.walk(folder):
        dirs[:] = sorted(d for d in dirs if d != ".git")
        for name in sorted(dirs) + sorted(files):
            if name == ".git":
                continue
            path = os.path.join(top, name)
            relative = os.path.relpath(path, folder).replace(os.sep, "/")
            sweep.check_name(relative)
            if name in files:
                try:
                    with open(path, "rb") as source:
                        sweep.check_bytes(relative, source.read(), 0)
                except OSError as error:
                    sweep.hit("unreadable", relative, str(error))
    for jar in jars:
        if not os.path.isfile(jar):
            fail(f"not a file: {jar}")
        with open(jar, "rb") as source:
            sweep.check_zip(os.path.basename(jar), source.read(), 0)

    shown = " ".join([folder] + jars)
    if sweep.hits:
        print(f"privacy-sweep: {sweep.hits} hit(s) in {shown}", file=sys.stderr)
        return 1
    print(f"privacy-sweep: clean ({shown})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
