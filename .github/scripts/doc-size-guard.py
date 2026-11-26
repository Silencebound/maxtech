#!/usr/bin/env python3
"""Fails when a change blows up a doc: a docs/**/*.md, SKILL.md, CLAUDE.md or PRD.md file that grows more than
GROWTH times in line count against the merge base, or grows past MAX_LINES. A looped replace once took a milestone doc from
168 to 41,694 lines in one commit.

    doc-size-guard.py <base>    compares HEAD with the merge base of <base> and HEAD (e.g. origin/main, or HEAD^1 on
                                the merge commit a pull_request checkout makes)
"""
import fnmatch
import subprocess
import sys

GROWTH = 10
MAX_LINES = 5000
PATTERNS = ("docs/*.md", "*/SKILL.md", "SKILL.md", ".claude/skills/*-facts.md", "CLAUDE.md", "*/CLAUDE.md", "PRD.md", "*/PRD.md")


def git(*args):
    return subprocess.run(("git",) + args, check=True, capture_output=True, text=True, encoding="utf-8").stdout


def lines(rev, path):
    result = subprocess.run(("git", "show", f"{rev}:{path}"), capture_output=True)
    if result.returncode != 0:
        return None  # not in that revision
    return result.stdout.count(b"\n")


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    base = git("merge-base", sys.argv[1], "HEAD").strip()
    changed = git("diff", "--name-only", "--diff-filter=AMR", base, "HEAD").splitlines()
    failed = False
    for path in changed:
        # fnmatch's * also matches "/", so docs/*.md covers every depth.
        if not any(fnmatch.fnmatchcase(path, pattern) for pattern in PATTERNS):
            continue
        old = lines(base, path)
        new = lines("HEAD", path)
        if old and new > old * GROWTH:
            print(f"::error file={path}::{path} grew from {old} to {new} lines (more than {GROWTH}x)")
            failed = True
        elif new > MAX_LINES and (old is None or new > old):
            print(f"::error file={path}::{path} grew to {new} lines (limit {MAX_LINES})")
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
