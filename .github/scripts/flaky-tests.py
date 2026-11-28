#!/usr/bin/env python3
"""Known flaky GameTests in CI: which failures earn a retry, and how often each listed test flakes.

  flaky-tests.py first <results.json> <list> <log> <mod-id> <pattern> <status>
      after a mod's GameTest run: records the listed tests' outcomes and prints the tests to retry, one per line
  flaky-tests.py retried <results.json> <list> <test> <log> <status>
      after one retry: records failed_then_passed or failed, prints the warning annotation on a pass, exits 1 on a fail
  flaky-tests.py rates <results.json>...    flake rate of every listed test over these runs (Markdown)
  flaky-tests.py fetch [<runs>]             the same over the last <runs> (default 30) runs' artifacts, via gh

The list (.github/flaky-tests.txt) has one `<namespace:test> <issue> <reason>` per line. A failed run is retried only
when the log names every failed required test and each is on the list; any other failure, a perf budget failure
(PerfBudget already measures twice) or a log without the failure summary is no retry. The results file holds
{"commit", "run_id", "ref", "kind": "main"|"pr", "results": {test: "pass"|"failed_then_passed"|"failed"}} with only
listed tests, also when none ran.
"""
import fnmatch
import io
import json
import os
import re
import subprocess
import sys
import zipfile

OUTCOMES = ("pass", "failed_then_passed", "failed")
ID = r"[a-z0-9_.-]+:[a-z0-9_./-]+"
ENTRY = re.compile(rf"^({ID})\s+(#\d+|https://\S+)\s+(\S.*)$")
SUMMARY = re.compile(r"GameTestServer\]: (\d+) required tests failed")
# Deliberately matches only plain "- <id>: ..." or "- <id>" lines. NeoForge logs a rotated test as "- <id> with rotation
# <r>: ...", which does not match, so the names then fall short of the count and failed_tests() returns None: no retry.
FAILED = re.compile(rf"GameTestServer\]:\s+- ({ID})(?::|\s*$)")
PASSED = re.compile(r"GameTestServer\]: All (\d+) required tests passed")
PERF_FAIL = re.compile(r"perf-result (\S+) \d+ \d+ FAIL\b")


def read_list(path):
    """{test: (issue, reason)}; a line without an issue is an error, so every entry tracks a fix."""
    entries = {}
    with open(path, encoding="utf-8") as f:
        for number, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            match = ENTRY.match(line)
            if not match:
                sys.exit(f"{path}:{number}: expected '<namespace:test> <#issue or URL> <reason>', got: {line}")
            entries[match.group(1)] = (match.group(2), match.group(3))
    return entries


def read_log(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()


def failed_tests(lines):
    """The failed required tests the summary names, or None when there is no complete summary to trust."""
    expected, names, in_summary = None, [], False
    for line in lines:
        summary = SUMMARY.search(line)
        if summary:
            expected, names, in_summary = int(summary.group(1)), [], True
            continue
        if in_summary:
            failed = FAILED.search(line)
            if failed:
                names.append(failed.group(1))
            else:
                in_summary = False
    if expected is None or expected == 0 or len(names) != expected:
        return None
    return names


def perf_failures(lines):
    return {match.group(1) for match in map(PERF_FAIL.search, lines) if match}


def load(path):
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    ref = os.environ.get("GITHUB_REF", "")
    return {"commit": os.environ.get("GITHUB_SHA", ""), "run_id": os.environ.get("GITHUB_RUN_ID", ""), "ref": ref,
            "kind": "main" if ref == "refs/heads/main" else "pr", "results": {}}


def save(path, doc):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=1, sort_keys=True)
        f.write("\n")


def first(results_path, list_path, log_path, mod_id, pattern, status):
    """Records the first run of a mod's GameTests and prints the listed tests to retry."""
    listed = read_list(list_path)
    doc = load(results_path)
    lines = read_log(log_path)
    ran = [t for t in sorted(listed) if t.startswith(mod_id + ":") and fnmatch.fnmatchcase(t, pattern)]
    retry = []
    if status == 0:
        for test in ran:
            doc["results"][test] = "pass"
    else:
        failed = failed_tests(lines)
        if failed is None:
            print("no complete failure summary in the log: no retry", file=sys.stderr)
        else:
            for test in ran:
                doc["results"][test] = "failed" if test in failed else "pass"
            unlisted = [t for t in failed if t not in listed]
            budget = [t for t in failed if t.split(":", 1)[1] in perf_failures(lines)]
            if unlisted:
                print("not on the flaky list, no retry: " + ", ".join(unlisted), file=sys.stderr)
            elif budget:
                print("perf budget failed (PerfBudget measured twice already), no retry: " + ", ".join(budget),
                      file=sys.stderr)
            else:
                retry = failed
    save(results_path, doc)
    for test in retry:
        print(test)


