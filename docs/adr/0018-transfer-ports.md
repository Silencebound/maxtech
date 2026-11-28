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

- `TransferPorts.builder()` takes buffers (`items(ItemSlots, modes)`, `tank(ResourceTank<K>, modes)` with
  `fluids(FluidTank, modes)`, `energy(EnergyEndpoint, modes)`), a rate cap per kind and a settings listener. A port
  can drive several buffers of one kind, each taking part in `PUSH`, `PULL` or both (owner): an input tank it pulls
  into and an output tank it pushes from, an input and an output slot group. Per face and kind the face's mode picks
  the buffers (`PULL` fills those allowing `PULL`, `PUSH` empties those allowing `PUSH`), first-fit in the order
  they were added. A face of a kind may take `OFF` and the modes of the kind's buffers. Buffer roles are code and not
  saved; settings stay per face and kind (`Builder.defaults` gives the settings a new port and data without settings
  start with; a port always saves its settings, so changed defaults never reconfigure existing ports). A
  `TransferPort` runs one scheduled task, sleeps until a buffer or neighbour changes, and is started and stopped by
  its block entity. The task joins Lib's transfer group of the level on `start` (one per level, per-owner budgets),
  so an owner needs no group of its own (amended in T2, before Farms F1: owners had to pass a group they did not
  have at construction time); `Builder.tasks(level -> group)` puts it in the owner's group instead, asked again on
  every start and, if that group closed, on the next event. Like Storage's devices, ports run whatever the ticking
  status of their own chunk (border chunks included) and move only through faces whose neighbour is at full status.
- A face pushes or pulls, never both: `PortFaceMode` is `OFF`, `PUSH` or `PULL` (owner). `PortFaces` packs six modes
  into two bits per face, per kind.
- Filters are per port and per kind, not per face (owner): `PortFilter` is an allow or deny list of keys and tags,
  up to `transfer.filterSize` entries from the screen. Tags go through tag support registered per kind.
- Between two Lib ports facing each other only a `PUSH` face facing a `PULL` face moves, and the pushing port does
  it; the `PULL` face skips the pusher (owner, `PortFaceMode.movesAgainst`). Two `PUSH` faces or two `PULL` faces
  move nothing between them, so two ports never move a resource back and forth. A neighbour port's `OFF` face counts
  as a block that is not a port (whether it exposes its buffers is that block's choice): such blocks are pushed into
  and pulled from as usual.
- Buffer roles hold between ports: a port pushing into a neighbouring Lib port's `PULL` face inserts into that port's
  buffers taking part in `PULL`, never into its push-only buffers, which the plain capability would offer. The pusher
  finds the neighbour through an internal registry of started ports by level and position (T2; not api); for a block
  that is not a Lib port it uses the neighbour's capability. The neighbour port's block must expose the capability on
  that face too, so a registry entry left by a block that is gone gets nothing. A port whose settings change wakes the
  started ports beside it, which may have slept because of it.
- A move between two handlers without transactions is refused (owner, ADR-0017); with one such side Lib touches it
  last: simulate it, move on the transactional side in a nested transaction, carry out the foreign side, keep only
  what it took.
- `PortSettings` saves per kind id; settings of a kind that is gone, or that no longer load, are kept and saved back,
  like `UnknownResource`.
- `KindBridge<K>` gives a kind's `KindHandler<K>`s through a capability cache (`transactional()` says which handlers
  roll back); `KindBridges` holds one per kind. Lib registers items and fluids.
- Energy is split in two. Networked energy moves through Lib's FE edges and is not a port buffer. A standalone energy
  buffer (an `EnergyEndpoint` with the `BUFFER` role outside any network, the only energy `energy()` accepts) moves
  through the port, with FE capability lookups on its neighbours and the remainder carried to the next operation.
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

## Amendment (T3, 2026-11-28)

- Tank buffers of any kind move through the kind's `KindBridge`, which common code reaches through Lib's platform;
  a tank of a kind without a bridge is refused at `build()`. Items keep Lib's own item caches (T2). Every tank kind
  moves up to `transfer.fluidPerOp` per operation until a kind needs its own key.
- With a handler that lacks transactions an operation makes one move and commits right after the foreign side is
  carried out; a short execute is kept exactly on Lib's side (the nested part rolls back and only the executed amount
  is moved again). A port's own buffers always roll back, so two such handlers never meet inside a port; the refusal
  is kept in the move itself.
- All ports of a level without a group of their own share Lib's transfer group and with it one owner's scheduler
  budget; no transfer-specific budget, since operations past it only wait for the next tick.
