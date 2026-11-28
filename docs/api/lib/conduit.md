# `lib.api.conduit`

Part of the [Lib api inventory](../lib-1.0.md); labels as defined there.

| Package / type | Purpose | Label |
|---|---|---|
| **`lib.api.conduit`** | Conduit types (a conduit is Lib's generic framework for blocks that connect to neighbours: cables now, later pipes and wires); each type gets its own block from Lib. Addons register their own through `Conduits` (ADR-0015, see below). | stable |
| `ConduitType` | One conduit type: network type, tier, base colour, thickness, face modes, per-conduit state. | stable |
| `ConduitTypes` | The `maxtech_lib:conduit_type` registry key and lookups. | stable |
| `ConduitFaceMode` | How a conduit face meets a block: both, insert, extract, disabled; stable ids. | stable |
| `ConduitState` | Server-side state a conduit saves in its block entity (cable parts). | stable |
| `ConduitLook` | How a conduit block is drawn; its codec is the `look` object of the block state file. Moved from `lib.conduit` for Farms (FM1). | experimental |
| `ConduitLook.Shell` | The `shell` look: translucent glass with border lines and rims Lib computes from the connections; `shell` sprite, optional `border_colour`, `border`, `rim` (Lib 0.14.0), `frame_bars` (Lib 0.15.0). | experimental |
| `ConduitShapes`, `ConduitFaceKey` | A conduit's boxes (core, arms, stubs, rings) in pixels and hit shapes; one face decoration. | experimental |
| `ConduitConnections`, `ConduitFaceModes`, `ConduitColours` | Whether two conduits connect, side masks; six face modes packed in an `int`; body and face colours. `ConduitFaceModes.validateRegistered` is Lib's (`@ApiStatus.Internal`). | experimental |
| `Conduits` | An addon's entry point: `builder(modId, name, type)` with `look`, `creativeTab`, `onWrench`, `connectionRule` and `register()`; `host`, `blockOf`, `connectivity`, `isChunkPresent`, `place`, `useWrench`. Lib owns the block, its block entity, dyes and facades; with `look` it also supplies the block state file and item model definition, and a conduit without a loot table drops its item, so the addon ships the item model and lang. Added for Farms (FM1); `setFactory` and `Factory` are Lib's (`@ApiStatus.Internal`). | experimental |
| `ConduitView`, `ConduitHost` | The conduit in a block: type, colour, side masks, face modes, state, facade and render value; plus setting block faces, face modes and the render value, refreshing links and syncing (server only). The render value (Lib 0.14.0) is an addon-defined `int` its client model reads; it is saved and synced only when it changes. Contents (`contents()`, `setContents`) work the same way with a fixed meaning. | experimental |
| `ConduitEntry`, `ConduitWrenchHook`, `ConduitConnectionRule` | A registered conduit's type, block and item; the builder's hooks for a wrench and for which neighbouring conduits a conduit links with. | experimental |