def retried(results_path, list_path, test, log_path, status):
    issue = read_list(list_path).get(test, ("no issue", ""))[0]
    doc = load(results_path)
    passed = PASSED.search("\n".join(read_log(log_path)))
    # A pattern that matched nothing would pass with nothing run.
    ok = status == 0 and passed is not None and int(passed.group(1)) > 0
    doc["results"][test] = "failed_then_passed" if ok else "failed"
    save(results_path, doc)
    if ok:
        print(f"::warning title=Flaky test retried::{test} failed, then passed on retry ({issue})")
    else:
        print(f"{test} failed again on retry ({issue})", file=sys.stderr)
        sys.exit(1)


def rates(docs):
    counts = {}
    for doc in docs:
        for test, outcome in doc.get("results", {}).items():
            if outcome in OUTCOMES:
                row = counts.setdefault((test, doc.get("kind", "pr")), dict.fromkeys(OUTCOMES, 0))
                row[outcome] += 1
    lines = [f"Flake rates over {len(docs)} runs", "",
             "| Test | Kind | Runs | Pass | Failed, then passed | Failed | Flake rate |",
             "|---|---|---:|---:|---:|---:|---:|"]
    for (test, kind), row in sorted(counts.items()):
        runs = sum(row.values())
        rate = (row["failed_then_passed"] + row["failed"]) / runs
        lines.append(f"| `{test}` | {kind} | {runs} | {row['pass']} | {row['failed_then_passed']} | {row['failed']} "
                     f"| {rate:.0%} |")
    if not counts:
        lines.append("| (no listed test ran) | | | | | | |")
    print("\n".join(lines))


def gh(path, *args):
    return subprocess.run(["gh", "api", path, *args], check=True, capture_output=True, text=True).stdout


def trusted_main_run(run_id, runs):
    """Whether a run was a push, schedule or manual run (not a PR from a fork that happens to use a branch named main)."""
    if run_id not in runs:
        runs[run_id] = gh(f"repos/{{owner}}/{{repo}}/actions/runs/{run_id}", "--jq", ".event").strip() in (
            "push", "schedule", "workflow_dispatch")
    return runs[run_id]


def artifact_ids(name, limit, main_only):
    """The ids of the newest `limit` unexpired artifacts called `name`, page by page; for main only those of main runs."""
    ids, runs, page = [], {}, 1
    while len(ids) < limit:
        listing = json.loads(gh(f"repos/{{owner}}/{{repo}}/actions/artifacts?name={name}&per_page=100&page={page}"))
        for artifact in listing["artifacts"]:
            run = artifact.get("workflow_run") or {}
            if artifact["expired"]:
                continue
            if main_only and not (run.get("head_branch") == "main" and trusted_main_run(run.get("id"), runs)):
                continue
            ids.append(artifact["id"])
        if len(listing["artifacts"]) < 100:
            break
        page += 1
    return ids[:limit]


def fetch(limit):
    """The newest `limit` results artifacts of main and of PRs, read straight from their zips."""
    docs = []
    for name, main_only in (("flaky-results-main", True), ("flaky-results", False)):
        for artifact in artifact_ids(name, limit, main_only):
            data = subprocess.run(["gh", "api", f"repos/{{owner}}/{{repo}}/actions/artifacts/{artifact}/zip"],
                                  check=True, capture_output=True).stdout
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                docs.append(json.loads(archive.read("flaky-results.json")))
    rates(docs)


def main(argv):
    if len(argv) == 7 and argv[0] == "first":
        first(argv[1], argv[2], argv[3], argv[4], argv[5], int(argv[6]))
    elif len(argv) == 6 and argv[0] == "retried":
        retried(argv[1], argv[2], argv[3], argv[4], int(argv[5]))
    elif len(argv) >= 2 and argv[0] == "rates":
        docs = []
        for path in argv[1:]:
            with open(path, encoding="utf-8") as f:
                docs.append(json.load(f))
        rates(docs)
    elif len(argv) in (1, 2) and argv[0] == "fetch":
        fetch(int(argv[1]) if len(argv) == 2 else 30)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
