# ADR-0018: Transfer ports

Date: 2026-11-28
Status: Accepted

## Context

Farms' lab hub (FM1) has to push its output into neighbouring chests and pull nutrient from neighbouring tanks, and
later MaxTech machines need the same: a block that moves resources between its own buffers and the blocks beside it.
Storage has import and export ports of its own, but they are built on Storage's networks and cells. Lib now owns
machine inventories (`lib.api.inventory`, ADR-0016) and resource kinds (ADR-0017), so a shared port belongs next to
them.

Constraints: no tickers (PRD 8.1), every move in a transfer transaction counting only what moved (never dupe or lose
items), the common api cannot name NeoForge types (ADR-0002), and kinds from compat modules (Mekanism chemicals,
Ars Nouveau Source) must work without changing the port.

## Options considered

1. **Each mod writes its own push and pull logic.** Simple for one machine; every machine repeats the caching,
   transaction and backoff work, and each copy is a new chance to dupe.
2. **Ports per fixed kind (items, fluids, energy).** A closed enum is easy to pack and save, but a compat kind needs
   changes in Lib's port and every saved setting.
3. **One port over resource kinds.** The port takes buffers of any kind; settings are per kind id; the NeoForge side
   bridges each kind to its capability. Built-in kinds are items, fluids and energy.

## Decision

Option 3, in the common api `lib.api.transfer` and the NeoForge-side api `lib.neoforge.api.transfer`, experimental
until Farms ships.

- `TransferPorts.builder(TaskGroup)` takes buffers (`items(ItemSlots, modes)`, `tank(ResourceTank<K>, modes)` with
  `fluids(FluidTank, modes)`, `energy(EnergyEndpoint, modes)`), a rate cap per kind and a settings listener. A port
  can drive several buffers of one kind, each taking part in `PUSH`, `PULL` or both (owner): an input tank it pulls
  into and an output tank it pushes from, an input and an output slot group. Per face and kind the face's mode picks
  the buffers (`PULL` fills those allowing `PULL`, `PUSH` empties those allowing `PUSH`), first-fit in the order
  they were added. A face of a kind may take `OFF` and the modes of the kind's buffers. Buffer roles are code and not
  saved; settings stay per face and kind. A `TransferPort` runs one scheduled task in the owner's group, sleeps until a buffer or neighbour changes,
  and is started and stopped by its block entity.
- A face pushes or pulls, never both: `PortFaceMode` is `OFF`, `PUSH` or `PULL` (owner). `PortFaces` packs six modes
  into two bits per face, per kind.
- Filters are per port and per kind, not per face (owner): `PortFilter` is an allow or deny list of keys and tags,
  up to `transfer.filterSize` entries from the screen. Tags go through tag support registered per kind.
- Between two Lib ports facing each other only a `PUSH` face facing a `PULL` face moves, and the pushing port does
  it; the `PULL` face skips the pusher (owner, `PortFaceMode.movesAgainst`). Two `PUSH` faces or two `PULL` faces
  move nothing between them, so two ports never move a resource back and forth. A neighbour port's `OFF` face counts
  as a block that is not a port (whether it exposes its buffers is that block's choice): such blocks are pushed into
  and pulled from as usual.
- A move between two handlers without transactions is refused (owner, ADR-0017); with one such side Lib touches it
  last: simulate it, move on the transactional side in a nested transaction, carry out the foreign side, keep only
  what it took.
- `PortSettings` saves per kind id; settings of a kind that is gone, or that no longer load, are kept and saved back,
  like `UnknownResource`.
- `KindBridge<K>` gives a kind's `KindHandler<K>`s through a capability cache (`transactional()` says which handlers
  roll back); `KindBridges` holds one per kind. Lib registers items and fluids; energy moves through Lib's FE edges.
- Which faces start active is the owner's choice: Farms' hub starts with every output face off (owner).
- Limits per operation and the shortest interval come from config: `transfer.itemsPerOp`, `fluidPerOp`,
  `energyPerOp`, `minIntervalTicks`, `filterSize`, in both presets.

## Consequences

- A machine gets pushing and pulling from a few builder calls, and a compat kind gets ports by registering a bridge
  (and tag support if it has tags).
- Settings saves are longer than a fixed bit field, since every entry names its kind.
- Neighbours without change signals (FE, foreign handlers) are polled with a capped backoff.
- Order: T1 the api types, config and this ADR (no moving yet: `build()` throws); T2 the port core with items, dupe
  and chunk GameTests and an idle perf test; T3 fluids, the generic tank-kind core and the adapter for handlers
  without transactions; T4 energy; T5 documentation and the GUI toolkit's face node. Farms' hub then uses it.
