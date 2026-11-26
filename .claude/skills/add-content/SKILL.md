---
name: add-content
description: How to add a block, item, cable or machine to MaxTech Storage with the content builder (lib.content), including datagen, config presets, tests and the checks a PR needs. Read before adding any registered content.
---

# Adding content to Storage

All registered content goes through the content builder in `lib.content`. One chain per entry registers it and declares its datagen: model, lang, loot, tags, recipes and creative tab. The datagen providers read those declarations; don't hand-write a provider entry for anything a chain can declare.

Storage has one `Content` instance, `dev.silencebound.maxtech.storage.registry.ModContent.CONTENT`. Datagen (`neoforge.datagen.StorageDataGenerators`) reads it, so a chain on it needs no provider wiring.

## Where it goes

- Each area has one `<Area>Content` class in its own package (`port.PortContent`, `cable.CableContent`, `terminal.TerminalContent`) holding its entries as `public static final` fields, whatever their kind. A class queues its entries when it loads, so the order in which `ModContent.init()` loads the area classes is the registration order and the creative-tab order.
- A new area gets its own `<Area>Content` and one `init()` line in `ModContent.init()`. Each class has an `init()` that does nothing but load the class. Never add an entry to another area's class.
- Shared infrastructure stays central in `registry`: `ModContent`, `ModCreativeTabs`, `ModDataComponents`, `ModMenus`.
- Entries read other areas' items through their `<Area>Content` fields and recipe inputs through `c:` tags (`MaterialTags`), never by naming another item as an input.

After any change: run `./gradlew :storage:neoforge:runData` (clear `storage/common/src/generated/resources/.cache` first), commit the generated files and go through `docs/IMPLEMENTER_CHECKLIST.md`.

## Item

```java
public static final RegistryEntry<Item, Item> SPEED_CARD = ModContent.CONTENT.item("speed_card", Item::new)
        .model(ItemModel.flat(MaxtechStorage.id("item/speed_card"))).lang("Speed Card")
        .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern(" O ")/* ... */
                .unlockedBy("has_oscillator", recipes.has(oscillator)).save(recipes.output()))
        .creativeTab().register();
```

- `item(name, factory)` starts the chain; models are data (`ItemModel.flat`, `tintedFlat`, `layered`), because a dedicated server has no client classes. Textures live in `storage/common/src/main/resources/assets/maxtech_storage/textures`.
- Balance numbers (capacity, rates, costs) come from config with preset defaults, never Java constants (see Config).

## Block

Same chain from `ModContent.CONTENT.block(name, factory)`: `.simpleItem()` or `.item(factory)` gives it a block item, then `.model(BlockModel...)`, `.loot(BlockLoot.SELF)` (or none for blocks that drop through code), `.tag(...)`, `.lang(...)`, `.recipe(...)`, `.creativeTab()`. Blocks with a block entity register the type through the area's content class; ticking block entities must sleep when idle and wake on events (CLAUDE.md, performance).

## Cable

Every cable is a block of its own (ADR-0012). One `ModContent.CONTENT.conduit(name, item -> type)` chain registers the conduit type, its block and its `ConduitItem`; see `cable.CableContent.register`:

- the factory receives the item as a lazy supplier, since conduit types register after items;
- `.model(...)` is required (the block is drawn from block entity data), `.look(ConduitLook...)` picks the render look, `.block(StorageCableBlock::new)` swaps in Storage's block class;
- every conduit block shares Lib's `ModBlockEntities.CONDUIT`; never register a block entity type or a block of your own for a cable;
- GameTest conduit types use `Content.create(modId).conduitWithoutItem(name, () -> type)`: type and block, no item and no datagen.

## Config and presets

- Server config for balance and behaviour lives in `storage.config.StorageServerConfig`, client config for visuals in `StorageClientConfig`, content switches (which gate recipes, loaded before server config) in `StorageCommonConfig` under `[modules]` via `StorageModules`.
- Every new value or section needs a comment that names its unit (it becomes the tooltip in NeoForge's config screen)
  and a short name in `storage.config.StorageConfigNames` (Lib's in `lib.config.LibConfigNames`);
  `StorageConfigNamesTest` (JUnit, normal build) and `runData` fail without the name, the test also on a name left
  behind for a removed value, and `config_lang_complete` fails until the lang is regenerated. Mark a value that only applies
  after a world reload or game restart with `worldRestart()`/`gameRestart()` on the builder. Top-level sections show
  in the screen in the order of `CONFIG_ORDER` in the mod's client entrypoint.
- New defaults go in both preset files, `storage/common/src/main/resources/maxtech_storage/presets/standalone.json` and `pack.json`, read through Lib's `PresetConfig` (ADR-0003). Preset files are append-only for parallel implementers.
- Add an option only when the PRD or a maintainer asks for it.

## Recipe types and compat

A new recipe type needs a JEI category (`compat.jei`) and a KubeJS schema (`compat.kubejs`, built on Lib's helpers); GameTests fail when one is missing. Other-mod code lives only under `compat.<modid>` behind a `ModList` guard.

## Content index

Datagen writes `storage/common/src/generated/resources/data/maxtech_storage/content_index.json` (`lib.content.ContentIndex`): items, blocks, fluids, conduit types, the creative tab in order, tags and recipe ids by type. After adding or removing content, regenerate and read the index diff; it should show exactly what you meant to add. Never assert a content count or a full id list in a test, because two PRs that each add content then fail together; test a rule instead.

## Tests

- **JUnit** (`storage/common/src/test`): pure logic (cell capacity accounting, channel counting, planner tree).
- **GameTests** (`storage/neoforge/src/gametest`, named `<Area>GameTests`): placing, network behaviour and the edges from the checklist (unloaded neighbours, full output, removal mid-run, both `unloadedChunkMode` values). Perf scenarios go in `<Area>PerfGameTests` and assert PRD §8.1 budgets. Config changes in a test go through the config test environments in `gametest`, which restore the values afterwards.
- **Every test must be able to fail**: break the code once, see the test fail, then restore it.
- **Datagen**: delete `.cache`, re-run `runData`, and check that `git status --porcelain -- storage/common/src/generated` shows only intended changes.
- Iterate with `./gradlew :storage:neoforge:runGameTestServer -Pgametest='maxtech_storage:your_prefix_*'`.
