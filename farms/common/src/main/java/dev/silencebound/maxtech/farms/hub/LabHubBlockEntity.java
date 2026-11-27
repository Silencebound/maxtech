package dev.silencebound.maxtech.farms.hub;

import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.farms.sampler.SamplerItem;
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
// PLATFORM-LEAK: NeoForge's item transfer type, which the sampler slot holds.
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * The lab hub: one nutrient tank, an output buffer, an MU store, a small fuel generator and a sampler charging slot.
 * Nothing ticks. One task in Farms' task group of the level does the hub's work: while fuel burns it credits MU in
 * steps of {@link #BURN_STEP} ticks, and while a sampler is not full and the store holds MU it charges the sampler in
 * steps of the same length. Otherwise it waits for an event (fuel or a sampler put in, energy used, output room) with
 * no timer. A full store pauses the burn instead of wasting it ({@link FuelBurner}). Energy coming in wakes the task
 * only for a sampler that waits for charge, and then not before the next step. Other mods reach the tank, the fuel
 * slot, the output and the FE input through the capabilities the loader glue registers. Server thread only.
 */
public final class LabHubBlockEntity extends BlockEntity {
    /** Ticks between MU credits while fuel burns, and between sampler charging steps. */
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

        @Override
        public void outputChanged() {
            // A fuel remainder that did not fit may fit now.
            Level current = level;
            if (current != null && !burner.isBurning() && storage.hasFuel(current)) {
                wakeIfWaiting();
            }
        }

        @Override
        public void samplerChanged() {
            if (!working) {
                task.wake();
            }
        }
    });
    private final EnergyEndpoint energy;
    private final FuelBurner burner = new FuelBurner();
    private final FuelTask task = new FuelTask();
    private final HubEnergyHandler feHandler;
    /** Set while the task runs, so its own changes do not wake it again. */
    private boolean working;
    /** Game tick from which the next sampler charging step may run; steps are at least {@link #BURN_STEP} apart. */
    private long nextCharge = Long.MIN_VALUE;

    public LabHubBlockEntity(BlockPos pos, BlockState state) {
        super(FarmsContent.LAB_HUB_ENTITY.get(), pos, state);
        long capacity = FarmsServerConfig.hubEnergyCapacity();
        energy = EnergyEndpoint.buffer(capacity, capacity, capacity, EnergyPriority.DEFAULT);
        // A flow change can mean energy leaves the store, which may let a paused burn go on.
        energy.setListener(endpoint -> energyUsed());
        feHandler = new HubEnergyHandler(energy, EnergyNetworks::feRate, this::energyReceived);
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
            energyUsed();
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

    /** The hub's task (fuel and sampler charging), for tests. */
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

    /** Energy left the store: room may have opened for a paused burn or fuel that waits. */
    private void energyUsed() {
        setChanged();
        Level current = level;
        if (current != null && (burner.isBurning() || storage.hasFuel(current))) {
            wakeIfWaiting();
        }
    }

    /**
     * Energy came in (FE every tick from a generator): only a sampler that waits for charge in an empty store needs the
     * task, and the task does not charge before {@link #nextCharge}, so this costs at most one run per step.
     */
    private void energyReceived() {
        setChanged();
        if (FarmsServerConfig.hubSamplerChargeMuPerTick() > 0 && samplerRoom() > 0) {
            wakeIfWaiting();
        }
    }

    /** Wakes the task when it waits for an event; a pending timer is kept, since the task already runs then. */
    private void wakeIfWaiting() {
        if (!working && !task.isQueued() && task.wakeAt() == -1) {
            task.wake();
        }
    }

    /** MU the sampler in the hub still takes; 0 without one. */
    private long samplerRoom() {
        ItemResource sampler = storage.sampler().getResource(0);
        return sampler.isEmpty() ? 0 : SamplerItem.capacity() - SamplerItem.charge(sampler);
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

    /** Charges the sampler and burns fuel into the MU store while there is work; sleeps otherwise. */
    private final class FuelTask extends ScheduledTask {
        FuelTask() {
            super(TaskPriority.BACKGROUND);
        }

        @Override
        protected int run(TaskGroup group, long gameTime) {
            working = true;
            try {
                Level current = level;
                if (current == null || isRemoved()) {
                    return DONE;
                }
                // Charging first: what it takes from a full store lets the burn go on in the same run.
                int charge = charge(gameTime);
                int burn = burn(current, gameTime);
                if (charge == WAIT) {
                    return burn;
                }
                return burn == WAIT ? charge : Math.min(charge, burn);
            } finally {
                working = false;
            }
        }

        /** One charging step when it is due; returns the ticks to the next one, or WAIT for none. */
        private int charge(long gameTime) {
            long rate = FarmsServerConfig.hubSamplerChargeMuPerTick();
            long room = samplerRoom();
            if (rate <= 0 || room <= 0 || energy.stored() <= 0) {
                return WAIT;
            }
            if (gameTime < nextCharge) {
                return (int) Math.max(NEXT_TICK, nextCharge - gameTime);
            }
            long moved = energy.extract(Math.min(room, rate * BURN_STEP));
            if (moved > 0) {
                ItemResource sampler = storage.sampler().getResource(0);
                storage.sampler().set(0,
                        sampler.with(FarmsContent.SAMPLER_CHARGE.get(), SamplerItem.charge(sampler) + moved), 1);
            }
            nextCharge = gameTime + BURN_STEP;
            return samplerRoom() <= 0 || energy.stored() <= 0 ? WAIT : BURN_STEP;
        }

        private int burn(Level current, long gameTime) {
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
