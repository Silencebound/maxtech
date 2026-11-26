# Reviewing a risky PR

Only risky PRs get a review (`docs/workflow.md`): transfers, persistence, the planner and executor, `src/api`, `Platform`
and build-logic. Trust a green CI for the build, GameTests and datagen.

## Must (request changes)

- Items or fluids can be duplicated or lost: moves outside a transfer transaction, counts that don't match what moved,
  give-back paths (remainders, drops, cancel, shift-click, breaking the block) that return more than went in.
- State that must survive a save and load doesn't, or loads wrong.
- A tick or hot path polls, allocates, or looks up capabilities or block entities per tick; a `perf_*` budget was
  loosened or a test disabled.
- Thread-safety: off-thread work touches the level or live network state instead of a snapshot.
- `src/api` changes without Javadoc (purpose, threading, null/empty), or without a version bump.
- `common` imports NeoForge outside `transfer`, `fluids`, `capabilities` and `model.data`.
- A risky path without a test that can fail.

## Should (comment)

- Edge cases worth a test (unloaded chunk, full output, refusal, creative mode).
- Docs or the milestone `state.md` left stale by the change.

## Output

One `gh pr review <n> --comment`, first line `Verdict: Approve` or `Verdict: Request changes`, then findings as
`path:line - problem - fix`, marked must or should. Don't merge; the main session merges on green CI.
