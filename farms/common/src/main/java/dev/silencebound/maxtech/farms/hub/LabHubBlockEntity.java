package dev.silencebound.maxtech.farms.hub;

import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.api.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The lab hub: one nutrient tank, an output buffer, an MU store and a small fuel generator. Nothing ticks. The fuel
 * generator is a task in Farms' task group of the level: it runs while fuel burns, crediting MU in steps of
 * {@link #BURN_STEP} ticks, and waits for an event (fuel put in, energy used) otherwise. A full store pauses the burn
 * instead of wasting it ({@link FuelBurner}). Other mods reach the tank, the fuel slot, the output and the FE input
 * through the capabilities the loader glue registers. Server thread only.
 */
public final class LabHubBlockEntity extends BlockEntity {
    /** Ticks between MU credits while fuel burns. */
    static final int BURN_STEP = 20;

    private final HubStorage storage = new HubStorage(() -> level, new HubStorage.Listener() {
        @Override
        public void changed() {
            setChanged();
        }

        @Override
        public void fuelChanged() {
            if (!working) {
                task.wake();
            }
        }
    });
    private final EnergyEndpoint energy;
    private final FuelBurner burner = new FuelBurner();
    private final FuelTask task = new FuelTask();
    private final HubEnergyHandler feHandler;
    /** Set while the fuel task runs, so its own changes do not wake it again. */
    private boolean working;

    public LabHubBlockEntity(BlockPos pos, BlockState state) {
        super(FarmsContent.LAB_HUB_ENTITY.get(), pos, state);
        long capacity = FarmsServerConfig.hubEnergyCapacity();
        energy = EnergyEndpoint.buffer(capacity, capacity, capacity, EnergyPriority.DEFAULT);
        energy.setListener(endpoint -> energyChanged());
        feHandler = new HubEnergyHandler(energy, EnergyNetworks::feRate, this::energyChanged);
    }

    /** The tank, fuel slot and output buffer. */
    public HubStorage storage() {
        return storage;
    }

    /** The hub's MU store. */
    public EnergyEndpoint energy() {
        return energy;
    }

    /** Spends up to {@code mu} of the store for the hub's own work and returns what it took; wakes a paused burn. */
    public long useEnergy(long mu) {
        long taken = energy.extract(mu);
        if (taken > 0) {
            energyChanged();
        }
        return taken;
    }

    /** FE from other mods, converted at Lib's rate; insert only. */
    public HubEnergyHandler feHandler() {
        return feHandler;
    }

    /** The fuel generator: burn ticks left and in total. */
    public FuelBurner burner() {
        return burner;
    }

    /** The fuel generator's task, for tests. */
    public ScheduledTask fuelTask() {
        return task;
    }

    /** Whether the hub holds nothing worth keeping on its dropped item. */
    public boolean isPristine() {
        return storage.isEmpty() && energy.stored() == 0 && !burner.isBurning();
    }

    /** The hub's item with everything it holds, for drops. */
    public ItemStack toItemStack(HolderLookup.Provider registries) {
        ItemStack stack = new ItemStack(getBlockState().getBlock());
        writeContents(stack, registries);
        return stack;
    }

    /** Puts what the hub holds on {@code stack} (its block entity data); nothing for a pristine hub. */
    public void writeContents(ItemStack stack, HolderLookup.Provider registries) {
        if (!isPristine()) {
            stack.set(DataComponents.BLOCK_ENTITY_DATA,
                    TypedEntityData.<BlockEntityType<?>>of(getType(), saveCustomOnly(registries)));
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            FarmsTasks.group(server).add(task);
            task.wake();
        }
    }

    @Override
    public void setRemoved() {
        TaskGroup group = task.group();
        if (group != null) {
            group.remove(task);
        }
        super.setRemoved();
    }

    private void energyChanged() {
        setChanged();
        // Room may have opened for a paused burn or waiting fuel; FE coming in every tick wakes nothing otherwise.
        if (!working && (burner.isBurning() || !storage.fuel().getResource(0).isEmpty())) {
            task.wake();
        }
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        storage.save(out);
        out.putLong("energy", energy.stored());
        out.putInt("burn_left", burner.burnLeft());
        out.putInt("burn_total", burner.burnTotal());
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        storage.load(in);
        energy.setStored(in.getLongOr("energy", 0));
        burner.load(in.getIntOr("burn_left", 0), in.getIntOr("burn_total", 0));
    }

    /** Burns fuel into the MU store while there is fuel and room; sleeps otherwise. */
    private final class FuelTask extends ScheduledTask {
        FuelTask() {
            super(TaskPriority.BACKGROUND);
        }

        @Override
        protected int run(TaskGroup group, long gameTime) {
            working = true;
            try {
                return burn(gameTime);
            } finally {
                working = false;
            }
        }

        private int burn(long gameTime) {
            Level current = level;
            if (current == null || isRemoved()) {
                return DONE;
            }
            long rate = FarmsServerConfig.hubFuelMuPerTick();
            if (rate <= 0) {
                burner.pause();
                return WAIT;
            }
            long made = burner.step(gameTime, energy.capacity() - energy.stored(), rate);
            if (made > 0) {
                energy.insert(made);
                setChanged();
            }
            if (!burner.isBurning() && energy.capacity() - energy.stored() >= rate) {
                burner.start(storage.takeFuel(current), gameTime);
            }
            if (!burner.isBurning()) {
                return WAIT;
            }
            if (energy.capacity() - energy.stored() < rate) {
                // Full: the burn waits for energy to be used, which wakes the task.
                burner.pause();
                return WAIT;
            }
            int next = Math.min(BURN_STEP, burner.burnLeft());
            return next <= 1 ? NEXT_TICK : next;
        }
    }
}
