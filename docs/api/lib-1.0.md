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

| Package / type | Purpose | Label |
|---|---|---|
| `lib.api` (package-info) | Names the api and states that the rest of Lib is internal. | stable |
| **`lib.api.carried`** | Finding items a player carries (inventory, then registered sources such as accessory slots). | experimental |
| `CarriedItems` | Lookup over the inventory and every registered source; registration of sources. | experimental |
| `CarriedItemSource` | Extra slots another mod adds (accessories). | experimental |
| `CarriedItemMatcher` | Allocation-free predicate with a context for lookups. | experimental |
| `CarriedSlot` | Where a found stack sits, re-read on each use by an open GUI. | experimental |
| **`lib.api.config`** | Config files with presets for every MaxTech mod. Moved from `lib.config` and `lib.platform` for Farms (FM1). | experimental |
| `Configs` | Entry point: `presetConfig(modId, anchor)`, and `builder`/`register` for plain files. | experimental |
| `PresetConfig` | A server config whose defaults come from `<modid>/presets/*.json`. | experimental |
| `ConfigBuilder`, `ConfigFile`, `ConfigType` | Declaring values, the registered file, which file. | experimental |
| **`lib.api.client.gui`** | The Accent Frame look and its widgets for MaxTech screens, client only (ADR-0016). Moved from `lib.client.gui`, `lib.client.gui.accent` and `lib.text` in Lib 0.15.0 for Farms (FM1). | experimental |
| `Accent` | Palette (ARGB) and drawing: panels with a title tab, boxes, slots, bars, button faces, fitted text. | experimental |
| `AccentButton`, `IconButton`, `Icon` | Text buttons (normal and compact, toggles lit while on); icon buttons (plain, toggle, cycle) and what they show (sprite, glyph, swatch, item). | experimental |
| `AccentPanel`, `AccentInset`, `AccentRow`, `AccentTabRow` | A panel as a layout with content edges; a dark well; list rows; page tabs with badges. | experimental |
| `AccentReadout`, `AccentTag`, `AccentStatus`, `AccentToast`, `ScrollBar` | A label and value; a small tag; state colours and dots; an in-screen toast and a notice outside screens; the scroll bar. | experimental |
| **`lib.api.style`** `AccentTheme` | A mod's accent, gauge and text colours; `LIB` is Lib's cyan, the default everywhere. Pure data, both sides; widgets take it through `theme(...)` or overloads. | experimental |
| `AccentBar`, `AccentTank`, `AccentEnergyBar`, `AccentProgress` | The bars' shared base type and the bar widgets drawing plain values from suppliers: a tank of any resource kind with quarter ticks (the kind's look and unit; "Unknown" for unknown contents), a segmented energy gauge (MU, FE in the tooltip), an arrow or flame; tooltips and narration give the numbers. | experimental |
| `FluidLook` | The fluid kind's `ResourceLook` (still and flowing sprite, tint, name, light), with `Fluid` overloads; Lib registers the NeoForge implementation, `FluidLook.get()` returns it. | experimental |
| **`lib.api.client.resource`** `ResourceLook`, `ResourceLooks` | How a resource kind looks on the client (still sprite, optional flowing sprite, ARGB tint, name, light 0..15) and the registry of one look per kind; conduit contents draw with it, a kind without a look draws nothing. Client only. | experimental |
| `TextScale`, `LayoutCheck` | The client's text size option for drawing and measuring (`setOptionSource` is Lib's, `@ApiStatus.Internal`); the 320 x 240 layout check and placement math. | experimental |
| **`lib.api.text`** `TextFit` | Fitting text with an ellipsis; pure Java, both sides. | experimental |
| **`lib.api.conduit`** | Conduit types (a conduit is Lib's generic framework for blocks that connect to neighbours: cables now, later pipes and wires); each type gets its own block from Lib. Addons register their own through `Conduits` (ADR-0015, see below). | stable |
| `ConduitType` | One conduit type: network type, tier, base colour, thickness, face modes, per-conduit state. | stable |
| `ConduitTypes` | The `maxtech_lib:conduit_type` registry key and lookups. | stable |
| `ConduitFaceMode` | How a conduit face meets a block: both, insert, extract, disabled; stable ids. | stable |
| `ConduitState` | Server-side state a conduit saves in its block entity (cable parts). | stable |
| `ConduitLook` | How a conduit block is drawn; its codec is the `look` object of the block state file. Moved from `lib.conduit` for Farms (FM1). | experimental |
| `ConduitLook.Shell` | The `shell` look: translucent glass with border lines and rims Lib computes from the connections; `shell` sprite, optional `border_colour`, `border`, `rim` (Lib 0.14.0). | experimental |
| `ConduitShapes`, `ConduitFaceKey` | A conduit's boxes (core, arms, stubs, rings) in pixels and hit shapes; one face decoration. | experimental |
| `ConduitConnections`, `ConduitFaceModes`, `ConduitColours` | Whether two conduits connect, side masks; six face modes packed in an `int`; body and face colours. `ConduitFaceModes.validateRegistered` is Lib's (`@ApiStatus.Internal`). | experimental |
| `Conduits` | An addon's entry point: `builder(modId, name, type)` with `look`, `creativeTab`, `onWrench`, `connectionRule` and `register()`; `host`, `blockOf`, `connectivity`, `isChunkPresent`, `place`, `useWrench`. Lib owns the block, its block entity, dyes and facades; with `look` it also supplies the block state file and item model definition, and a conduit without a loot table drops its item, so the addon ships the item model and lang. Added for Farms (FM1); `setFactory` and `Factory` are Lib's (`@ApiStatus.Internal`). | experimental |
| `ConduitView`, `ConduitHost` | The conduit in a block: type, colour, side masks, face modes, state, facade and render value; plus setting block faces, face modes and the render value, refreshing links and syncing (server only). The render value (Lib 0.14.0) is an addon-defined `int` its client model reads; it is saved and synced only when it changes. Contents (`contents()`, `setContents`) work the same way with a fixed meaning. | experimental |
| `ConduitEntry`, `ConduitWrenchHook`, `ConduitConnectionRule` | A registered conduit's type, block and item; the builder's hooks for a wrench and for which neighbouring conduits a conduit links with. | experimental |
| **`lib.api.conduit.model`** | Conduit model geometry for an addon's own conduit model (FM1): quads before baking and Lib's own parts. | experimental |
| `ConduitQuad`, `ConduitTexture`, `ConduitLayer` | One face of a model part in pixels; its sprite (one of Lib's, or a custom model hook's by atlas id, Lib 0.14.0); its chunk layer (from the sprite, or forced translucent or cutout). Moved from `lib.client.conduit`. | experimental |
| `ConduitUv` | Which part of its sprite a `ConduitQuad` shows (0 to 16 pixels) and its clockwise quarter turns, as a block model face's `uv` and `rotation`; a quad without one shows the whole sprite unturned (Lib 0.14.0). | experimental |
| `ConduitContents` | What a conduit shows inside it: a resource of any kind as its draw base (a fluid saved by registry name as before, other kinds as `kind` plus `resource`; a kind that is gone loads empty; drawn with the kind's `ResourceLook`; `ofFluid` and `fluid()` for fluids), fill step 0..4, upstream side, second lane; `EMPTY` for none. The static `pack`/`packed*` helpers give the client render key's int (bits 0-15 the session look index + 1, which Lib's client hands out per kind and draw base, 16-18 fill, 19-21 upstream side + 1, 22 lane), valid only within one session; Lib packs its conduits' contents itself. | experimental |
| `ConduitBodyKey`, `ConduitParts` | What a conduit body looks like (type, dye, arm and ring masks, render value, packed contents; the 4- and 5-argument constructors give 0 for what they leave out); Lib's default body and face quads, which ignore the render value and contents. | experimental |
| **`lib.neoforge.api.client.conduit`** | NeoForge-side client api (jar `maxtech_lib-neoforge-api`, Lib 0.14.0, FM1): custom models for conduits marked `Conduits.Builder.customModel(translucent)`. | experimental |
| `ConduitModelHook` | An addon's model of one conduit: its sprites (resolved at bake time) and the body and face quads per key, Lib's own by default; called lazily on meshing threads. | experimental |
| `RegisterConduitModelsEvent` | Client mod-bus event, fired once at startup: `register(ConduitEntry, hook)`. A marked conduit without a hook or a hook for an unmarked one stops the client. | experimental |
| **`lib.api.energy`** | MaxTech energy (MLE): MU amounts and the FE rate, producer, consumer and buffer endpoints, networks with one sleeping pass per tick, per-level schedulers, edges to other mods' FE blocks with export settings (`EnergyEdge`, `EdgeSettings`, Lib 0.11.0), diagnostics (`EnergyNetwork.readout()`, `EnergyReadout`, the endpoint capability `EnergyNetworks.ENDPOINT_CAPABILITY`, Lib 0.12.0). Added in Lib 0.10.0; experimental until Storage's switch-over proves it. | experimental |
| **`lib.api.guide`** | What stays of Lib's guide, retired in Lib 0.13.0 (MGM item 3; GuideME draws guides now): config values by path. `Guides`, `GuideElements`, `GuideColumns`, `GuideLiveValues` and `GuideScreens` are removed. | experimental |
| `GuideConfigValues` | A mod's config values by dotted path, for a guide's `<Config>` tag (Storage's GuideME extension). | experimental |
| **`lib.api.info`** | Block information for the scanner and overlays (Jade). | stable |
| `BlockInfoProvider` | Sided block capability that describes a block; brief and full detail. | stable |
| `BlockInfoRequest` | What was asked: level, position, side, hit, detail. | stable |
| `BlockInfoSink` | Receives lang-keyed lines. | stable |
| **`lib.api.inventory`** | Lib-owned machine inventories over transaction-aware storage, for Farms (FM1, ADR-0016); the methods here are the machine's own committed steps, outside transfer transactions. | experimental |
| `ItemSlots`, `SlotRole` | Fixed slots built from a reusable `ItemSlots.Builder` (role, filter and size limit per slot; change listener per slot index; save and load slot by slot, keeping stacks that no longer load as `unknown` and stacks for slots the inventory lost as `overflow` for the owner to hand out; `dropContents`/`contentsToDrop` empty slots and overflow when the block breaks, unknown items vanish with it); roles say what players and automation may do: inputs take (players may take back), outputs give, display slots neither. | experimental |
| `ResourceTank`, `FluidTank` | One resource of a kind with an amount up to a capacity, optional filter and change listener; `resource()` keeps its `Optional` for menu getters; saved contents that no longer load are kept, saved back and block the tank (`unknown()`, refusing `set`/`clear` until `discardUnknown()`); a save over the capacity loads whole. `FluidTank` is the fluid tank (mB). `InventoryFactory` is Lib's (`@ApiStatus.Internal`). | experimental |
| **`lib.neoforge.api.inventory`** `Handlers` | The NeoForge item and fluid handlers of `ItemSlots` and `FluidTank` for capability registration, one per inventory, taking part in transactions with the slots' roles applied (every slot reports its real capacity, for comparators; one that takes nothing refuses). `install` and `Bridge` are Lib's (`@ApiStatus.Internal`). | experimental |
| **`lib.api.network`** | Network topology for every network type: types, their logic, graphs and the per-level manager; addons register their own network types. | stable |
| `UnloadedChunkMode` | `networks.unloadedChunkMode`: truncate or pause. | stable |
| `NetworkManager` | The per-level entry point: add, remove and invalidate nodes, anchors, a node's network and a type's graph. Moved from `lib.network` for Farms (FM1); `tickLevel`, `clear` and `setConfigSource` are Lib's (`@ApiStatus.Internal`). | experimental |
| `NetworkType` | A kind of network, registered with connectivity and logic factories; `byId` returns an `Optional`. | experimental |
| `NetworkLogic`, `NodeConnectivity` | A type's transport semantics (topology events and a tick per active network) and how it sees the world. | experimental |
| `Network`, `NetworkGraph` | One connected set of nodes (a read-only view) and one level's graph of a type; the graph's constructor, `tick` and `setProfiling` are Lib's (`@ApiStatus.Internal`). | experimental |
| `RouteTree` | Widest-path routes to a root over one component, built when the component is rebuilt. | experimental |
| **`lib.api.recipe`** | Item inputs and outputs shared by machine recipes, the recipe viewer layout and KubeJS. | stable |
| `ItemInput` | Ingredient, count, consumed or catalyst; codecs. | stable |
| `ItemOutput` | Output stack and chance in hundredths of a percent; codecs. | stable |
| **`lib.api.registry`** | Registering a mod's game objects through Lib, for Farms (FM1). | experimental |
| `Registrars` | Entry point: `create(modId, registry)`. | experimental |
| `Registrar`, `RegistryEntry` | A deferred registrar and its entries (`id`, `key`, `holder`, `get`). Lib's `lib.platform` types extend them. | experimental |
| **`lib.api.resource`** | Item and fluid keys with components, interned by networks; resource kinds for holders of any kind (ADR-0017). | stable |
| `StackKey` | Sealed key type with codecs; `Kind`. | stable |
| `ItemKey` | Item plus component patch, never air. | stable |
| `FluidKey` | Fluid plus component patch, never empty. | stable |
| `ResourceKind`, `ResourceKinds` | A kind of resource (id, codecs, draw base, amount unit) and the registry of kinds, filled during mod construction and frozen at Lib's common setup; `register` with a `MapCodec` saves the key's fields next to `kind`, with a `Codec` under `value`; built-in `ITEM`, `FLUID`, `ENERGY`; lookup by id or session index (ADR-0017, Lib 0.15.0). `freeze` is Lib's (`@ApiStatus.Internal`). | experimental |
| `KindKey` | A key of any kind with its kind; save codec on `kind`, `CLIENTBOUND_STREAM_CODEC` that lets a client skip kinds it lacks, and `SERVERBOUND_STREAM_CODEC` for keys clients send, which refuses unknown resources. | experimental |
| `UnknownResource` | A saved resource of a kind no loaded mod registers, kept and saved back unchanged. | experimental |
| `AmountUnit`, `EnergyKey` | How a kind's amounts are counted and shown (items, mB, MU); the one key of energy. | experimental |
| **`lib.api.schedule`** | Budgeted per-level work queues: groups with time and operation budgets, priorities, wake-on-event and backoff. Moved from `lib.schedule` for Farms (FM1). | experimental |
| `TaskSchedulers` | An addon's entry point: `newGroup(level)` with Lib's configured budgets. | experimental |
| `ScheduledTask`, `TaskGroup`, `TaskPriority` | A task, the group that runs it within its budget, and its queue. | experimental |
| `TaskScheduler` | The level's scheduler; its constructor, `tick` and `timers` are Lib's (`@ApiStatus.Internal`). | experimental |
| **`lib.api.security`** | Owners, trusted players, teams and the one access check. | stable |
| `OwnerRecord` | Immutable owner record with codecs. | stable |
| `Owned` | Something with an owner record. | stable |
| `OwnerSecurity` | The access check and team resolver registry. | stable |
| `OwnerSecurity.setOpsBypassSource` | Lib's own hook to its server config. | internal |
| `TeamResolver` | Team mod adapter (FTB Teams built in). | stable |

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
