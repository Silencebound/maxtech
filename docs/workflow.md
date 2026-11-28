# How we work

Owner decision 2026-10-06: match the process to the risk, keep context small, and let CI do the testing.

## Risk tiers

| Tier | Examples | Who does it | Review |
|---|---|---|---|
| Trivial | docs, comments, config values, renames, one-file fixes | the main session, directly | none; merge on green CI |
| Normal | a feature or device on existing systems | one implementer | self-check against `docs/IMPLEMENTER_CHECKLIST.md`; merge on green CI |
| Risky | item and fluid transfers, anything that can dupe or lose items, persistence, the planner and executor, `src/api`, `Platform`, build-logic | one implementer | one independent reviewer (`docs/PR_REVIEW.md`) |

A reviewer's should findings are fixed on the same branch; fixes need no second review unless they change other logic.
Nothing is reviewed twice by default.

Every PR carries its tier as a label (`trivial`, `normal`, `risky`). The `review-gate` status, required on `main` with
the build check (owner decision 2026-10-19), fails without a tier label or with `needs-owner`, and for `risky` waits
for a reviewer's `Verdict: Approve` on the head (later merges of main keep it). With both green, GitHub's auto-merge
merges the PR, so merges no longer wait for the owner. A fix pushed after an approval needs a short re-verdict from the
same reviewer.

## Work units

- One implementer per milestone chunk, working sequentially on one branch; one or two PRs, soft cap about 3,000
  changed lines outside `src/generated`.
- Run areas in parallel only when they share no code. Otherwise run them one after another; stand-in interfaces and
  re-wiring cost more than the wall time saved.
- Mechanical work (renames, docs, guide pages, texture drafts) runs on the faster model.

## Context and reports

- Briefs point to files and PRD sections; they don't restate them. Each item's state (what exists, where, which config
  keys, open choices) goes in its own `milestones/<M>/item-<NN>.md` (milestone notes), and the item's line on the milestone page
  gets a link to it. Parallel PRs then never edit the same section; older items keep their sections on the page.
- Write ADRs only for real architecture decisions.
- Agent reports are at most about 150 words: PR number, what changed, risks or guesses, anything the owner should decide.

## Testing and CI

- Locally, run the scoped build and only the GameTests for what you touched (`-Pgametest='maxtech_storage:prefix_*'`).
- CI runs the full build, every GameTest and the datagen check on each PR, on GitHub-hosted `ubuntu-latest` runners
  (owner 2026-11-25; forks run the same way). All steps run in bash; keep scripts portable across Linux, macOS and Git
  Bash. A nightly run does everything and opens a "Nightly CI failed" issue on failure; check it at the start of a
  session.
- The bridge GameTests against the real AE2 and Refined Storage (`ae2_*` with `-PwithAe2=true`, `rs_*` with
  `-PwithRs=true`) run in steps of their own: nightly, manual, and on PRs that touch `compat/ae2`,
  `compat/refinedstorage`, `storage/bridge`, the bridge GameTests or `storage/neoforge/build.gradle.kts`.
- Perf: every perf GameTest goes through `PerfBudget`. In CI a budget over twice only warns ("Perf budget over"),
  since hosted runners vary too much to block on; locally it fails the test (owner 2026-11-25). Before merging a change
  to a hot path, or when CI warns, run the affected `perf_*` GameTests on a quiet machine (Mac or Windows PC).
- CI failures: fix on the same branch, up to three rounds, then stop and report.
- Known flaky GameTests are listed in `.github/flaky-tests.txt`, each with its issue. When every failed test of a
  GameTest step is listed, CI runs each once more on its own; green passes with a "Flaky test retried" warning. The
  outcomes are kept as artifacts; `python3 .github/scripts/flaky-tests.py fetch` prints flake rates (2026-11-28).

## Git

- Work on feature branches in worktrees (`.claude/worktrees/<name>`); `main` is protected and changes only by PR.
  Never force-push.
- Switching machines: the `SessionStart` hook fetches and fast-forwards a clean branch; the `SessionEnd` hook pushes a
  feature branch's unpushed commits (never `main`) and warns about uncommitted files (`.claude/hooks/git-sync.sh`).
- Guards (`.claude/hooks/guard.py`, a `PreToolUse` hook) refuse force-pushes, pushes to `main`, `./gradlew --stop`,
  killing Java or Gradle by name, hand edits under `src/generated/`, and any text carrying the owner's private identity
  (checked by hash). The session-start hook also reports a failed nightly run and open PRs.
