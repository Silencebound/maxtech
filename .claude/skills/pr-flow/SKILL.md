---
name: pr-flow
description: How the main session takes a PR to merged by risk tier (trivial, normal, risky) with scripts/pr.sh. Read before merging or watching any PR.
---

# PR flow for the main session

`scripts/pr.sh` does the mechanical part; use it instead of hand-written `gh` polling loops.

| Command | What it does |
|---|---|
| `scripts/pr.sh status <pr>` | One line: state, head, base, mergeable, auto-merge, CI |
| `scripts/pr.sh wait <pr>` | Blocks until CI on the current head finishes; exit 0 green, 1 failed |
| `scripts/pr.sh merge <pr>` | Merges `origin/<base>` into the PR branch only when GitHub reports a conflict (temporary worktree, normal merge commit, push; stops on a real conflict and names the files); a branch that is only behind merges as it is, without another CI run (owner 2026-11-28; `main` does not require up-to-date branches, and the build on `main` after the squash catches a clash), then waits for green CI on that head, squash-merges, retargets PRs stacked on it, merges the base into each and pushes (no force), then fast-forwards the owner's main checkout when it is on main, clean and only behind |
| `scripts/pr.sh auto <pr>` | Merges the base in only on a conflict and turns on auto-merge, then returns; GitHub merges it once the build and `review-gate` are green (stacked children are not fixed up; use `merge` for those) |
| `--dry-run` (with `merge`, `merge-all`, `auto`) | Only says whether the base would merge in, or whether the stack resolver would resolve the conflict; commits, pushes and merges nothing |
| `scripts/status.sh` | Table of open PRs (CI, `review-gate`, latest `Verdict:` with `@sha` when it is for an older commit, mergeable, auto-merge) and of the worktrees under `.claude/worktrees` with their PR's state |
| `scripts/status.sh --clean` | Also removes worktrees whose PR is merged or closed and that have nothing uncommitted, untracked or unpushed; dirty, locked and the current worktree are listed, never removed (no `--force`); local branches stay |

Run `wait` and `merge` with `run_in_background`, never as a foreground sleep loop.

## Stacked PR after its base was squash-merged (2026-11-28)

B stacked on A, A squash-merged: B then conflicts with `main` in files A touched (add/add or content), because the
squash repeats A's changes under a new id. `pr.sh merge`, `merge-all`, `auto` and the child fix-up after a merge
resolve this with `scripts/stack-resolve.sh`: B's version of every conflicted file is kept, committed as
`Merge origin/main into <branch>` and pushed without force, only when B contains A's work in those files (A's final
head is an ancestor of B, or B has some of A's own commits and none of A's later commits touches a conflicted file)
and no commit on `main` that B lacks, other than A's squash, touches them. Otherwise it stops and lists the files as
before. A is found as the most recent PR merged into B's base whose own commits B contains. Tests:
`scripts/test/test-stack-resolve.sh` (scripted repository, no network).

## Writing commits and PRs (owner 2026-11-25)

Write like the maintainers of AE2 or Refined Storage: short, concrete, from the player's or the developer's side. Read
it back once and cut whatever the diff already says.

- Subject: one change, about 50 characters. `fix(storage): drop kept slots with the block`, not
  `fix(storage): ensure kept interface slots and tanks are correctly preserved and restored when broken`.
- Commit body: usually none. When the reason is not obvious, one to three lines: `NeoForge's handler drops the whole
  list when one entry names a removed mod.`
- PR body: one to three sentences and `Fixes #N`. Good: `A placed interface lost its whole stock when one slot named a
  removed mod. It now keeps that slot as saved and loads the rest. Fixes #12.` Too much: a paragraph per class,
  "This PR...", a list of the new tests, a closing summary.
- Avoid: ensure, robust, comprehensive, seamless, leverage, utilize, streamline, "in order to", "now" in every sentence,
  em dashes, bold, headings or bullet lists in a short PR, and the same five-sentence shape every time.
- Mention tests only when something about them is unusual (a flaky one, one that needs a flag).

