# ADR-0015: Addon conduits through the api

Date: 2026-11-27
Status: Accepted. Supersedes ADR-0004.

## Context

ADR-0004 kept the network framework internal and made only the conduit types api. Lib 1.0 therefore did not support
conduit types from other mods: a type registered through the api jar had no block and joined no network.

MaxTech Farms (FM1) needs lab tubes: a conduit type of its own on a Farms network type that carries nutrient, MU and
output together. Farms builds on Lib's api jar only, like a third-party addon (`farms/CLAUDE.md`), so it cannot use
Lib's content builder (`lib.content`), its conduit block (`lib.conduit`) or its network engine (`lib.network`).

The api source set cannot reference NeoForge or see `main` (ADR-0002), so Lib's conduit block and block entity, which
use NeoForge model data and capabilities, cannot move into it.

## Options considered

1. **Keep ADR-0004.** No api risk, but Farms would need Lib internals or a cable block of its own.
2. **Network engine only.** Farms gets network types, but still writes its own conduit block, model, sync, dyes and
   facades, a second copy of Lib's conduit code.
3. **The full framework, within the api limit.** The network engine and the pure conduit helpers move into the api;
   the block and block entity stay Lib's behind api interfaces; an addon registers its conduits through a builder.

## Decision

Option 3 (owner, 2026-11-27).

- The network engine moves to `lib.api.network` as it is (`NetworkType`, `NetworkLogic`, `NodeConnectivity`,
  `Network`, `NetworkGraph`, `NetworkManager`, `RouteTree`), experimental, with no interfaces in front.
- The pure conduit helpers move to `lib.api.conduit` (`ConduitLook`, `ConduitShapes`, `ConduitFaceKey`,
  `ConduitConnections`, `ConduitFaceModes`, `ConduitColours`), experimental.
- `ConduitBlock` and `ConduitBlockEntity` stay Lib's classes. Addons see a conduit in the world as a `ConduitView`
  (read-only) or `ConduitHost` (plus server-side mutators), from `Conduits.host`.
- `Conduits.builder(modId, name, type)` registers the type, Lib's block and the item. Behaviour that Storage gets
  from subclasses comes from hooks on the builder instead (`onWrench` with a `ConduitWrenchHook`, `connectionRule` with
  a `ConduitConnectionRule`). Addons never subclass Lib's conduit block.
- `look()` on the builder sets how the conduit is drawn. Lib then supplies the block state file and item model
  definition at runtime from a built-in client pack below the mods' own resources; the addon writes no block state
  JSON for conduits.
- Facades and dye colours apply to addon conduits as to Storage's cables.
- A conduit whose mod ships no loot table drops its own item (and any facade); an addon's loot table still wins.
- Custom conduit models (client geometry plus a NeoForge-side api jar for model hooks) come in a later PR; until then
  every addon conduit is drawn by Lib's conduit model from its look.

## Consequences

- Addons on the api jar alone can add conduits with their own network types; Farms is the first.
- Most of the new api is experimental until Farms settles it, and may change in any Lib minor version.
  `ConduitType`, `ConduitTypes`, `ConduitFaceMode`, `ConduitState` and `UnloadedChunkMode` stay stable.
- Lib's conduit block now carries the hooks, so a change to Storage's cables can affect addon conduits; the Lib
  GameTests `conduit_api_*` use the api only and catch a missing piece at compile time.
- Lib owns the block state format of conduits. `ConduitLook.CODEC` is that format, pinned by JUnit tests.
- Storage still uses Lib internals (`lib.content`, `lib.conduit`) and keeps pinning one Lib minor.
