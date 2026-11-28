"""Rule types for port/convert.py (port/README.md, "Rules").

A rules module (port/rules/<package>.py) defines RULES, a list of the rule objects below. convert.py applies core.py
first, then the other modules in alphabetical order, each rule in list order, to every generated file the rule's
`files` patterns match (fnmatch against the path relative to port/out, e.g. "lib/neoforge/src/main/java/**").

Java text is split into code, comments and string/char literals first, so a rename never touches a string such as a
translation key or a resource path. Rename and ImportMove work on code and comments (Javadoc links follow the rename);
Regex works on code only unless scope="all"; Transform sees the whole text.
"""

from __future__ import annotations

import fnmatch
import re
from typing import Callable, Iterable

JAVA = ("**/*.java",)


def split_java(text: str) -> list[tuple[str, str]]:
    """Splits Java source into (kind, text) parts; kind is "code", "comment" or "literal"."""
    parts: list[tuple[str, str]] = []
    i, n, start = 0, len(text), 0

    def flush(end: int) -> None:
        if end > start:
            parts.append(("code", text[start:end]))

    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] in "/*":
            flush(i)
            end = text.find("\n", i) if text[i + 1] == "/" else text.find("*/", i + 2)
            end = n if end < 0 else (end if text[i + 1] == "/" else end + 2)
            parts.append(("comment", text[i:end]))
            i = start = end
        elif text.startswith('"""', i):
            flush(i)
            j = i + 3
            while j < n and not (text.startswith('"""', j) and text[j - 1] != "\\"):
                j += 1
            end = min(n, j + 3)
            parts.append(("literal", text[i:end]))
            i = start = end
        elif c in "\"'":
            flush(i)
            j = i + 1
            while j < n and text[j] != c and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            end = min(n, j + 1)
            parts.append(("literal", text[i:end]))
            i = start = end
        else:
            i += 1
    flush(n)
    return parts


def map_parts(text: str, kinds: Iterable[str], fn: Callable[[str], str]) -> str:
    kinds = set(kinds)
    return "".join(fn(t) if k in kinds else t for k, t in split_java(text))


class Rule:
    """Base class. `files`: fnmatch patterns of the paths the rule applies to; `note`: why the rule exists."""

    def __init__(self, files: tuple[str, ...] = JAVA, note: str = "") -> None:
        self.files = tuple(files)
        self.note = note
        self.hits = 0

    def matches(self, rel: str) -> bool:
        return any(fnmatch.fnmatch(rel, p) for p in self.files)

    def apply(self, text: str, rel: str) -> str:
        raise NotImplementedError

    def describe(self) -> str:
        return type(self).__name__

    def _sub(self, rx: re.Pattern[str], repl, text: str) -> str:
        out, k = rx.subn(repl, text)
        self.hits += k
        return out


class Rename(Rule):
    """A whole-word rename of a simple name (class, method, field), in code and comments."""

    def __init__(self, old: str, new: str, **kw) -> None:
        super().__init__(**kw)
        self.old, self.new = old, new

    def apply(self, text: str, rel: str) -> str:
        rx = re.compile(r"(?<![\w$-])" + re.escape(self.old) + r"(?![\w$])")
        return map_parts(text, ("code", "comment"), lambda t: self._sub(rx, self.new, t))

    def describe(self) -> str:
        return f"Rename {self.old} -> {self.new}"


class ImportMove(Rule):
    """Moves a package or class: every dotted name starting with `old` (imports, fully qualified uses)."""

    def __init__(self, old: str, new: str, **kw) -> None:
        super().__init__(**kw)
        self.old, self.new = old, new

    def apply(self, text: str, rel: str) -> str:
        rx = re.compile(r"(?<![\w$.])" + re.escape(self.old) + r"(?![\w$])")
        return map_parts(text, ("code", "comment"), lambda t: self._sub(rx, self.new, t))

    def describe(self) -> str:
        return f"ImportMove {self.old} -> {self.new}"


class Regex(Rule):
    """A regex substitution; scope "code" (default) skips comments and literals, "all" sees everything."""

    def __init__(self, pattern: str, repl: str | Callable[[re.Match[str]], str], scope: str = "code",
                 flags: int = 0, **kw) -> None:
        super().__init__(**kw)
        self.pattern, self.repl, self.scope, self.flags = pattern, repl, scope, flags

    def apply(self, text: str, rel: str) -> str:
        rx = re.compile(self.pattern, self.flags)
        if self.scope == "all":
            return self._sub(rx, self.repl, text)
        return map_parts(text, ("code",), lambda t: self._sub(rx, self.repl, t))

    def describe(self) -> str:
        return f"Regex {self.pattern!r}"


class Transform(Rule):
    """Any function (text, rel) -> text, for rewrites a regex can't express. Return the text unchanged to skip."""

    def __init__(self, fn: Callable[[str, str], str], name: str = "", **kw) -> None:
        super().__init__(**kw)
        self.fn, self.name = fn, name

    def apply(self, text: str, rel: str) -> str:
        out = self.fn(text, rel)
        if out != text:
            self.hits += 1
        return out

    def describe(self) -> str:
        return f"Transform {self.name or self.fn.__name__}"