## Merge gate (2026-10-19)

Branch protection on `main` requires the build check and the `review-gate` commit status (`.github/scripts/review-gate.sh`, run by `.github/workflows/review-gate.yml`). Label every PR with its tier when opening it (`gh pr create --label trivial|normal|risky`). The gate fails without a tier label or with `needs-owner` (use that when the owner wants to look first), passes `trivial` and `normal`, and passes `risky` only on the latest `Verdict: Approve` review on the head (or on a commit followed only by merges of main). `pr.sh merge` turns auto-merge on, so the PR also merges if the background job ends early. Fork PRs: the review event cannot set the status, so the owner merges them.

## Steps

1. **Trivial PR** (main session's own docs, config, small fixes), labeled `trivial`: merge with `scripts/pr.sh merge <pr>` once CI is green.
2. **Normal PR** (implementer, self-checked): skim the diff and the implementer's report, then `scripts/pr.sh merge <pr>`. No reviewer.
3. **Risky PR** (transfers, persistence, planner/executor, `src/api`, `Platform`, build-logic): launch one `reviewer` with the PR number and two or three focus points. Musts go back to the implementer and then to the same reviewer; shoulds are fixed on the branch without re-review. Merge with `scripts/pr.sh merge <pr>`.
4. **Stacked PRs** only within one chunk's own chain; `scripts/pr.sh merge` retargets and updates the child. Never auto-merge with `--delete-branch` a PR that another open PR uses as its base.
5. **After a merge:** tell running implementers on overlapping files to merge origin/main.

## Idle Gradle daemons (2026-10-03)

After a wave of PRs has merged, or when builds feel slow in a long session, run `scripts/gradle-idle-cleanup.sh` (default: stops daemons that are idle now and were started more than 2 hours ago; `DRY_RUN=1` to preview). It only stops IDLE daemons and re-checks each one just before stopping it, so busy builds, CI jobs, dev games and other worktrees are never touched. Never use `./gradlew --stop` for this.

## CI failures

Read the failed step with `gh run view <run> --log-failed`. A "Gradle build daemon disappeared" or runner crash is infrastructure: `gh run rerun <run> --failed`. Anything else goes back to the author (three rounds, then stop and report, CLAUDE.md).

**Flaky GameTests** (2026-11-28): `.github/flaky-tests.txt` lists tests that fail on hosted runners without a code
cause (`<namespace:test> <#issue> <reason>`). `.github/scripts/gametests.sh` retries a failed GameTest step's tests one
by one only when every failed test is listed and none failed a perf budget; a listed test failing twice, or any unlisted
failure, fails as before. A pass on retry shows a "Flaky test retried" warning; the first run's error annotation stays.
Add a test when it failed on a change that cannot have caused it, and open an issue for it first: the list is not a fix,
and the entry leaves with the fix. `python3 .github/scripts/flaky-tests.py fetch [runs]` prints each listed test's
flake rate over main's nightlies (kept 30 days) and PR runs (14 days).

## Stalled auto-merge, merging main, doc size (2026-10-01)

- **Stalled auto-merge:** `pr.sh wait` and `pr.sh merge` now handle it. With auto-merge on, once CI is green they give GitHub 3 minutes (`AUTO_MERGE_GRACE`, counted from the last check's completion); if the PR is still open they print `auto-merge stalled ... merging it here` and run the normal merge path, stacked children included. Without auto-merge, `wait` returns on green and `merge` merges as before.
- **Merging main into a branch:** run `scripts/merge-main.sh` in the branch's worktree instead of hand-resolving. It keeps both sides (main's, then ours) in `.claude/skills/*/SKILL.md` and `docs/IMPLEMENTER_CHECKLIST.md` (not yet in `storage-facts.md`: resolve that by hand, keeping both sides), takes main's side of generated files and regenerates every mod's datagen with the cache cleared, refuses to commit if a conflict marker is left anywhere, and stops without resolving anything on any other conflict (it lists the files). It never pushes; build, run the GameTests, then `git push origin HEAD`.
- **Doc size guard:** CI fails a PR that grows a `docs/**/*.md`, `SKILL.md`, `CLAUDE.md` or `PRD.md` more than 10x in lines, or past 5,000 lines (`.github/scripts/doc-size-guard.py`). It has caught a looped replace before; send the PR back to the author, never raise the limits. Run it locally with `python3 .github/scripts/doc-size-guard.py origin/main`.
- **Perf budgets on a busy machine:** every mod's perf GameTests hand their measurement to `lib.neoforge.perf.PerfBudget` (`measureUnder` within one call, `measureTicks` over ticks). Over budget, it measures once more with the same windows and budget, and only the retry counts: the log shows `measured X ms against a Y ms budget, measuring again` and then `retry measured ...`, and a pass on the retry prints a `Perf budget retried` warning annotation on the PR checks. A retry over budget fails, unless the machine was busy during it: then the log says `skipped budget: machine busy` with a `Perf budget skipped` annotation. Busy means processes other than the test JVM used over 50 % of all CPUs during the retry, read from `/proc/stat` and `/proc/self/stat` on Linux; without `/proc` the 1-minute load per CPU above 1.5 decides (macOS), and without a load average a CPU load sample (Windows). The log line says which signal was used. Retries on CI are a hint of noise on the host; the same test retried often is worth a look. A skip in a local run proves nothing; re-run that test alone before trusting it.

## What CI runs when (2026-10-01)

`.github/scripts/changed-areas.sh` classifies the diff against the first parent (the base on a PR, the previous main commit on a push) into `docs_only`, `lib`, `storage` and `all`. `lib/**`, `build-logic/**`, `gradle/**`, `*.gradle.kts`, `gradle.properties`, `.github/**` and any unrecognised path set `all`, which flags both mods; so do `workflow_dispatch` and the nightly run. `storage/**` alone sets `storage`. A Lib change that touches only client code (`lib/client`, `lib/api/client`, `lib/neoforge/client`), Lib's hand-made assets or Lib unit tests sets `lib_client` instead of `all`: build and Lib datagen, no GameTests, since GameTests run headless and never load client code. A changed client class that non-client code imports (outside any `client` package, unit tests and Lib datagen) still sets `all` (2026-11-28). Two more flags, `ae2` and `rs`, run the bridge GameTests against the real mods in steps of their own (`-PwithAe2=true -Pgametest=maxtech_storage:ae2_*`, `-PwithRs=true ... rs_*`): always nightly and on manual runs; on a PR only when it touches `compat/ae2` or `Ae2*GameTests` (ae2), `compat/refinedstorage` or `Rs*GameTests` (rs), or `storage/bridge`, `BridgeGameTests` or `storage/neoforge/build.gradle.kts` (both). A lib or build-logic change alone does not set them (2026-10-15).

- **Docs-only PR or push** (only `*.md`, `docs/**`, `.claude/**`, `scripts/**`, `LICENSE`, `README`): conflict markers (plus the doc size guard on a PR), then green. The `build` check still reports.
- **Other PR:** `./gradlew build` always; GameTests and datagen only for the flagged mods. No server smoke.
- **Push to main:** conflict markers and `./gradlew build` (compile and unit tests); skipped build for docs-only commits. No GameTests, datagen or server smoke.
- **Perf budgets only warn in CI** (GitHub-hosted runners, since 2026-11-25): a "Perf budget over" annotation means run those `perf_*` GameTests locally before merging, where the budget fails.
- **Perf trends** (2026-11-28): a same-repo PR that ran GameTests gets one "Perf trends" comment (updated on every
  push) listing `perf_*` tests over 20% slower or faster than main's last nightly, and tests without a baseline. The
  separate `perf trends` job never blocks a merge; a listed slowdown in the PR's area is checked on a quiet machine
  like a budget warning (perf skill).
- **Nightly (01:00 UTC) on main:** everything, including every GameTest suite, datagen and the dedicated-server smoke. A failure opens or comments on the "Nightly CI failed" issue. A broken GameTest, generated file or server start from a merged PR shows up there, not on main's push check.
