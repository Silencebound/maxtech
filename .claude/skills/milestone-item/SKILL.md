---
name: milestone-item
description: Checklist for an implementer's milestone item PR in MaxTech Storage or Lib (B09-style items, milestones/<M>/item-NN.md in the milestone notes) - state file, config names and presets, lang and datagen, guide, Jade, textures, recipes, persistence, tests, api, PR text. Use when implementing or self-checking any milestone item before opening its PR.
---

# Milestone item checklist

On top of `docs/IMPLEMENTER_CHECKLIST.md`; adding content: the `add-content` skill. Tick what applies, say in the
report what doesn't and why.

**Issue**
- [ ] The item has a GitHub issue (`kind:item`), a sub-issue of the milestone's parent issue (B09: #319); the main
  session creates it with the brief. The PR body ends with `Closes #<issue>`, so the merge closes it and the parent's
  progress moves.

**Docs of the item**
- [ ] `milestones/<M>/item-NN.md` in the milestone notes (a commit in the notes repository, not in this PR): state (built, tested, what waits for the owner), what exists and where, config
  keys, decisions, open choices.
- [ ] The item's line on `milestones/<M>.md` (milestone notes) ends with `[State](<M>/item-NN.md)`; no new sections on that page.
- [ ] One line under `Unreleased` in `storage/CHANGELOG.md` or `lib/CHANGELOG.md` when players see it.

**Config**
- [ ] Balance numbers are config, never Java constants; defaults in both `standalone.json` and `pack.json`
  (`<mod>/common/src/main/resources/<modid>/presets/`).
- [ ] Every new value or section has a comment (the tooltip, naming the unit) and a short name in
  `StorageConfigNames` / `LibConfigNames`; `*ConfigNamesTest` and `presets_complete` pass.

**Text and data**
- [ ] All player-facing text via lang keys; `./gradlew :storage:neoforge:runData` (and Lib's if touched) run with the
  generated folder clean, generated files committed.
- [ ] Guide page or section: short and factual like AE2's, linked from the index (the `guideme` skill).
- [ ] Jade lines for new blocks: compact line plus details.
- [ ] Textures only from the generator scripts in `storage/neoforge/src/textures` (MTX style 2a); static, state by
  colour; only the controller animates.
- [ ] Recipes use `c:` tags and stay reachable with Lib and Storage alone; a new recipe type gets a JEI category
  (`compat.jei`) and a KubeJS schema (`compat.kubejs`).

**Persistence**
- [ ] Saved by fixed ids (no ordinals, no registry indices); old saves load (`docs/workflow.md`, ids and formats).
- [ ] Blocks drop with their contents and settings, creative breaking included; settings copy with the blueprint card
  (`SettingsCopyable`).

**Tests**
- [ ] GameTests for level behaviour, JUnit for pure logic; each new test broken once and seen failing.
- [ ] `perf_*` GameTests in budget; never loosen a budget or disable a test.

**Code boundaries**
- [ ] No `src/api` change without a version bump called out in the PR.
- [ ] Client code (screens, renderers, models) only in client classes, never loaded on a dedicated server.
- [ ] Compat only under `compat.<modid>` behind `ModList.isLoaded`.

**PR**
- [ ] Merge `origin/main` into the branch (`scripts/merge-main.sh`) and rebuild before opening the PR;
  `git diff origin/main --stat` shows only what you meant to change.
- [ ] Description one to three short sentences (`pr-flow`: "Writing commits and PRs"), matching the final diff, ending with `Closes #<issue>` when there is one.
- [ ] Opened with its tier label (`gh pr create --label normal` or `--label risky`); the `review-gate` check fails
  without one.
- [ ] Report says what was not seen in a dev client (screens, textures, guide, Jade) so the owner can check it.
