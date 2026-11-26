package dev.silencebound.maxtech.storage.sensor;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.energy.EnergyLookups;
import dev.silencebound.maxtech.lib.energy.FeLookups;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.network.FacingAccess;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Reads one machine's values on demand, as a sensor would, for a device that faces it without being a sensor: logic's
 * machine pools (M7L2) read each member's machine this way when no sensor with the pool's label reads it. The same
 * values ({@link SensorRef}: redstone, comparator, block state properties, handlers, pack readers), the same claim
 * check and the same costs: each read charges {@link SensorWorker#READ_COST} to the device owner's team, plus
 * {@code sensor.savedDataCost} when pack readers read the saved data, which happens at most every
 * {@code sensor.savedDataMinIntervalTicks}. A reading is kept until {@link #invalidate} (a block or neighbour update,
 * or the machine's capabilities changing) and read again only when asked after that, so nothing polls. Server thread
 * only.
 */
public final class MachineReader {
    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final SensorReading reading = new SensorReading();
    private final FacingAccess access = new FacingAccess();
    private final Runnable changed;
    private final Runnable invalidated = this::handlersChanged;
    private boolean stale = true;
    private boolean closed;
    private int generation;
    private long lookupTarget = Long.MIN_VALUE;
    private long lastDataRead = NEVER;
    private long lastRead = NEVER;
    private int reads;
    private boolean saveFailed;
    private @Nullable CachedBlockLookup<ResourceHandler<ItemResource>> items;
    private @Nullable CachedBlockLookup<ResourceHandler<FluidResource>> fluids;
    private @Nullable CachedBlockLookup<EnergyHandler> energy;
    private @Nullable CachedBlockLookup<EnergyEndpoint> endpoint;

    /**
     * @param changed
     *            told when the machine's capabilities changed (it may be called from inside a capability lookup, so it
     *            must only mark work)
     */
    public MachineReader(Runnable changed) {
        this.changed = changed;
    }

    /** The machine may have changed (a block or neighbour update): the next {@link #refresh} reads it again. */
    public void invalidate() {
        stale = true;
        access.targetChanged();
    }

    /** Stops the capability listeners; the reader is not used again. */
    public void close() {
        closed = true;
        generation++;
    }

    /** How many times the machine was read; for tests. */
    public int reads() {
        return reads;
    }

    private void handlersChanged() {
        invalidate();
        changed.run();
    }

    /**
     * Makes sure the reading of the block {@code towards} of {@code device} is current: read again when it was
     * invalidated, the target moved, pack reader values are due, or the reading is {@code maxAge} ticks old (a send
     * asks for a recent one, since a machine's contents can change without a block update next to the device; a status
     * look passes {@link Long#MAX_VALUE}), and the owner's team has the checks for it. Returns whether there is a
     * reading to use (possibly an older one while the budget waits); false when the target is not loaded and was never
     * read, or a land claim keeps the owner from it. Allocates only when it reads.
     */
    public boolean refresh(ServerLevel level, long device, Direction towards, OwnerRecord owner, UUID team,
            long gameTime, long maxAge) {
        long targetPos = BlockPos.offset(device, towards);
        if (targetPos != lookupTarget) {
            BlockPos target = BlockPos.of(targetPos);
            int current = ++generation;
            BooleanSupplier valid = () -> generation == current && !closed;
            Direction side = towards.getOpposite();
            items = Platform.get().transfer().items().cache(level, target, side, valid, invalidated);
            fluids = Platform.get().transfer().fluids().cache(level, target, side, valid, invalidated);
            energy = FeLookups.BLOCK.cache(level, target, side, valid, invalidated);
            endpoint = EnergyLookups.BLOCK.cache(level, target, side, valid, invalidated);
            lookupTarget = targetPos;
            lastDataRead = NEVER;
            reading.clear();
            stale = true;
        }
        if (level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(BlockPos.getX(targetPos)),
                SectionPos.blockToSectionCoord(BlockPos.getZ(targetPos))) == null) {
            // Never load a chunk to read a machine; keep what was read.
            return reading.read;
        }
        BlockPos at = BlockPos.of(device);
        if (!access.allowed(level, at, towards, owner)) {
            reading.clear();
            return false;
        }
        BlockPos target = BlockPos.of(targetPos);
        if (reading.data.resolve(level.getBlockState(target))) {
            lastDataRead = NEVER;
        }
        boolean wantsData = !reading.data.isEmpty();
        boolean dataDue = wantsData
                && gameTime - lastDataRead >= StorageServerConfig.SENSOR_SAVED_DATA_INTERVAL.getAsInt();
        if (!stale && !dataDue && gameTime - lastRead < maxAge) {
            return reading.read;
        }
        int cost = dataDue
                ? SensorWorker.READ_COST + StorageServerConfig.SENSOR_SAVED_DATA_COST.getAsInt()
                : SensorWorker.READ_COST;
        if (TeamBudget.charge(team, cost, gameTime) > 0) {
            // Over the budget: keep the last reading; the next look after an update tries again.
            return reading.read;
        }
        reading.read(level, target, towards, find(items), find(fluids), find(energy), find(endpoint));
        if (dataDue) {
            reading.data.read(savedData(level, target));
            lastDataRead = gameTime;
        }
        stale = false;
        lastRead = gameTime;
        reads++;
        return true;
    }

    /** The value with the id {@code id} at the last read ({@link SensorRef}); 0 for an unknown id. Parses the id. */
    public long value(String id) {
        Optional<SensorRef> ref = SensorRef.parse(id);
        return ref.isPresent() ? reading.value(ref.get()) : 0;
    }

    /** What {@link #itemFill} reads when every slot is full. */
    public static final long FILL_SCALE = 1_000_000;

    /**
     * How full the item slots were at the last read, out of {@link #FILL_SCALE}: each slot's count against its item's
     * own max stack size (an empty slot 0), averaged over the slots. 0 without an item handler.
     */
    public long itemFill() {
        return reading.itemFill;
    }

    /** The value {@code ref} names at the last read; 0 for one the target does not have. Allocation-free. */
    public long value(SensorRef ref) {
        return reading.value(ref);
    }

    /** Whether the value with the id {@code id} was found at the last read, as {@link SensorWorker#found}. */
    public boolean found(String id) {
        Optional<SensorRef> ref = SensorRef.parse(id);
        return reading.read && ref.isPresent() && reading.found(ref.get());
    }

    private @Nullable CompoundTag savedData(ServerLevel level, BlockPos target) {
        BlockEntity entity = level.getBlockEntity(target);
        if (entity == null) {
            return null;
        }
        try {
            return entity.saveWithoutMetadata(level.registryAccess());
        } catch (RuntimeException e) {
            if (!saveFailed) {
                saveFailed = true;
                LOGGER.warn("A logic pool could not read the saved data of {} at {}",
                        BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()), target, e);
            }
            return null;
        }
    }

    private static <T> @Nullable T find(@Nullable CachedBlockLookup<T> lookup) {
        return lookup == null ? null : lookup.find();
    }
}
