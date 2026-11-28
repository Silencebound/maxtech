---
name: brief-templates
description: Fill-in templates the main session copies into Agent prompts and messages: implementer, reviewer, delta reviewer, fix round, remote PC session task, client check request. Read with implementer-brief before launching any agent.
---

# Brief templates

Copy one block, fill the `<placeholders>`, delete lines that do not apply. Keep it short: point to files, give line
ranges, never restate them (`implementer-brief`, `docs/workflow.md`).

## 1. Implementer (new PR)

```
Task: <one line>. Plan/ADRs: <PRD §n, milestones/<M>/item-<NN>.md, docs/adr/<file>>.
Owner decisions: <numbered list>. Open choices you may make and must report: <list or none>.
Scope: <numbered list>. Out of scope: <list>.
You own: <paths>. Do not touch: <paths>.
Read first (line ranges, not whole files): <path:from-to>, <path:from-to>.
If the request leaves open what the owner wants, stop and ask me; do not build your own reading.
Risk: <trivial|normal|risky>; label the PR with it. <Risky: a reviewer follows. Normal: you self-check, CI merges.>
Tests that must fail when the code is broken: <cases>. Add them.
Local checks, first run: full build, unit tests and the GameTests for <prefix_*>. Push only when green.
Commit ends with: Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
PR body ends with: 🤖 Generated with [Claude Code](https://claude.com/claude-code)
Never write the owner's real name anywhere (code, commits, PR, docs).
Report (at most 150 words): PR number, what changed, risks or guesses, what the owner should decide.
```

## 2. Reviewer (first review)

```
Review PR <#n> (stacked: <#n, #m>, one verdict per PR). Context: <issue, ADR, item file, implementer report>.
Focus: <two or three points>.
Root rules: no dupe or loss of items or fluids; no tickers on cables or idle devices; no allocation on hot paths;
ADR-0002: `common` imports only NeoForge's transfer, fluids, capabilities and model.data; the rest goes through `Platform`.
Name every vacuous test (one that passes with the fix removed) and say what it should assert.
Output: Must / Should / Nit, each as `path:line - problem - fix`. End with exactly one line:
Verdict: Approve   or   Verdict: Request changes
```

## 3. Delta reviewer

```
Re-review PR <#n>. Look only at commits after <sha> (the first review): `git diff <sha>..HEAD`.
Earlier findings: <numbered list, must/should>.
Check each fix is right and that its test fails without the fix (revert it locally and run). Name vacuous tests.
Do not re-review the rest. Output: finding, fixed or not, new issues. End with exactly one line:
Verdict: Approve   or   Verdict: Request changes
```

## 4. Fix round

```
Fix PR <#n> (branch <branch>). Findings: <numbered list: path:line - problem - fix>.
Who: under ~50 lines, a fresh Sonnet (this brief, plus the PR number and suspected cause); larger rework resumes the
original implementer by SendMessage (`implementer-brief`).
Add a test per must that fails without the fix. Local checks: build, unit tests and the targeted GameTests <prefix_*>.
Push to the same branch without force. Stacked PRs: merge the base into each child and push (`scripts/pr.sh merge`).
Commit ends with: Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Report (at most 100 words): what was fixed per finding, anything disputed. Three failed rounds: stop and report.
```

## 5. Remote PC session task (SendMessage to the PC's Remote control session)

```
Run this as a background subagent in its own worktree (`.claude/worktrees/<name>`, branch <branch> off origin/main).
When it finishes, forward its final report to me unchanged.
<the implementer template (1), filled in>
```

## 6. Client check request to the owner

```
Check <what> in the dev client.
Worktree: <path> (branch <branch>). Run: ./gradlew :storage:neoforge:runClient   <add -PwithAe2=true if needed>
In game: <command, e.g. /maxtech_storage showcase <station> here>
Look at: <two or three things>. Expected: <one line>. Tell me what looks wrong; screenshots are welcome.
```
