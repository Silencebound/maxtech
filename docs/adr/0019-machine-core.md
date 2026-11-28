# ADR-0019: Machine core

Date: 2026-11-28
Status: Accepted

## Context

Storage already has machine code (`storage/common/.../storage/machine/`): a processing block entity, module math,
side config, an ACTIVE block state, a recipe type with JEI and REI plugins, and a recipe page layout in Lib's viewer
package. Farms item 6 (the mixer) needs the same pieces, and the planned Machines mod needs them in bulk. Lib now owns
machine inventories (ADR-0016), resource kinds (ADR-0017) and transfer ports (ADR-0018), so the shared machine core
belongs there too, and Storage's machines move onto it later.

Constraints: no tickers (PRD 8.1), every move in a transaction, the common api cannot name NeoForge types (ADR-0002),
and every mod must be able to add machines, recipes and upgrade cards without changing Lib.

## Options considered

1. **A base machine block entity in Lib.** One class to extend. Every machine takes the base's fields, save format and
   tick loop, and a block entity that is also a conduit endpoint or a Farms lab part cannot extend two classes.
2. **Ticking machines.** The simplest loop; an idle machine still costs a tick every tick, which PRD 8.1 forbids.
3. **Composition on the scheduler.** A `MachineProcess` object owned by the block entity, running as one
   `ScheduledTask` and sleeping when idle. Each mod keeps its own block entity.

## Decision

Option 3 for the core; the other areas follow it. All of it goes in the common api `lib.api.machine`, experimental
until Farms ships, except the machine's transactional inventory access, which names NeoForge transfer types and so
sits in `lib.neoforge.api.inventory` beside `Handlers` (amended 2026-11-28, PR #62); everything lands in Lib 0.15.0
(owner, 2026-11-28; no release first).

**Machine core**
- `MachineProcess` is composed into a machine's block entity; there is no base block entity. It runs one
  `ScheduledTask`, sleeps when idle, and wakes on an input, output, energy or redstone change.
- `Handlers.machineItems` and `Handlers.machineTank` (`lib.neoforge.api.inventory`) give role-ignoring transactional
  views of `ItemSlots` and `ResourceTank`, with `insertAll`/`extractAll` that run in a nested transaction, so a recipe
  takes all its inputs and outputs or none. The moves themselves live in an internal common class
  (`lib.inventory.MachineMoves`), so the common `MachineProcess` uses them without its api naming `TransactionContext`.
- `MachineStatus` implements `MenuStatus`; `MachineMenus.standard` builds the usual menu from a process.
- Scope for 0.15.0 (owner): items, tanks, modes and chance outputs. Parallel runs come later, as an upgrade.

**Recipes**
- `MachineRecipe` and `MachineRecipeType.define` give a recipe type its codecs, a datagen builder and its page shape.
  Inputs are items, fluids or other kinds by `ResourceKind`, matched by item or `c:` tag. A recipe has modes (variants
  of one machine) and outputs with a chance.
- JEI and REI plugins are generated from the recipe's shape, so a mod adds a type and gets both viewers (owner).
  EMI follows when it exists for 26.1.2. Minecraft 26.1 no longer syncs recipes to clients, so the plugins' PR must
  send each machine type with NeoForge's `OnDatapackSyncEvent.sendRecipes` for the viewers to see them.

**Upgrades**
- Lib owns the `maxtech_lib:upgrade` data component, `UpgradeSlots` and `UpgradeEffects` (the math that turns the
  installed cards into speed, energy use and so on). Lib registers no cards (owner): each mod registers its own items
  with the component, in tiers 1 to 4 that match the pipe tiers.
- A machine has 3 upgrade slots by default; the count is config.

**Redstone**
- `RedstoneMode` is always, high, low, pulse or never (owner). Pulse runs once per rising edge. `NEVER` keeps a
  machine off whatever the signal. `RedstoneControl` holds the mode and wakes the process on a change.
- The mode is set from the screen through a menu server action (`Builder.cycle`, `Layout.cycleButton`; an ADR-0016
  amendment).

**Working-state look**
- `MachineStates.ACTIVE` is a block state with a delayed off, so a machine between short runs does not flicker. Datagen
  produces the active model variant.
- Light is per machine and defaults to 0. A quiet looping sound plays on the client, and each player can turn it off in
  the client config. No particles (owner).

**Multiblocks**
- A generic validator in Lib, wanted for Machines and Farms (owner). A structure is data (a pattern of block or tag
  predicates and a controller position), not code. Validation is lazy: a neighbour change schedules one check on the
  scheduler, with no polling. The controller's block state shows the formed state.
- Concrete structures are named later, by the mod that needs them; the validator is built for the first of them and
  not before.

**Migration**
- Farms' mixer waits for the full core and gets no interim version (owner). Storage's machines move onto the core
  after Farms ships, so Farms proves the core first.

## Consequences

- A machine gets its loop, saves, menu and viewer pages from a process, a recipe type and a few builder calls; a mod
  without machines pays nothing.
- A machine block entity wires the process (start, stop, save, load) itself; a base class would do that for it, so
  each machine repeats a few lines.
- Two machine implementations exist until Storage migrates, Storage's own and Lib's. Storage's must not grow new
  features in that time.
- The api stays experimental, so Farms can still change it before it is frozen.
- Order, each its own PR: machine inventory access; menu actions and redstone; recipes; `MachineProcess`; process tests and
  perf; `MachineMenus`; viewer plugins; upgrades; working-state look; the multiblock validator once a structure is
  named.
