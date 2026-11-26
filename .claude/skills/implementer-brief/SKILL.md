---
name: implementer-brief
description: The short checklist the main session uses to brief an implementer: task, scope, owned paths, known facts, risk tier, model.
---

# Writing an implementer brief

Keep briefs short: point to files instead of restating them. The implementer already reads CLAUDE.md and its agent file.

1. **Task:** one line, plus the PRD sections by number, the milestone page and the item's state file
   `milestones/<M>/item-<NN>.md` (milestone notes) (written by the implementer).
2. **Scope:** a numbered list with the owner's decisions; name the open choices it may make and must report.
3. **Paths it owns**, if anything runs in parallel; otherwise say it owns the chunk.
4. **Known facts** from this session (exact types, config keys, the files to start from), marked when guessed. Tell it
   to read line ranges or grep hits, not whole files: everything read stays in context and is re-sent every turn.
   For displays, the controller and logic, point to the context pack (`docs/context/displays.md`, `controller.md`,
   `logic.md`) instead of file lists.
5. **Risk tier:** say whether the PR will get a review (risky) or merge on CI (normal), so it self-checks accordingly.
6. **Pre-checks:** answer cheap questions before launching: if the item says "check what exists" or depends on an
   outside mod or API, do one search or `rg` yourself and put the answer in the brief (REI/EMI, 2026-10-10: the
   implementer spent context finding EMI had no 26.1.2 build). Name the test cases the PR must add, so a missing test
   doesn't cost a separate fixer run.
7. **Model:** pass `model: "sonnet"` for mechanical and UI work (renames, docs, guide pages, texture drafts, display
   sources, screens); keep the default for persistence, transfers, planners, network logic and anything near the api.

One implementer per item or PR (owner, 2026-10-10): a long-running implementer re-sends its whole, growing context on
every turn, which was most of our usage. Start a fresh one from the milestone page for the next item; continue the same one
only for fixes on its own open PR. Run implementers in parallel only when their areas share no code.

Split an item expected to exceed ~1,500 changed lines into two PRs (for example backend, then screen and display), each
with its own fresh implementer: carried context grows with every turn, so long runs cost the most (usage measurement,
2026-10-10). Point the brief at the area's context pack in `docs/context/` when one exists.

Fixes after review or a CI failure: for a fix under ~50 lines, start a fresh Sonnet implementer with the PR number, the
failure or reviewer points and the suspected cause, instead of resuming the original implementer. A resumed implementer
re-sends its whole earlier context (~200k) on every turn; a fresh one starts at ~20k. Resume the original only for
larger rework where its memory of the code pays off.
