# CLAUDE.md — MaxTech Storage

Two NeoForge mods for Minecraft 26.1.2 (Java 25): **MaxTech Storage** (`maxtech_storage`, `storage/`) and the library it
builds on, **MaxTech Lib** (`maxtech_lib`, `lib/`). Each is split into `common` (game logic) and `neoforge` (the
`Platform` implementation and loader glue). Requirements: `PRD.md` and `storage/PRD.md`. Mod rules: `storage/CLAUDE.md`,
`lib/CLAUDE.md`. How we work (risk tiers, review, agents, CI): `docs/workflow.md`. Packages: `dev.silencebound.maxtech.lib`
and `dev.silencebound.maxtech.storage`; public api under `.api`; compat under `<mod>.compat.<modid>`.

## Commands

```
./gradlew :lib:common:build :lib:neoforge:build :storage:common:build :storage:neoforge:build   # normal check
./gradlew :storage:neoforge:runGameTestServer -Pgametest='maxtech_storage:foo_*'   # only your tests while iterating
./gradlew :storage:neoforge:runData              # datagen (same for lib); commit the generated files
./gradlew :storage:neoforge:runClient            # dev client; /maxtech_storage showcase [<station> [here]|list|clear|reset-steps]
./gradlew spotlessApply                          # format before committing
scripts/pr.sh merge <pr>                         # wait for green CI and squash-merge (run in the background)
```

Optional mods join dev runs only behind a flag (`-P<flag>=true`, or set it in `~/.gradle/gradle.properties`); never the
jar, datagen or CI:

```
-PwithAe2=true        # AE2 bridge; also GameTests: -Pgametest='maxtech_storage:ae2_*'
-PwithRs=true         # Refined Storage bridge; also GameTests: -Pgametest='maxtech_storage:rs_*'
-PwithKubeJs=true     # KubeJS recipe schemas and script nodes (client and server only, never GameTests)
-PwithRei=true        # REI instead of JEI
-PwithCurios=true     # gadgets in Curios slots; also -Pgametest='maxtech_storage:curios_*'
-PwithFtbChunks=true  # FTB Chunks claim tests (All Rights Reserved: dev-only, never bundled)
```

## Facts that bite

- Minecraft 26.1.x is unobfuscated; NeoForge changed a lot since 1.21. Never trust memory for APIs. Order of truth:
  the `neoforge-26-1` skill (search it with `rg`, don't read it whole), `~/mc-reference/`, Gradle sources jars, web docs.
  Record what you verify in `SKILL.md` or `storage-facts.md` in the same PR.
- `common` may import only NeoForge's `transfer`, `fluids`, `capabilities` and `model.data`; everything else goes through
  `Platform`. Rendering only through Blaze3D/vanilla APIs, never raw GL. Target is 26.1.2; no 26.2 APIs.
- Error Prone and NullAway fail the build. Fix findings; suppress only a real false positive, with a one-line reason.
  Everything is non-null unless `@Nullable`; api methods return `Optional` or empty, not null.

## Rules that catch real bugs

- **Performance (PRD §8.1):** no tickers on cables or idle devices; work runs on the network's scheduler and sleeps
  until an event. No `getCapability`/`getBlockEntity` per tick (use caches). Hot paths: no streams, lambdas, boxing or
  new collections. Keep the `perf_*` GameTests in budget on a quiet machine (CI only warns on them); never loosen a
  budget or disable a test to make one pass.
- **Never dupe or lose items:** every move runs in a NeoForge transfer transaction against the real handler and counts
  only what actually moved. Storage's cells are the source of truth; caches are views.
- **Networks:** cables are Lib conduits (one `ConduitType` per block, no BER, never tick). Graphs are topology only and
  rebuilt lazily; respect `networks.unloadedChunkMode` and anchors.
- **Optional mods:** compat code only under `compat.<modid>`, loaded behind `ModList.isLoaded`; Storage must run with
  Lib alone (GuideME is bundled inside the Storage jar, not an optional mod).
- **Data and config:** use datagen for JSON; all player-facing text via lang keys; recipe inputs use `c:` tags; balance
  numbers come from config with defaults in both preset JSONs (`<mod>/common/src/main/resources/<modid>/presets/`).
  Blocks drop with their contents and settings.
- **Api:** public types in `src/api` need Javadoc (purpose, threading, null/empty). An api change bumps the mod's
  version and is called out in the PR.
- **Tests:** JUnit for pure logic, GameTests for level behaviour; a bug fix adds a regression test. A new test must fail
  once when you break the code it covers.

## More mods in this repository

All of the owner's MaxTech mods live here, each as `<mod>/common` and `<mod>/neoforge` with its own version line and
changelog (owner 2026-11-24). The archived monorepo grew into a feature-bloated mess, so:

- A mod gets a folder only when work on it starts. No placeholders or half-started mods; concepts stay in the notes.
- Mods use each other only through Lib's api, never another mod's internals.
- Shelving a mod means deleting it from `main`; git history keeps the code.
- One active mod besides Lib at a time, until it has shipped.

## Written output

- Never write the owner's real name; the public identity is Silencebound. Claude Code's attribution lines in commits
  and PRs are fine (owner 2026-11-25).
- Commits: Conventional Commits, scope `storage`/`lib`/area. The subject says what changed in plain words, about 50
  characters (`fix(storage): keep unknown fluids in interfaces`), no "and", no period. A body only when the why isn't
  obvious from the subject, one to three short lines.
- PR descriptions: one to three short sentences on what was wrong or what is new, plus `Fixes #N` when there is an
  issue. Don't restate the diff, list files or tests, or summarise at the end. Examples and words to avoid: the
  `pr-flow` skill's "Writing commits and PRs".
- Docs and comments: plain, factual, explain why, no marketing or filler.
- Formatting: Spotless (Eclipse, 4 spaces, 120 columns). Don't reformat files you didn't change.

## Milestones and private notes

Milestone pages, item state files, release docs and machine setup live in the owner's private notes, not in this
repository; `CLAUDE.local.md` (ignored, per checkout) imports their index with the current milestone. "The milestone
notes" in skills, agents and docs means that folder. Without `CLAUDE.local.md` there are no milestone notes: ask the
owner instead of guessing.
