# MaxTech Lib api for 1.0

Inventory of Lib's public api before the 1.0 freeze (PRD §5.8, §5.10), as of Lib 0.9.0. The api is everything under
`dev.silencebound.maxtech.lib.api`, built from `lib/common/src/api` and published as `maxtech_lib-api`; addons compile
against it with `compileOnly(project(":lib:common", "modApi"))` or the published jar. Everything else in
`dev.silencebound.maxtech.lib` is internal, even where it is public Java.

Labels: **stable** = frozen at 1.0, a breaking change needs a Lib major bump. **experimental** = marked
`@ApiStatus.Experimental` (on the package and each type), may change in any minor version, also after 1.0. **internal** = should not be api; marked
`@ApiStatus.Internal` and to be moved out at the next major bump.

## Checks

- Every package, public type and exported member in the api source sets has Javadoc: `checkApiJavadoc`
  (build-logic `CheckApiJavadoc`, part of `check`) fails the build otherwise. Records must name every component with
  `@param`; `@Override` methods inherit their comment.
- javac's doclint runs on the api compile (`-Xdoclint:all/protected`, without the `missing` group), so malformed
  comments and broken `{@link}`s fail the build too. The `missing` group stays off because it demands `@param` and
  `@return` on every method, which the plain prose style here does not use.
- Content (purpose, threading, null or empty) is a review item; `docs/PR_REVIEW.md` lists it.

## Packages and types

The api is `lib.api` (its package-info names the api and states that the rest of Lib is internal, stable) and
the packages below, one page each in `docs/api/lib/` with a row per type, so parallel PRs that touch different
packages don't edit the same file. A new package gets its own page and one line here, in alphabetical order.

- [`lib.api.carried`](lib/carried.md)
- [`lib.neoforge.api.client.conduit`](lib/client.conduit.md)
- [`lib.api.client.gui`](lib/client.gui.md)
- [`lib.api.client.resource`](lib/client.resource.md)
- [`lib.api.conduit`](lib/conduit.md)
- [`lib.api.conduit.model`](lib/conduit.model.md)
- [`lib.api.config`](lib/config.md)
- [`lib.api.energy`](lib/energy.md)
- [`lib.api.guide`](lib/guide.md)
- [`lib.api.info`](lib/info.md)
- [`lib.api.inventory`, `lib.neoforge.api.inventory`](lib/inventory.md)
- [`lib.api.machine`](lib/machine.md)
- [`lib.api.menu`](lib/menu.md)
- [`lib.api.network`](lib/network.md)
- [`lib.api.recipe`](lib/recipe.md)
- [`lib.api.registry`](lib/registry.md)
- [`lib.api.resource`](lib/resource.md)
- [`lib.api.schedule`](lib/schedule.md)
- [`lib.api.security`](lib/security.md)
- [`lib.api.style`](lib/style.md)
- [`lib.api.text`](lib/text.md)
- [`lib.api.transfer`, `lib.neoforge.api.transfer`](lib/transfer.md)

## Lib internals that Storage uses

Storage compiles against all of `lib:common` (`storage/common/build.gradle.kts`), not only the api jar, and imports
these internal packages. A third-party addon cannot use them through the api jar. Counts are import lines in `storage/`
on 2026-10-15.

| Package | Purpose | Imports | Label |
|---|---|---|---|
| `lib.platform` | Loader seam for `common` code. | 233 | internal |
| `lib.client.gui` | Screen helpers, side tabs and the editor toolkit; the Accent widgets are api since Lib 0.15.0. | 113 | internal |
| `lib.content` | Content builder (blocks, items, datagen declarations). | 72 | internal ("until it settles") |
| `lib.conduit` | Conduit block, block entity, item and per-conduit data. | 70 | internal |
| `lib.client` (top level) | Client glue. | 60 | internal |
| `lib.info` | Scanner and info formatting. | 52 | internal |
| `lib.energy` | FE buffers and transfer helpers. | 26 | internal; waits for the energy decision |
| `lib.resource` | Key interning, counts, palette codec. | 26 | internal |
| `lib.network` | `NetworkBootstrap`: config and lifecycle for `lib.api.network.NetworkManager`. | 25 | internal |
| `lib.text` | Rich text (text fitting is api since Lib 0.15.0). | 25 | internal |
| `lib.schedule` | `LevelTasks`: one scheduler per level, behind `lib.api.schedule.TaskSchedulers`. | 15 | internal |
| `lib.sync` | Synced lists for open screens. | 15 | internal |
| `lib.highlight` | World highlights. | 11 | internal |
| `lib.config` | Lib's own configs and the preset machinery behind `lib.api.config`. | 10 | internal |
| `lib.save` | Save versions and migrations. | 8 | internal |
| `lib.facade`, `lib.tool`, `lib.registry`, `lib.viewer`, `lib.concurrent` | Smaller helpers. | 2-5 each | internal |
| `lib.neoforge.perf`, `.datagen`, `.platform`, `.compat` | Perf budgets for GameTests, datagen providers, loader glue. | 25 | internal |

