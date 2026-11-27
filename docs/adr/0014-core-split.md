# ADR-0014: Split core into a code library and a content mod

Date: 2026-10-03
Status: Accepted. Restored on 2026-11-27 from the history before the public export, rewritten in today's names; the
parts about the shelved mods are left out.

## Context

MaxTech began as a larger mod suite whose core mod was both the shared code every addon builds on (network graph,
conduit framework, energy, GUI components, config) and a content mod (materials, ores, worldgen, tools). The owner
wanted Storage to be a standalone mod: installed on its own, it must not add ores to the world or hundreds of material
items to the recipe viewer. With that core, every addon brought all of its content along.

Few code packages of the old core imported its content packages, so the two halves could be separated.

## Options considered

1. **Library mode in one core.** The core registers its content only when a content-using addon is loaded. One PR,
   but every recipe, tag and worldgen file needs a load condition, and the content code still ships in the jar.
2. **Split core into two mods.** A library with code and frameworks only, plus a content mod. More work up front, but
   each jar holds exactly what it says, and data needs no conditions.
3. **Keep core as it is.** Storage brings all the content with it, which contradicts the goal.

## Decision

Option 2.

- **MaxTech Lib** (`maxtech_lib`, package `dev.silencebound.maxtech.lib`, api under `...lib.api`) holds the shared
  code: the `Platform` seam and its NeoForge implementation, config and presets, the content builder and registration
  helpers, the network graph and conduit framework, FE helpers, schedulers, sync, GUI components and datagen helpers.
  It registers framework objects (registries, block-entity types, menus, data components) and no player-facing items
  or worldgen.
- **Content lives in the mods above Lib.** Storage and Farms register their own blocks, items and data.
- **Dependencies.** Lib depends on no MaxTech mod. Storage depends on Lib only (it may use Lib internals and pins one
  Lib minor, see `lib/CLAUDE.md`); Farms uses Lib's api jar only (ADR-0015).
- **Packages and api.** A Java package lives in exactly one jar: FML loads each mod jar as a Java module, and the
  module system rejects a package present in two modules of one layer. Lib publishes its own `-api` jar from
  `lib/common/src/api`.
- **Lib code never imports a mod above it.** Where Lib needs something a mod provides, the mod fills a Lib hook
  (for example `content.ContentExtension`).
- Lib's own assets (GUI lang keys, conduit models and their shared textures) use the `maxtech_lib` namespace.

## Consequences

- Storage ships standalone and runs on FE (and later Lib's energy system) without any content mod.
- Shared systems go in Lib; content goes in the mod that uses it.- A change to Lib's internals reaches every mod built on it; changes to `lib/common/src/api` are breaking-change
  candidates (`lib_version`, Storage's `lib_version_range`).

## Amendment: the facade item moves to Lib (owner decision 2026-10-04)

Storage runs without a content mod and has conduits (its cables), so a Storage-only game needs facades. Lib registers
the one facade item, `maxtech_lib:facade`, and its recipe serializer (`lib.facade.FacadeContent`). This is the single
exception to "Lib registers no player-facing items"; anything else player-facing goes in a mod above Lib.

- Recipes are Lib data: a mimicable block plus `c:plates/iron` gives four facades, and while that tag is empty (no mod
  adds iron plates) an iron ingot binds instead. Two recipes with opposite `neoforge:tag_empty` conditions, so exactly
  one loads.

## Amendment: the rest of the suite is shelved, Lib shrinks (2026-10-05 and 2026-10-06)

Development moved to Lib and Storage alone, and the other mods of the suite were shelved with their ADRs (the numbers
missing from `docs/adr`). Lib dropped everything Storage does not reach, among it the pipe framework (pipe types,
travel, face screens, filters, routing and redstone modes), the EU energy network and charged items, upgrade cards,
the machine and multiblock frameworks and Lib's own recipe-viewer plugin. FE helpers, the conduit framework and its
api, the network graph, config and presets, the content builder, schedulers, sync and the facade stayed.

A day later the owner chose a clean slate: the remaining pipe leftovers (`api.pipe` with `PipeSize`, the pipe and
glass conduit looks, face channels, extractor attachments and the unused conduit-kind hooks) were removed too.
`lib_version` went to 0.2.0 and then 0.3.0, breaking api changes before 1.0.
