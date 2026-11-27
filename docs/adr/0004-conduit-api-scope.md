# ADR-0004: What of the conduit and network code is public api

Date: 2026-09-29
Note (2026-10-17): "conduit" now means only Lib's generic connecting-block framework (cables now, later pipes and wires). The "pipe" framework mentioned here, with its pipe types, was removed (ADR-0014 amendment).
Status: Superseded by ADR-0015 (bundle parts earlier by ADR-0012)

## Context

The conduit, network and pipe code started in internal packages so implementers could work without touching `api`. The development rules require every `api` change to be stable, fully documented and free of NeoForge types, and the `api` source set cannot see `main`. The only other planned user of the network framework was the digital storage network.

## Options considered

1. **Promote everything** (conduit types, pipe types, the network framework). Freezes APIs that were still changing.
2. **Promote the settled conduit types only.** Addons can add conduit tiers against the api; third-party network types wait.
3. **Promote nothing yet.** No api risk, but an addon with only the api jar cannot add a conduit.

## Decision

Option 2. `lib.api.conduit` holds `ConduitType`, `ConduitState` and `ConduitTypes` (the conduit type registry and its lookups). The network framework (`NetworkType`, `NetworkLogic`, `NodeConnectivity`, `Network`, `NetworkManager`) stays internal until the storage network is its second real user, per the development rules' "don't pre-generalise".

Networks have no `NetworkNode` capability: a node is a conduit, keyed by position and network type, and the block answers connectivity.

## Consequences

- Addons register conduit tiers against the api only.
- Third parties cannot yet add network types; promoting the network framework is a recorded task with its own review.
- History: the energy and pipe parts of the original decision were dropped with those frameworks.
