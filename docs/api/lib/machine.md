# `lib.api.machine`

Part of the [Lib api inventory](../lib-1.0.md); labels as defined there.

| Package / type | Purpose | Label |
|---|---|---|
| **`lib.api.machine`** | Building blocks composed into a mod's own machine block entity (ADR-0019); no base block entity. Experimental until Farms ships. | experimental |
| `MachineProcess`, `MachineStatus` | The machine loop: `builder(type)` with `items(slots, firstInput, firstOutput)`, `inputTank`, `outputTank`, `energy(consumer)`, `redstone`, `tasks`, `onStatus`, `onChange`; one sleeping `ScheduledTask` (the level's machine group by default) that finds the recipe, pays its energy per tick and finishes a run in one transaction, all or nothing; saves progress, recipe id and mode. `MachineStatus` (idle, running, no recipe, no energy, output full, off by redstone) is a `MenuStatus`. `MachineProcessFactory` is Lib's (`@ApiStatus.Internal`). How to wire one: `docs/api/machine-process.md`. | experimental |
| `RedstoneMode`, `RedstoneControl` | Always, high, low, pulse (one run per rising edge) or never; the control reads the signal on neighbour updates and wakes the process. | experimental |
