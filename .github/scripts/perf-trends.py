#!/usr/bin/env python3
"""Perf trend tracking for CI: collects the perf_* GameTest results of a run and compares them with main's.

  perf-trends.py extract <out.json> <log>...         read PerfBudget's `perf-result` lines from GameTest logs
  perf-trends.py compare <current.json> [<baseline.json>]   print the PR comment (Markdown) to stdout

PerfBudget logs one line per finished budget: `perf-result <test> <measured_ns> <budget_ns> <outcome>`. A test that
reports more than once in a run (repeated suites) keeps its median. extract writes {"commit", "results": {test:
{"measured_ns", "budget_ns", "outcome"}}}, also when it found nothing (empty results). compare lists tests more than
THRESHOLD slower or faster than the baseline and tests without a baseline; it never fails on a regression, since hosted
runners are too noisy to block on (the budgets only warn in CI too).
"""
import json
import os
import re
import statistics
import sys

THRESHOLD = 0.20
MARKER = "<!-- perf-trends -->"
LINE = re.compile(r"perf-result (\S+) (\d+) (\d+) (\S+)")


def extract(out_path, log_paths):
    seen = {}
    for path in log_paths:
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8", errors="replace") as log:
            for line in log:
                match = LINE.search(line)
                if match:
                    test, measured, budget, outcome = match.groups()
                    entry = seen.setdefault(test, {"measured": [], "budget_ns": int(budget), "outcome": outcome})
                    entry["measured"].append(int(measured))
                    entry["outcome"] = outcome
    results = {
        test: {"measured_ns": int(statistics.median(e["measured"])), "budget_ns": e["budget_ns"],
               "outcome": e["outcome"]}
        for test, e in sorted(seen.items())
    }
    with open(out_path, "w", encoding="utf-8") as out:
        json.dump({"commit": os.environ.get("GITHUB_SHA", ""), "results": results}, out, indent=1, sort_keys=True)
        out.write("\n")
    print(f"{len(results)} perf results written to {out_path}")


def ms(nanos):
    return f"{nanos / 1e6:.4f}"


def load(path):
    if not path or not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def compare(current_path, baseline_path=None):
    current = load(current_path)["results"]
    baseline_doc = load(baseline_path)
    baseline = baseline_doc["results"] if baseline_doc else {}
    slower, faster, missing = [], [], []
    for test, now in sorted(current.items()):
        before = baseline.get(test)
        if not before or before["measured_ns"] <= 0:
            missing.append((test, now))
            continue
        change = (now["measured_ns"] - before["measured_ns"]) / before["measured_ns"]
        row = (test, now, before, change)
        if change > THRESHOLD:
            slower.append(row)
        elif change < -THRESHOLD:
            faster.append(row)
    slower.sort(key=lambda r: -r[3])
    faster.sort(key=lambda r: r[3])

    lines = [MARKER, "### Perf trends", ""]
    if baseline_doc:
        commit = baseline_doc.get("commit", "")[:7] or "unknown commit"
        lines.append(f"{len(current)} `perf_*` results against main's latest full run ({commit}). "
                     f"Changes over {THRESHOLD:.0%} are listed; hosted runners are noisy, so this never fails the build. "
                     "Confirm a slowdown on a quiet machine (perf skill) before acting on it.")
    else:
        lines.append(f"{len(current)} `perf_*` results; no baseline from main found yet (the nightly run stores one).")
    lines.append("")

    def table(title, rows):
        lines.extend([f"**{title}**", "", "| Test | Main (ms) | PR (ms) | Change | Budget (ms) | Outcome |",
                      "|---|---:|---:|---:|---:|---|"])
        for test, now, before, change in rows:
            lines.append(f"| `{test}` | {ms(before['measured_ns'])} | {ms(now['measured_ns'])} | {change:+.0%} "
                         f"| {ms(now['budget_ns'])} | {now['outcome']} |")
        lines.append("")

    if slower:
        table(f"Slower by more than {THRESHOLD:.0%}", slower)
    if faster:
        table(f"Faster by more than {THRESHOLD:.0%}", faster)
    if baseline_doc and not slower and not faster:
        lines.extend([f"No perf test changed by more than {THRESHOLD:.0%}.", ""])
    if missing and baseline_doc:
        lines.extend(["**No baseline on main** (new or renamed tests, or main's run did not reach them)", "",
                      "| Test | PR (ms) | Budget (ms) | Outcome |", "|---|---:|---:|---|"])
        for test, now in missing:
            lines.append(f"| `{test}` | {ms(now['measured_ns'])} | {ms(now['budget_ns'])} | {now['outcome']} |")
        lines.append("")
    print("\n".join(lines))


def main(argv):
    if len(argv) >= 3 and argv[0] == "extract":
        extract(argv[1], argv[2:])
    elif len(argv) in (2, 3) and argv[0] == "compare":
        compare(argv[1], argv[2] if len(argv) == 3 else None)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