- The status line (`.claude/statusline.sh`) shows branch state, CI on the branch, open PRs, runners, model and context, plan usage,
  and session cost; GitHub data is cached and refreshed in the background.
- Bring main into a pushed branch with a normal merge. Generated files are regenerated, never hand-merged.
- Merge PRs with `scripts/pr.sh merge <pr>` (usage: `scripts/pr.sh` without arguments; more in the `pr-flow` skill).
  It merges only on an up-to-date base (owner decision 2026-10-18): when the PR branch does not contain the current
  `origin/main` (the PR's base), it merges it in through a temporary worktree, pushes and waits for CI on the new head;
  on a conflict it stops and names the files, to be merged by hand (`scripts/merge-main.sh`).
- Milestone items: the `milestone-item` skill (`.claude/skills/milestone-item/SKILL.md`) lists what every item PR
  needs, on top of `docs/IMPLEMENTER_CHECKLIST.md`.
- Versions: Storage imports internal Lib packages (`docs/api/lib-1.0.md`), so each Storage release pins exactly one Lib
  minor in `lib_version_range` (e.g. `[0.9,0.10)`), and a Lib minor bump moves the range in the same PR. Third-party
  addons use only the api jar and may declare a wider range. An api change bumps the mod's version (minor before 1.0).
- User-visible PRs add one line under `Unreleased` in `lib/CHANGELOG.md` or `storage/CHANGELOG.md`; a release renames it to the version.
- Squash-merge with the PR title as subject. Approvals are comments starting `Verdict: Approve` (GitHub refuses
  self-approval on the shared account).

### Shared lists

Parallel PRs used to collide in files every feature appends to. Keep each feature's entries in its own file:

- **Add lang keys in your feature's lang class, not in the provider.** Lib's lines live in one `LangSection` class per
  feature in `lib/neoforge/.../datagen/lang/` (`FacadeLang`, `EnergyInfoLang`, ...); a new feature adds its own class
  and one `all.add(...)` line at the end of `LibLangSections` (the file merges with `merge=union`).
  The provider sorts keys and fails on a duplicate, so a bad union merge fails datagen instead of shipping. Storage
  keeps the same split by hand (`Showcase.translations()`, `Ae2Lang`, `StorageRecipeViews`): add a method beside your
  feature and one call in `StorageLanguageProvider`. Farms' provider is still one flat list; split it the same way when
  it grows.
- Lib's api inventory has one page per package in `docs/api/lib/`; `docs/api/lib-1.0.md` only links them. Not
  `merge=union`: PRs edit existing rows, and a union merge keeps both the old and the new row without a conflict.
- Lists whose order matters or that sit in shared Java code (`MaxtechLib.init`, the NeoForge entrypoints, import
  blocks) stay as they are; their conflicts are a line or two and real ordering questions.

## Changing ids or saved formats from the 0.9 beta on

Owner decision 2026-10-19 (#316): worlds from the 0.9 beta load in 1.0, so these rules apply from the beta release on,
not only after 1.0.

Worlds must survive updates within a major version (PRD §8.2), and beta worlds must survive the step to 1.0.

- **Renamed block, item or block entity type id:** keep loading the old id with `Registry#addAlias(old, new)` on every
  start, inside the `RegisterEvent` for that registry (NeoForge facts in the `neoforge-26-1` skill), plus a GameTest
  that loads the old id.
- **Changed saved format:** add a `MigrationStep` (version N to N+1, a pure function over the saved tag) to the format
  in `StorageSaveFormats` (Lib: `lib.save.SaveFormat`), plus a JUnit test of the step and, for block entities, a
  GameTest that loads old data. Block entities, cable parts, saved data and the cell item save `data_version`; data
  without one is version 1. Storage block entities extend `VersionedBlockEntity` and override `loadSaved`, never
  `loadAdditional`.
- Never drop data on load: a newer or unmigratable save loads unchanged, and steps are never removed or reordered.
- A stamp never goes down: data loaded from a newer version is saved again with that version, so a step never runs
  twice. Such data may lack the newer format's fields after an older build saved it; a step may only assume the data is
  no older than its stamp.
- Removing content needs a major version and a deprecation release first.

## Decisions

The main session proposes a plan with defaults; the owner flags only what to change, and judges results in the dev
client. Undecided ideas live in the private notes repo.
