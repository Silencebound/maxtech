# ADR-0005: Per-face conduit configuration

Date: 2026-09-29
Note (2026-10-17): "conduit" means Lib's generic connecting-block framework; the bundle and pipe-framework parts below are history.
Status: Accepted (bundle parts superseded by ADR-0012; the extraction, routing, filter and card parts were dropped with the pipe framework)

## Context

A conduit that connects to every neighbour it can reach both feeds and takes from it, and nothing on the model shows how a conduit meets a block. The owner wanted EnderIO-style control per face. Constraints: conduits never tick (PRD §8.1), conduit rendering stays a cached baked model, and Lib owns everything more than one conduit type shares.

## Decision

Each face of a conduit that touches a block has a `ConduitFaceMode`, from the conduit's side:

- `INSERT`: the conduit gives to the block.
- `EXTRACT`: the conduit takes from the block.
- `BOTH`
- `DISABLED`: no transfer; the arm is drawn as a short stub so the face can still be seen and wrenched.

Between two conduits a face is only connected or disabled; disabling it on either side cuts the link, which is a topology change. Each `ConduitType` declares the modes it supports and its default. `ConduitFaceMode` and the supported and default modes are in `lib.api.conduit`, because every conduit type and its renderer needs them.

**Storage and sync:**

- Modes pack into an `int` per conduit, 2 bits per side, stored in `ConduitData`.
- The connection mask keeps meaning "linked to another conduit" and drives graph edges. Faces towards blocks get a separate block-face mask; the render mask is the two ORed together.
- The block-face mask is set on the server by the network logic that owns the side lookups, which also gets capability-invalidation callbacks. `ConduitType` has no block-check hook, and the client never queries capabilities.
- Modes are saved with the conduit and sync with its update packet; face changes are rare player actions, so there is no delta sync.
- Changing a mode invalidates that network type's cached routes. Only a conduit-to-conduit disable changes topology.

**Rendering:** arms extend to any block in the block-face mask. The end of an arm at a block carries a connector ring coloured by mode (blue insert, orange extract, green both); a disabled face draws a short grey stub. Rings and stubs are cached model parts, and the scanner lists each face's mode.

**Tools:** using a wrench on a conduit cycles one face through the type's supported modes (the arm or stub that was hit, or the clicked side of the core box). Sneak-using a wrench removes the conduit.

## Consequences

- Conduits get real in/out control per face.
- The conduit model gains a ring part per face; the perf and model-cache budgets must still hold.
