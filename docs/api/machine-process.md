# Running a machine with MachineProcess

How a mod gives its machine block entity a working loop with Lib's `MachineProcess` (`lib.api.machine`, ADR-0019).
There is no base block entity: the process is a field of the mod's own block entity, which wires it to its slots,
tanks, energy endpoint and redstone control. Everything here is experimental until Farms ships. Lib's GameTest
`MachineProcessGameTests` (`lib/neoforge`, `machine_process_*`) builds a demo machine this way with the api only.

## What the process does

- Finds the recipe of its `MachineRecipeType` that the inputs and the selected mode match, and looks again only after
  an input, input tank, mode or datapack change (a reload wakes every machine). A pipe topping up an input with more
  of the same does not look again while a recipe runs.
- Starts a run only when every output fits, chance outputs counted as made, and when the redstone mode allows it.
- Moves the run on once per tick: one tick per tick for a recipe without energy, or by the energy its consumer
  endpoint received for one with energy (half the energy, half the speed).
- When the run is over, takes the inputs and puts out every output in one transaction. If something no longer fits,
  nothing moves: the run holds at its end, draws nothing and finishes when room is made.
- Sleeps otherwise: no recipe, outputs full, no energy, or redstone off. It is one `ScheduledTask` in the level's
  machine group (or the group `tasks` gives) and never ticks while asleep.

Inputs stay in their slots until a run ends, so taking one out mid-run resets the run and loses no items (the energy
paid for it, and in pulse mode its pulse, are gone). In pulse mode each rising edge starts one run, which keeps going
whatever the signal does; in high, low and never a run pauses while the mode does not allow it.

## Wiring it

```java
public final class MixerBlockEntity extends BlockEntity {
    private final EnergyEndpoint energy = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);
    private final ItemSlots slots = ItemSlots.builder(3).input(0, 1).output(1, 3).build(this::slotChanged);
    private final FluidTank water = FluidTank.create(4000, () -> tankChanged(this.water));
    private final RedstoneControl redstone = new RedstoneControl(this::setChanged, this::wake);
    private final MachineProcess process;

    public MixerBlockEntity(BlockPos pos, BlockState state) {
        super(MIXER.get(), pos, state);
        process = MachineProcess.builder(MIXER_RECIPES)   // MachineRecipeType.define(...)
                .items(slots, 0, 1)                       // inputs (then catalysts) from 0, outputs from 1
                .inputTank(water)
                .energy(energy)
                .redstone(redstone)
                .onStatus(status -> { /* working-state look, sound */ })
                .onChange(this::setChanged)
                .build();
    }

    private void slotChanged(int slot) { setChanged(); process.slotChanged(slot); }
    private void tankChanged(ResourceTank<?> tank) { setChanged(); process.tankChanged(tank); }
    private void wake() { process.wake(); }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            redstone.start(server, worldPosition);
            process.start(server, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        process.stop();
    }

    // saveAdditional/loadAdditional: slots, tank, redstone and process.save/load, each in its own child.
}
```

The block forwards `neighborChanged` to `redstone.neighbourChanged(level, pos)`, which wakes the process when the
signal matters. The endpoint joins whatever energy network the mod's host forms; the process takes over its listener
and declares the recipe's `energy_per_tick` as demand only while a run needs it. Without `energy(...)` recipe energy
is ignored. The counts of slots and tanks come from the type's `MachineRecipeShape`; `build()` refuses slots or tanks
that do not cover it.

## Menus

`status()` is a `MachineStatus`, a `MenuStatus` with Lib's English names, for a `Layout.status` line;
`progress()` and `progressTotal()` feed a progress value (`MenuDefinition.Builder.progress`). `mode()` and
`setMode(id)` select one of the shape's modes. `MachineMenus.standard` (a later PR) will build the usual menu.

## Saves

`save` writes the progress, the run's recipe id and the mode; `load` reads them back without waking. After a load the
run keeps its progress only if the inputs match the same recipe again; a recipe a datapack removed resets the run, and
the items stay in their slots.
