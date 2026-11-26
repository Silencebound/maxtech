# Implementer checklist

The mistakes that most often cost a fix round. Go through it before opening a PR; most PRs merge on this self-check and green CI alone (`docs/workflow.md`).

## Networks and energy

- **Anything a network route or cache reads must invalidate when it changes.** Routes and cached lookups read a device's properties only when they are built. If a device's rates, sides or capabilities change, invalidate its capabilities or the route, or the network keeps using stale values.
- **Never rebuild routes per tick.** A value that swings around a threshold must not invalidate every tick; only a change beyond what routes already know needs a rebuild.
- **Devices never consume from a network they feed**, whatever the player sets a side to.

## Items and duplication

- **Every path that gives items back is a dupe risk.** That covers crafting remainders, drops, spilling on removal, bucket filling, shift-click and a device broken in creative mode. Walk each one: can the player end up with more than they put in?
- **A damageable item with a crafting remainder must call `setNoCombineRepair()`**, or vanilla's repair recipe turns two items into three.
- **Outputs are never voided and inputs are never consumed twice.** Check the full-output path and overlapping inputs.

## Performance

- **No polling.** Wake on events: inventory, energy, neighbour, redstone, chunk load. If you add a periodic check, it must stop once there is nothing to do, and the PR must say why an event can't do it.
- **Nothing on hot paths allocates.** No `Optional`, streams, lambdas, boxing or new collections in tick code or in anything called per insert (ports and buses insert often). Cache registry lookups.
- **Never force-load a chunk.** Read neighbours only through `getChunkNow` checks, and treat what isn't loaded as unknown, not as off.
- **Hooks that fire for every block update** (`onBlockChanged`) must return after one cheap check when there is nothing to do.

## Config and data

- **Balance numbers come from config with preset defaults** in both `standalone.json` and `pack.json`, never Java constants. Recipe data in JSON is fine; say in the PR why a number is data rather than config.
- **A recipe naming a tag that no pack defines fails to load.** Write an empty tag in datagen for inputs that only other mods supply.
- **A recipe that needs another mod's item** uses `compat.<modid>` data recipes gated on `mod_loaded`, or gets a fallback through `RecipeConditions.withoutMod`; the reachability GameTests enforce this.
- **A switch that turns content on or off** is a COMMON config boolean under the top-level `[modules]` section of its mod's own common config, keyed by feature (`modules.voidGeodes`), and its `ContentToggle` id is that key in snake_case (`void_geodes`). Add it to the module switch table on the mod's guide index page.

## Tests

- **Every test must be able to fail.** Break the expectation or the code once, see the test fail, then restore it, and say in the report that you did.
- **Tests must not depend on batch order.** GameTest batches run in random order; clean up in teardown, and never rely on another batch's state.
- **Config changes in tests go through a test environment that restores the values** (see `ConfigGameTests`), never `set`/`save` directly (issue #43).
- **Cover the edge a reviewer will ask about:** unloaded neighbours, mixed tiers, removal mid-run, full output, creative mode.

## Merging

- Merge origin/main normally once your branch is pushed (`scripts/merge-main.sh` handles generated files). Two PRs that each pass can fail together, so CI re-runs everything.

## Docs

- **Update what your change makes stale:** the milestone's `state.md`, guide pages, Javadoc that describes old behaviour, and skill entries for every API fact you verified (`SKILL.md` or `storage-facts.md`).
- **The PR description matches the final diff**, including anything added in fix rounds.

## Local runs

- Build what you changed (the scoped build in CLAUDE.md) and run only the GameTests you touched; CI runs the rest.
- Perf tests and hot-path changes: follow the `perf` skill (which `PerfBudget` method, repeatable measurements, running on a quiet machine).
- A change anyone would see in game: follow the `client-check` skill, and say in the PR what you checked in a client.

## Recurring findings (check each before opening the PR)

- Docs made stale by the change: guide pages, JEI info text, config names in docs, the milestone's `state.md`.
- An edge path without a test: refusals, empty/zero cases, save-and-load, the "other addon absent" case.
- Addon independence: every recipe input reachable with Lib plus Storage alone (the reachability GameTests), compat recipes gated on `mod_loaded`, fallbacks via `RecipeConditions.withoutMod`.
- Payloads: container id, reach, ranges and list sizes checked on the server; decode inside the handler; rate limit.
- Hot paths: no allocation, lambdas or boxing per tick; cadence instead of polling; cached lookups.
- Tests that can't fail: break each new one once.
- New enum values or kinds: tests that enumerate them derive expectations from the code, not a hard-coded list.
