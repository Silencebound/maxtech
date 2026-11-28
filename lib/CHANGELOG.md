# MaxTech Lib changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow semver (PRD §5.8).
Entries before 2026-10-06 are not recorded; 0.2.0 is the first version in this repository's history.
Api changes are marked **Api**.

## [Unreleased]

### Added
- `/maxtech_lib profile [seconds]` and `/maxtech_lib profile stop` (permission level 2): samples Lib's level schedulers and networks for 10 s by default (up to 300) and prints scheduler and network time per tick, scheduler time by mod, the ten most expensive task classes and network types, the largest networks and the total task runs. Off, it costs one static read per task run. Internal hooks for it: `TaskScheduler.setRunListener`, `NetworkType.types()`, `NetworkManager.existingGraph`, `NetworkGraph.isProfiling()`/`acquireProfiling()`/`releaseProfiling()`, `LevelTasks.acquireProfiling()` (all `@ApiStatus.Internal`).
- **Api** Side config for transfer ports (`lib.api.menu`, experimental, ADR-0016 and ADR-0018 amendments): `MenuDefinition.Builder.port(getter, kinds...)` returns a `PortValue` (face modes and allowed modes per kind, the host's facing and block state), `Builder.sideConfig(port, kinds...)` adds a standard side tab and `Layout.faces(port, kinds...)` the same node in a panel: a rotatable block preview and one block net of face buttons per kind, named relative to the facing (`RelativeFace` in `lib.api.transfer`), push orange, pull blue. The server applies a clicked mode only when the port allows it for the kind; `MenuTesting.Session.pressFace` clicks one in GameTests. `Layout.Faces` is a new layout record, so exhaustive switches over `Layout` need a case for it. The toolkit demo machine faces the player and has a port with the tab.
- **Api** Side config for transfer ports (`lib.api.menu`, experimental, ADR-0016 and ADR-0018 amendments): `MenuDefinition.Builder.port(getter, kinds...)` returns a `PortValue` (face modes and allowed modes per kind, and the host's facing), `Builder.sideConfig(port, kinds...)` adds a standard side tab and `Layout.faces(port, kinds...)` the same node in a panel: one block net of face buttons per kind, named relative to the facing (`RelativeFace` in `lib.api.transfer`), push orange, pull blue. The server applies a clicked mode only when the port allows it for the kind; `MenuTesting.Session.pressFace` clicks one in GameTests. `Layout.Faces` is a new layout record, so exhaustive switches over `Layout` need a case for it. The toolkit demo machine faces the player and has a port with the tab.
- **Api** Machine recipes (`lib.api.recipe`, experimental, ADR-0019): `MachineRecipeType.define(modId, name, shape)` registers a machine's recipe type, serializer and recipe book category through the mod's registrars; `MachineRecipe` holds item inputs (`ItemInput`, `c:` tags, counts, catalysts), tank inputs of any resource kind (`KindInput`, exact resource or kind plus tag, with an amount), item outputs with a chance (`ItemOutput.roll`), tank outputs (`KindOutput`), `energy_per_tick`, `duration` and an optional `mode`. The JSON codec checks each recipe against the type's `MachineRecipeShape` (slots and tanks per side, modes); a recipe that does not fit or names an unknown item, fluid or kind is logged and skipped. `MachineRecipeLookup` finds a machine's recipe and remembers the last one; `MachineRecipeBuilder` writes recipes in datagen. `KindTags` (`lib.api.resource`) holds each kind's tag test, which port filters and recipe inputs share. See `docs/api/lib-1.0.md`.
- **Api** Machine recipes (`lib.api.recipe`, experimental, ADR-0019): `MachineRecipeType.define(modId, name, shape)` registers a machine's recipe type, serializer and recipe book category through the mod's registrars; `MachineRecipe` holds item inputs (`ItemInput`, `c:` tags, counts, catalysts), tank inputs of any resource kind (`KindInput`, exact resource or kind plus tag, with an amount), item outputs with a chance (`ItemOutput.roll`), tank outputs (`KindOutput`), `energy_per_tick`, `duration` and an optional `mode`. The JSON codec checks each recipe against the type's `MachineRecipeShape` (slots and tanks per side, modes); a recipe that does not fit or names an unknown item, fluid or kind is logged and skipped. `KindTags` (`lib.api.resource`) holds each kind's tag test, which port filters and recipe inputs share. See `docs/api/lib-1.0.md`.
- **Api** Guides for every MaxTech mod (experimental): `Guides.register(modId)` (`lib.neoforge.api.guide`) declares
  the mod's GuideME guide `<modid>:guide` with Lib's `<Config>` tag and the website export's rewrite, and an item
  implementing `GuideBook` (`lib.api.guide`) opens its guide when used. Neither names a GuideME type. GuideME
  (26.1.15-beta, LGPLv3/MIT/Apache 2.0) is now a required dependency bundled in Lib's jar (jar-in-jar); it moved here
  from Storage (ADR-0014 amendment). `<Config>` reads any mod's `GuideConfigValues` (a key without a namespace names
  the page's mod). Mods with guide pages get hot reload in `runClient` and `runExportGuide`, `createGuideWebsite` and
  `serveGuideWebsite` from the build.
- **Api** Menu server actions (`lib.api.menu`, experimental, ADR-0016 amendment): `MenuDefinition.Builder.cycle(enumValue, setter)` returns a `CycleAction` over an enum that implements the new `MenuChoice`, and `Layout.cycleButton(action, key, width)` shows it as an Accent button that asks the server for the next (left click) or previous (right click) choice, with a tooltip and narration. The server takes only constants of the enum while the menu is open; `MenuTesting.Session.pressCycleButton` clicks one in GameTests. `Layout.CycleButton` is a new layout record, so exhaustive switches over `Layout` need a case for it.
- **Api** `RedstoneMode` (always, high, low, pulse, never) and `RedstoneControl` (`lib.api.machine`, experimental, ADR-0019): a machine's redstone mode, read on neighbour updates without ticking, with `allowsRun()`, `consumePulse()` (one run per rising edge, up to 8 waiting), a wake callback and saves that keep the mode, the reading and the waiting pulses. Lib ships the modes' English names and hints. The toolkit demo machine has a redstone button.
- **Api** `Handlers.machineItems`, `machineTank`, `insertAll`, `extractAll` and `Handlers.MachineTank` (`lib.neoforge.api.inventory`, experimental): a machine's own transactional access to its `ItemSlots` and `ResourceTank`s, so one step (inputs drained, results put out) commits or rolls back whole. `machineItems(slots)` and `machineTank(tank)` ignore slot roles but keep filters, limits and capacity; `insertAll`/`extractAll` move a list of stacks in a nested transaction and refuse a partial fit; change listeners run on the root commit only, once per touched slot or tank, and unknown items, unknown tank contents and overflow are never touched. See `docs/api/lib-1.0.md`.
- **Api** `ConduitLook.Shell.withFrameBars()` (block state field `frame_bars`, experimental): a shell's borders become solid bars on every edge of the tube's outer box, all twelve of a bend's or branch's core included, with the glass set in one bar width between them. Flat border lines seen through clear glass looked like they floated; bars on the far side read as the back of the frame. `Shell` gained a seventh record component, `frameBars`; the four- and six-argument constructors stay.
- **Api** `ConduitLook.Shell.withBarTexture(Identifier)` (block state field `bar_texture`, experimental): a shell's border lines, rims, bars and collars show a sprite, projected onto the block and turned to run along each bar, tinted with the border colour, instead of a flat colour. `Shell` gained an eighth record component, `barTexture` (an `Optional`, empty: flat; `withoutBarTexture()` goes back to flat); the seven-argument constructor stays.
- **Api** `ConduitLook.Shell.withTierStripe(int)` (block state field `tier_stripe`, `#rrggbb`, experimental): with frame bars, a flat 1 px stripe in that colour on the glass beside every bar and collar, so tiers of one pipe look different without a new shape. `Shell` gained a ninth record component, `tierStripe` (an `Optional<Integer>`, empty: none); the eight-argument constructor stays. Shells without it draw exactly as before.
- **Api** `ConduitParts.itemPiece(Shell, ConduitType)` and `ConduitItemModels.shell(ItemModelGenerators, ConduitEntry, Shell)` (NeoForge api, datagen, experimental): a shell conduit's item model as a straight east-west piece of the tube, built from the block model's glass, frame bars and tier stripes with both ends closed like an open end, instead of a flat item that looked like a glass pane.
- Dev only: `/maxtech_lib_demo pipes [clear]` (GameTest source set) builds a labelled showcase of shell pipe looks next to the player: the picked look (smoky glass, plain 1 px bars, tier stripe) in tiers 1 to 4, bar colours, glass tints, bar thickness, bar patterns and tier markings.
- **Api** `Layout.value(value, key, width, shownWhenNonZero)` (`lib.api.menu`, experimental): a value line that is drawn and narrated only while a synced `IntValue` is not 0; its box stays in place when hidden. See `docs/api/gui-toolkit.md`.
- **Api** `MenuTesting` (`lib.api.menu`, experimental, GameTests only): opens a `MenuDefinition` for a mock server player with its values payloads captured, reads the server's and the client's `MenuValueView`, presses a tank's bucket button and shift-clicks slots, so addons can test their menus; `toolkit_api_*` GameTests use it. See `docs/api/gui-toolkit.md`.
- Docs: `docs/api/gui-toolkit.md` shows how to build a screen with the toolkit; a dev-only demo machine (`/maxtech_lib_demo toolkit`, GameTest source set) and `toolkit_api_*` GameTests use it through the api.

### Changed
- The Accent style's background is a bit darker (owner request): `Accent.GRAPHITE` 0xFF25292D to 0xFF1C2023, `BEVEL` 0xFF343A3F to 0xFF2C3135 and `SLOT` 0xFF15181B to 0xFF101215, so slots stay darker than the panel and the bevel still reads; TEXT and LABEL on the panel stay above 4.5:1.
- **Api** `Layout.Value`'s canonical constructor gained a fourth component, `@Nullable IntValue shownWhenNonZero`
  (null: always shown); code that built `new Layout.Value(value, key, width)` directly passes `null` or uses
  `Layout.value(...)`, and record patterns on `Layout.Value` take four components.
- **Api** The Accent widget set is api (`lib.api.client.gui`, client only, experimental, ADR-0016): `Accent`,
  `AccentButton`, `IconButton`, `Icon`, `AccentPanel`, `AccentInset`, `AccentRow`, `AccentReadout`, `AccentTag`,
  `AccentStatus`, `AccentTabRow`, `AccentToast`, `ScrollBar`, `TextScale`, `TextFit` and `LayoutCheck` moved from
  `lib.client.gui`, `lib.client.gui.accent` and `lib.text` with the same names and drawing, so addons on the api jar
  draw the same look; `TextScale` gained an internal setter for the text size option, and `TextFit`, which is pure
  Java, lives in `lib.api.text`. Side tabs, `ScreenComponents` and the editor pieces stay internal.
- **Api** `ConduitContents` holds a resource of any kind (`Optional<KindKey<?>> resource`) instead of a fluid; build
  fluid contents with `ConduitContents.ofFluid`, read them with `fluid()`. Fluid contents save as before; contents of
  other kinds save their kind and key, are not drawn yet, and load empty when their kind's mod is gone.
  `KindKey.STREAM_CODEC` is now `CLIENTBOUND_STREAM_CODEC`.
- **Api** Tank menu values and `AccentTank` take any resource kind: `MenuValues.Builder.kindTank` (a `KindKey` of any
  kind) and `tank(kind, ...)` (one kind, by getters or over a Lib `ResourceTank`) next to the fluid `tank`;
  `MenuValueView.resource` reads any tank, `fluid` only fluid tanks. Unknown contents arrive as `UnknownResource` and
  the gauge shows them as "Unknown" with the saved amount. `AccentTank`'s new constructor takes kind keys and draws
  with the kind's `ResourceLook` (only the well for a kind without one); amounts show in the kind's unit.
  `MenuValueView.kind(TankValue)` gives the declared kind (null for any kind) so a screen can pick the empty unit;
  `ResourceTank.unknownAmount()` (default 0) lets the menu show the saved amount of any tank's unknown contents.
- **Api** Conduit contents of any resource kind are drawn, with the kind's client look: `ResourceLook` and
  `ResourceLooks` (`lib.api.client.resource`, client only, experimental) give a kind its sprites, tint, name and
  light; `FluidLook` is now the fluid kind's look (registered by Lib, `FluidLook.install` is gone). Packed contents
  hold a session look index instead of the fluid's registry id: `ConduitContents.packed()` and `packedFluidId` are
  replaced by Lib's own packing and `packedLookIndex`.

### Fixed
- A client with Lib but no mod that adds conduit blocks (Lib alone, or Lib with Farms) crashed at startup: the
  facade tint extension was registered with no blocks, which NeoForge refuses.

### Added
- Transfer ports move items: `TransferPorts.Builder.build()` makes a port over `ItemSlots` buffers that pushes into
  and pulls from the blocks beside it, sleeping until something changes; ports with fluid, energy or other tank
  buffers still throw `UnsupportedOperationException`. A port always saves its settings, defaults included.
- **Api** Transfer ports move fluids and tanks of any kind with a `KindBridge`: `tank(ResourceTank, modes)` and
  `fluids(FluidTank, modes)` no longer throw, at up to `transfer.fluidPerOp` per operation; a handler without
  transactions is touched last and only what it really moved counts. A port's block must expose the kind's
  capability on its pulling faces for neighbouring ports to push into it.
- **Api** Transfer ports move energy: `energy(EnergyEndpoint, modes, onChange)` (a standalone buffer endpoint) no
  longer throws and pushes FE into and pulls FE from the FE handlers beside it at the configured FE rate, up to
  `transfer.energyPerOp` MU per operation. The fraction of one MU a conversion leaves is kept for the next operation
  and saved with the port; less than 1 MU is dropped when the FE rate changes or the port is removed. `energy`
  gained the `onChange` listener (mark the block entity changed there), since the port changes the endpoint's
  stored energy directly.
- **Api** `TransferPorts.builder()` takes no `TaskGroup`: a port runs in Lib's transfer group of the level it starts
  in, or in the owner's group given by `Builder.tasks(level -> group)`.
- **Api** Declared toolkit menus (`lib.api.menu`, experimental, ADR-0016): `Menus.define` builds a `MenuDefinition`
  from a host's slot groups, fluid tanks, energy, progress and other values, a shift-click order, an `AccentTheme`
  and a `Layout` of rows and columns in GUI pixels; `register` adds its menu type, `open` opens it for a player. The
  layout is checked when defined (panel and title tab fit 320x240, nothing overlaps, every slot group placed once;
  dev runs throw), and text never moves slots. `ItemSlots.Builder.size()` is new.
- Lib binds a screen to every declared toolkit menu: the Accent panel in the definition's theme, slot frames, tank,
  energy and progress bars with their tooltips (tanks of any kind, unknown contents as "Unknown"), labels, values and
  status lines fitted into their boxes (drawn smaller at a large text size, never moving anything) and narrated, and
  bucket clicks on fluid tanks. Addons write no client code for it. **Api** `AccentToast.theme` draws a toast's title and news strip in a mod's theme.
  from a host's slot groups, tanks of any kind (fluid tanks take bucket clicks; unknown contents show as "Unknown"
  with their amount), energy, progress and other values, a shift-click order, an `AccentTheme` and a `Layout` of rows
  and columns in GUI pixels, with `Layout.value` for a live number in a text; `register` adds its menu type, `open`
  opens it for a player. The layout is checked when defined, or when registered for a definition built before Lib
  (panel and title tab fit 320x240, every slot group placed once and sharing no slot, status lines over `MenuStatus`
  enums; dev runs throw), gaps are never negative, and text never moves slots. `ItemSlots.Builder.size()` and
  `copy()` are new.
- **Api** Transfer port types (`lib.api.transfer` and `lib.neoforge.api.transfer`, experimental, ADR-0018): face
  modes (off, push, pull) packed per resource kind, allow and deny filters of keys and tags per port and kind, port
  settings saved by kind id (settings of a removed mod's kind are kept), the `TransferPort` handle and its builder
  (several buffers per kind, each with the modes it takes part in), the rule that between two active port faces
  only a pushing face facing a pulling face moves (the pusher does it), and `KindBridge`s from a kind to its
  capability (items and fluids built in). Ports do not move anything yet: `TransferPorts.Builder.build()` throws. New
  config `transfer.*` in both presets.
- **Api** Inventories (`lib.api.inventory`, experimental, ADR-0016): `ItemSlots` with per-slot roles (input, output,
  display), filters and limits, and `ResourceTank`/`FluidTank`, both journaled so transfers through their handlers
  roll back with the transaction; `lib.neoforge.api.inventory.Handlers` gives the NeoForge handlers for capabilities.
  Nothing saved is lost: stacks and tank contents of a removed mod are kept as unknown and saved back unchanged,
  stacks for slots a machine no longer has are kept as overflow, and a tank keeps a saved amount over its capacity.
  An item or fluid with a removed mod's component counts as unknown too, instead of loading without it.
  `ItemSlots.dropContents` drops the slots and overflow when a block breaks (unknown contents vanish with it).
- **Api** `Conduits.Builder.attachesToBlocks()` (experimental, for Farms FM1): the conduit's network logic gets
  `onNodeInvalidated` once whenever a block entity beside it appears, goes or is replaced, also when it arrives or
  leaves with its chunk, so addon conduits no longer need their attachable blocks to poke them. Other conduits are unchanged.
- **Api** Resource kinds (`lib.api.resource`, experimental, ADR-0017): `ResourceKind`, the `ResourceKinds` registry
  with built-in item, fluid and energy kinds, `KindKey` with save and network codecs (one for keys clients send), and
  `UnknownResource`, which keeps the saved contents of a kind whose mod was removed. Nothing uses them yet.
- **Api** Menu values (`lib.api.menu`, experimental, ADR-0016): a menu declares int, long, enum, energy, progress and
  tank values with server-side getters; Lib's toolkit menu sends the changed ones in one payload a tick at most, with
  longs and fluid components whole. `FluidKey.OPTIONAL_STREAM_CODEC`.
- **Api** Themes and bars for the Accent look (`lib.api.client.gui`, experimental): `AccentTheme` (in `lib.api.style`;
  Lib's cyan by default, so existing screens look the same; every Accent widget and drawing method takes one),
  `AccentTank` (the fluid's own sprite and tint through `FluidLook`), `AccentEnergyBar` (MU, with FE at the configured
  rate in the tooltip) and `AccentProgress` (arrow or flame) on the shared `AccentBar` type, with their numbers in the
  tooltip (hovered or keyboard-focused) and narration. The accent demo screen shows them.
- The shell look draws a conduit's contents inside the glass: the fluid's own textures, tint and light, a fill level
  on horizontal pieces and a full column on vertical ones, the flowing texture along the flow from the upstream side
  and a second lane flowing back. **Api** `ConduitLook.Shell` gains optional `inset` and `lane_width` fields;
  `ConduitQuad` gains `lightEmission` (existing constructors kept).
- **Api** Conduit contents (`lib.api.conduit.ConduitContents`, experimental, for Farms FM1): a shared meaning for
  what a conduit shows inside it (fluid, fill step 0..4, upstream side, second lane), set with
  `ConduitHost.setContents` on the server and read with `ConduitView.contents()`. Saved and synced as `contents` only
  when not empty and only on change, the fluid by registry name; a fluid no longer registered loads as empty.
  `ConduitBodyKey.contents()` hands the client model the contents packed into an int (layout in
  `ConduitContents.pack`).
- **Api** `ConduitLook.Shell` (`"look": "shell"`, experimental): a translucent glass conduit whose border lines and rims
  Lib works out from the connections, with the border colour, border and rim widths as optional fields
  (`docs/api/conduit-addon.md`).
- **Api** `ConduitQuad` gains an optional `ConduitUv` (`lib.api.conduit.model`, experimental): the part of its sprite
  a quad shows and its quarter turns, baked like a block model face's `uv` and `rotation`; without one a quad shows its
  whole sprite as before.
- **Api** Custom conduit models (experimental, for Farms FM1): `Conduits.Builder.customModel(translucent)` marks a
  conduit whose client model comes from an addon's `ConduitModelHook`, registered in the client mod-bus event
  `RegisterConduitModelsEvent` (both in the NeoForge-side api jar, `lib.neoforge.api.client.conduit`). The hook names
  its sprites and supplies body and face quads per key (Lib's own by default); Lib caches the baked parts and still
  draws facades. A marked conduit without a hook, or a hook for an unmarked one, stops the client at startup.
  `ConduitQuad` gains a `sprite` (one of the hook's) and `ConduitQuad.ofSprite`.
- **Api** Addon conduits (`lib.api.conduit`, experimental, for Farms FM1): `Conduits.builder(modId, name, type)`
  registers a conduit type with Lib's conduit block and item (the shared `maxtech_lib:conduit` block entity, dyes and
  facades included), with a look, a creative tab, a wrench hook (`ConduitWrenchHook`) and a connection rule
  (`ConduitConnectionRule`) instead of a block subclass. `Conduits.host` gives the conduit in the world as a
  `ConduitHost` (a read-only `ConduitView` plus face, block-face and sync mutators); `blockOf`, `connectivity`,
  `isChunkPresent`, `place` and `useWrench` cover network logic, tests and an addon's own wrench. With a look, Lib
  supplies the conduit's block state file and item model definition from a built-in client pack (below the mods'
  own resources); a conduit block whose mod ships no loot table drops its own item.
- **Api** The pure conduit helpers are api (`lib.api.conduit`, experimental, for Farms FM1): `ConduitLook`,
  `ConduitShapes`, `ConduitConnections`, `ConduitFaceKey`, `ConduitColours` and `ConduitFaceModes` moved there from
  `lib.conduit` unchanged; `ConduitFaceModes.validateRegistered` stays Lib's (`@ApiStatus.Internal`).
- **Api** The network engine is api (`lib.api.network`, experimental, for Farms FM1): `Network`, `NetworkGraph`,
  `NetworkLogic`, `NetworkManager`, `NetworkType`, `NodeConnectivity` and `RouteTree` moved there from `lib.network`.
  `NetworkType.byId` returns an `Optional` (allocation-free) instead of null. Ticking, clearing and profiling stay Lib's
  (`@ApiStatus.Internal`); `lib.network.NetworkBootstrap` wires the config and the server lifecycle.
- **Api** `EnergyNetworks.feRate()`: the server's configured FE rate, for an addon's own FE edges (Farms FM1's
  sampler shows its MU to other mods' chargers as FE).
- **Api** Registration is api (`lib.api.registry`, experimental, for Farms FM1): `Registrars.create(modId, registry)`
  gives a `Registrar` whose entries are `RegistryEntry`s. Lib's internal `RegistryHelper.Registrar` and
  `RegistryEntry` now extend them, so existing callers are unchanged.
- **Api** Config files with presets are api (`lib.api.config`, experimental, for Farms FM1): `ConfigBuilder`,
  `ConfigFile` and `ConfigType` moved there from `lib.platform`; `PresetConfig` is an interface, made with
  `Configs.presetConfig(modId, anchor)` (Lib's class is now the internal `LibPresetConfig`); `Configs.builder` and
  `Configs.register` add plain client or common files.
- **Api** The task scheduler is api (`lib.api.schedule`, experimental, for Farms FM1): `ScheduledTask`, `TaskGroup`,
  `TaskPriority` and `TaskScheduler` moved there from `lib.schedule`, and `TaskSchedulers.newGroup(level)` gives an
  addon a group with Lib's configured budgets. `LevelTasks` stays internal and backs it.
- NeoForge's config screen (Mods, Config) for Lib's client and server files: every option and section has a name and
  a tooltip with its unit, range, default and, for `workers.threads`, that it applies after a world reload. Sections
  come in order of how often they are changed. `ConfigScreens` registers the same screen for an addon.
- `MenuHelper.craftingRemainders(player, recipe, input)` (Platform, not api): a crafting recipe's remainders worked
  out with the crafting player set, as vanilla's result slot does (NeoForge's `CommonHooks.setCraftingPlayer`).
- `ConfigBuilder.worldRestart()` and `gameRestart()` mark a value the game reads only at world load or startup.
  `ConfigHelper.builder(modId)` replaces `builder()`: values and sections get the translation key
  `<modid>.configuration.<path>`, and datagen writes their tooltips from the comments.
- `SyncedListIndex`: `$text` search words match a tooltip text from the new default method
  `EntryDescriber.tooltip`, built lazily with a per-rebuild budget; `setFilter` limits the view by a predicate.
- The Accent widget set for every MaxTech screen (`lib.client.gui.accent`, client only, not api): the Accent Frame
  look moved here from Storage, a text button (16 high, compact 12), icon buttons (plain, toggle, cycle), side tabs with
  flyouts, changed dots and badges, one scroll bar, an in-screen toast and a notice hook for outside screens. Dev runs
  show them with `/maxtech_lib accent_demo`.
- Accent pieces for screens that lay out their own lists and tables (`AccentPanel`, `AccentInset`, `AccentRow`,
  `AccentTabRow`, `AccentReadout`, `AccentTag`, `AccentStatus`), and `ScrollBar.draw` for a bar without a widget;
  `SidePanel` draws its scroll bar with it.
- **Api** Additive: `BlockInfoSink` gets structured entries as default methods that fall back to `line`: `status`
  (with a `Tone` and an optional sprite), `progress` (optionally with a sprite), `pips`, `stacks` (new record
  `InfoStack`) and `icon`. `BlockInfoRequest.Detail.BRIEF` is now a few short entries; the details go to `FULL`.
- Jade's compact view for every block with a `BlockInfoProvider`: a status dot next to the name, then one row of bars
  coloured by fill, item and fluid icons and mode icons; sneaking shows the details as text. Client options
  `overlay.detailLevel` (`compact` or `full`) and `overlay.sneakForDetails` (on).
- **Api** Conduit model geometry (`lib.api.conduit.model`, experimental, for Farms FM1): `ConduitQuad`,
  `ConduitTexture` and `ConduitBodyKey` move from `lib.client.conduit`, a quad gets a `ConduitLayer` (from its sprite
  as before, or forced translucent or cutout), and `ConduitParts` hands out Lib's own body and face quads.
- **Api** Conduit render value (experimental, for Farms FM1): `ConduitHost.setRenderValue(int)` sets an addon-defined
  value that `ConduitView.renderValue()` reads on both sides and `ConduitBodyKey.renderValue()` hands to the client
  model. Saved and synced as `render_value` only when non-zero and only on change; Lib's looks ignore it.

### Changed
- Config lang keys use the full path (`maxtech_lib.configuration.overlay.detailLevel`); the old last-segment keys are
  gone.
- The accessibility option is now called "Cable colour labels"; the word "conduit" no longer appears in player-facing text.
- Energy blocks show one entry in Jade's compact view (a stored-energy bar, or what they produce or use); the
  network's lines moved to the details.
- The task scheduler no longer allocates on ticks where a group's queue fills and drains, and a key registry interns
  with one hash probe and shrinks once after a large release instead of at every halving.

### Removed
- The device-casing GUI pieces (`lib.client.gui.casing`, client only, not api) and `/maxtech_lib gui_demo`: nothing
  uses them since the logic screens moved to the Accent look.

## [0.14.0] - unreleased (beta)

Ships with MaxTech Storage 0.9.0 (public beta). Report bugs on the issue tracker.

### Removed
- **Api** Breaking, Lib 0.13.0: Lib's guide is retired (MGM item 3); mods draw their guides with GuideME. Gone:
  `lib.guide` and `lib.client.guide` (guide screen, page parser, search, scenes, hold key, book item, live values,
  recipe sync, hot reload and the dev reload command), the Lib test guide, and `Guides`, `GuideElements`,
  `GuideColumns`, `GuideLiveValues` and `GuideScreens` from `lib.api.guide`. `GuideConfigValues` stays. The client
  config key `guide.hotReload` is removed.

### Added
- **Api** Experimental, Lib 0.12.0: energy diagnostics (MLE item 3). `EnergyNetwork.readout()` returns an
  `EnergyReadout` (supply, demand, delivered, brownout fraction, stored and capacity, time to full or empty, last pass
  cost) from fields the pass fills, without waking the network. Blocks show their endpoint through the new
  `EnergyNetworks.ENDPOINT_CAPABILITY` block capability; Jade and the scanner then add MU lines for them
  ("64 MU/t").
- **Api** Experimental, Lib 0.11.0: FE edges for MaxTech energy (MLE item 2). `EnergyEdge` puts another mod's block into
  an energy network as a producer and a consumer; Lib's `FeEdge` reaches it through a cached FE capability lookup and
  moves FE in transactions, counting only what moved. `FeEndpointHandler` exposes a MaxTech buffer as an FE handler.
  Conversion keeps exact units of the FE rate, so no round trip creates energy at any rate. New server config keys
  `energy.exportEnabled`, `energy.exportPerEdge`, `energy.exportPerNetwork` (0 = no limit) and
  `energy.edgePollTicks`; energy coming in is never limited. New: `FeRate.unitsOfFe`/`unitsOfMu`/`wholeFe`/`wholeMu`/
  `muCovering`, `EnergyEndpoint.exportLeft`/`countExport`, `EnergyNetwork.edgeExport`/`edgeCount`,
  `EnergyScheduler.edgeCount`/`activeEdgeCount`.
- **Api** Experimental, Lib 0.10.0: MaxTech energy core (`lib.api.energy`, MLE item 1): MaxTech Units (MU) with an
  FE rate in the server config (`energy.feRateFe`, `energy.feRateMu`, default 1:1, rounding never creates energy),
  producer, consumer and buffer endpoints, and energy networks with one pass per tick by priority, linear brownout,
  leftovers-only buffers, and sleep until something changes. Nothing uses it yet; Storage stays on FE.

### Changed
- `PresetConfig.presetProblems(Predicate)` also reports keys that must be in every built-in preset; Lib's `energy.*`
  keys are checked that way. Lib's energy system and its config keys are documented in `docs/context/energy.md`.
- **Api** Breaking, Lib 0.9.0: `ConduitType.createState()` and `CarriedItems.locate` / `CarriedItemSource.locate`
  return `Optional`; `ConduitType.placeholderColour()` is renamed `baseColour()`; `lib.api.guide` and
  `lib.api.carried` are experimental at package level and stay so after 1.0. Conduit types from other mods are not
  supported in 1.0.
- **Api** Javadoc on every public api type and member, checked by the build (`checkApiJavadoc` and doclint);
  `OwnerSecurity.setOpsBypassSource` is marked `@ApiStatus.Internal`. No signature changed. Inventory and open
  questions for 1.0 in `docs/api/lib-1.0.md`.

## [0.8.0] - 2026-10-15

### Added
- Guide scenes and step-by-step boxes for guide pages.
- Larger text option for MaxTech screens.
- Client option `accessibility.conduitColorLabels`: conduits show their dye letter and mode arrows or a diamond, and
  Jade names the colour and modes.
- Save format versions with migration steps and a versioned block entity base (`lib.save`), for save compatibility
  after 1.0.

## [0.7.0] - 2026-10-09

### Added
- Guide screens with markers and live network values.

## [0.6.0] - 2026-10-09

### Added
- Guide page elements: recipe layouts, `RecipeFor` and the guide layout tags.
- Guide search and a guided-progress start page.
- Guide pages open from items; guides hot reload.

### Fixed
- Guide recipe boxes are sized to their content.

## [0.5.0] - 2026-10-09

### Added
- Guide core: markdown pages and a device-book screen.

## [0.4.0] - 2026-10-08

### Added
- **Api:** carried gadgets are found through Lib, including in Curios slots.
- Device casing GUI pieces and a dev demo screen.
- Canvas widgets used by the logic node editor.

### Changed
- Tasks that idle together spread their backoff wake-ups.

## [0.3.0] - 2026-10-06

### Removed
- Leftover code from a shelved mod suite.

## [0.2.0] - 2026-10-06

Baseline: Lib imported into this repository (conduit networks, scheduler, content builder, widget toolkit, config
and presets).
