# ADR-0012: Standalone conduit blocks

Date: 2026-10-01
Status: Accepted. Supersedes the bundle parts of ADR-0004 and ADR-0005.
Note (2026-10-17): the bundle block and "pipes" in the old sense are gone for good; a conduit is Lib's generic connecting-block framework (cables now, later pipes and wires), one `ConduitType` per block.

## Context

Conduits first lived in one bundle block, EnderIO-style, with each conduit kind in a fixed 4 px lane (`ConduitSlots`). Rendering drafts in October 2026 showed that sizes could not differ visibly and that attachments and rings were limited to the lane width.

The owner decided on 2026-10-01 to use standalone pipes for now and to park the bundle concept for later.

## Options considered

1. **Keep the bundle.** Compact, already built, but has every limit above.
2. **Standalone blocks, one conduit per block.** Readable and simpler to render. Parallel lines take more space.
3. **Hybrid:** standalone blocks plus a bundle block for tight spots. Two block families to maintain.
4. **Adaptive bundle** (lane size by contents, with a cross-section budget). One block, but the hardest to render and the hardest to keep readable.

Rendered comparisons are in the owner's model-drafts page.

## Decision

Option 2. Each `ConduitType` gets its own block, one conduit per block, drawn at its size's thickness. The bundle block, its slot layout and its model are removed; git history and this ADR keep them for a later bundle or hybrid design.

What stays the same:
- `ConduitType`s, network types and the network graph. Nodes are already keyed by position and network type, and a standalone block is a position with one type.
- Transport logic per `NetworkLogic`.
- Face modes per ADR-0005, with wrench cycling and rings.
- Colours with the same-colour-or-uncolored rule, and facades.
- Cable and pipe block entities never tick.

What changes:
- One block per conduit type, with a shared block entity type for all conduit blocks. Placing a conduit item places its block; there is no adding to an existing block.
- Conduits of the same network type connect across sizes and materials, as they do today between bundles. Different network types never connect.
- **Thickness** comes from the conduit type's size (`lib.api.pipe.PipeSize`), as a fraction of a block; the size tables for MaxTech's cables and pipes went with them (ADR-0014 amendment).
- Rendering is still a cached baked model, never a BER. Parts are keyed by (type, connection mask, colour, face rings).
- Facades apply to a conduit block as they did to a bundle. CB Multipart support may come later as optional compat (`compat.cbmultipart`) if a 26.1 build exists.
- No world migration. Nothing has been released, so old worlds with bundle blocks are not supported.

## Consequences

- Running several network types to one block needs one conduit block per type.
- Rendering gets simpler: no lanes, jogs or slot alignment. `ConduitSlots`, `ConduitShapes`' lane maths and the layout GameTests go away.
- Conduit blocks need datagen: blockstate, model, loot and item model per block. They are generated from the conduit types, never by hand.
- A later bundle or hybrid design can come back as an extra block on the same conduit types and graph.
