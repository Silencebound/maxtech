# `lib.api.network`

Part of the [Lib api inventory](../lib-1.0.md); labels as defined there.

| Package / type | Purpose | Label |
|---|---|---|
| **`lib.api.network`** | Network topology for every network type: types, their logic, graphs and the per-level manager; addons register their own network types. | stable |
| `UnloadedChunkMode` | `networks.unloadedChunkMode`: truncate or pause. | stable |
| `NetworkManager` | The per-level entry point: add, remove and invalidate nodes, anchors, a node's network and a type's graph. Moved from `lib.network` for Farms (FM1); `tickLevel`, `clear`, `setConfigSource` and `existingGraph` (a type's graph or null, for diagnostics) are Lib's (`@ApiStatus.Internal`). | experimental |
| `NetworkType` | A kind of network, registered with connectivity and logic factories; `byId` returns an `Optional`; `types()` (all registered types, for diagnostics) is Lib's (`@ApiStatus.Internal`). | experimental |
| `NetworkLogic`, `NodeConnectivity` | A type's transport semantics (topology events and a tick per active network) and how it sees the world. | experimental |
| `Network`, `NetworkGraph` | One connected set of nodes (a read-only view) and one level's graph of a type; the graph's constructor, `tick`, `setProfiling`, `acquireProfiling`, `releaseProfiling` and `isProfiling` are Lib's (`@ApiStatus.Internal`). | experimental |
| `RouteTree` | Widest-path routes to a root over one component, built when the component is rebuilt. | experimental |