## Conduits from other mods

Supported since Lib 0.14.0 (ADR-0015, superseding ADR-0004; first user Farms, FM1). An addon on the api jar alone
registers a network type with its `NetworkLogic` (`lib.api.network`) and a conduit through
`Conduits.builder(modId, name, type)`. Lib makes the block, item and block entity, saves, syncs and draws them, takes
dyes and facades, and adds each conduit to its type's network. With `look(...)` Lib also supplies the block state file
and item model definition; a conduit without a loot table drops its own item. How-to, files to ship and the block
state format: `docs/api/conduit-addon.md`.

Not supported:

- Subclassing Lib's conduit block or block entity, now or later. Behaviour changes go through the builder's hooks
  (`ConduitWrenchHook`, `ConduitConnectionRule`); the conduit in the world is a `ConduitView` or `ConduitHost`.
- Replacing Lib's conduit model. A conduit marked `customModel` supplies its own quads through a `ConduitModelHook`
  (NeoForge api jar), but Lib's model still picks the parts, caches them and draws facades.
- Lib's content builder (`lib.content`) and conduit classes (`lib.conduit`) stay internal; Storage still uses them.

`ConduitType`, `ConduitTypes`, `ConduitFaceMode` and `ConduitState` stay stable. The addon entry points and the moved
helpers and engine types are experimental until Farms settles them.

## Findings and decisions

The owner decided the findings of the 1.0 inventory on 2026-10-15; Lib 0.9.0 carries the breaking ones.

1. **Storage depends on Lib internals, but pinned Lib like an addon.** Done: Storage pins exactly one Lib minor
   (`lib_version_range=[0.9,0.10)`), and a Lib minor bump moves the range in the same PR. The rule is in
   `docs/workflow.md` and `lib/CLAUDE.md`. Promoting the parts Storage needs to api stays post-1.0 work.
2. **The conduit api cannot be used alone.** Done: third-party conduit types were not supported in 1.0; superseded in
   Lib 0.14.0, where addons register conduits through `Conduits` (section above, ADR-0015). The package stays stable.
3. **Nullable returns.** Done: `ConduitType.createState()` returns `Optional<ConduitState>`, `CarriedItems.locate` and
   `CarriedItemSource.locate` return `Optional<CarriedSlot>`. `BlockInfoRequest.side` and `hit` stay `@Nullable`
   record components, documented.
4. **Experimental at 1.0.** Done: `lib.api.guide` and `lib.api.carried` stay experimental after 1.0, marked
   `@ApiStatus.Experimental` on the package and on every type; their Javadoc says they may change in any Lib minor
   version.
5. **Names that will age.** Done for the colour: `ConduitType.placeholderColour()` is now `baseColour()`, the body
   colour Lib draws the conduit in wherever its look uses the type's colour (the default look does), tinted by a dye
   (`ConduitLook`, `ConduitColours`); it was never tied to missing textures. Open: `StackKey.base()` returning
   `Object`, and `ItemOutput.chance` (int, hundredths of a percent) against Storage's `PatternOutput.chance` (float).
6. **Lib-internal hook in the api.** Unchanged: `OwnerSecurity.setOpsBypassSource` stays `@ApiStatus.Internal` until
   the next major bump; `CarriedItems.unregister` exists for tests, documented.

## Breaking changes in Lib 0.9.0

- `ConduitType.createState()` returns `Optional<ConduitState>` instead of a nullable `ConduitState`.
- `CarriedItems.locate(Player, Class)` and `CarriedItemSource.locate(...)` return `Optional<CarriedSlot>` instead of a
  nullable `CarriedSlot`.
- `ConduitType.placeholderColour()` is renamed `baseColour()` (constructor parameter likewise).
- `lib.api.guide` and `lib.api.carried` are marked experimental at package level (no signature change).

## Breaking changes in Lib 0.13.0

- Lib's guide is retired (MGM item 3): `lib.guide`, `lib.client.guide`, the guide screen, keybind, book item, live
  values, recipe sync and the dev reload command are gone, and with them `Guides`, `GuideElements`, `GuideColumns`,
  `GuideLiveValues` and `GuideScreens`. `GuideConfigValues` stays (Storage's GuideME `<Config>` tag reads it). The
  client config key `guide.hotReload` is gone.

## Javadoc pass (#202, non-breaking)

Javadoc added where it was missing (record components and compact constructors, codec fields, enum constants,
threading notes on `OwnerSecurity`, `ConduitType` and `BlockInfoRequest`); a stale reference in `ConduitType`'s
constructor Javadoc to a class that no longer exists replaced; `@ApiStatus.Internal` on
`OwnerSecurity.setOpsBypassSource`. No signature changed, so no version bump.
