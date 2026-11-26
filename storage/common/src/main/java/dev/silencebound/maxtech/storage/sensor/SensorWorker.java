package dev.silencebound.maxtech.storage.sensor;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.energy.EnergyLookups;
import dev.silencebound.maxtech.lib.energy.FeLookups;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.FacingAccess;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * What a sensor does, shared by the block and the part: it reads the block in front of it (its target) and keeps what
 * it read as values the logic layer and the sensor's screen ask for ({@link #value}, {@link #values}). Reading never
 * changes the target, and a target a land claim keeps the owner from is not read at all ({@link FacingAccess}): the
 * values read 0 and the list is empty until a later check, made when the claim answer runs out, allows it.
 *
 * <p>
 * Nothing polls unless the player asks for it. The sensor reads once when it starts (placed, or its chunk loaded) and
 * when it joins a network; then whenever its target tells its neighbours of a change (a block update, or a container's
 * comparator update), or its target's handlers are replaced (block entity placed or removed, chunk loaded). A target
 * that never tells anyone can be read every 1, 5 or 30 seconds ({@link SensorPoll}). Every read runs as a task on the
 * network's scheduler, so events within a tick become one read, and costs {@link #READ_COST} checks of the owner's team
 * budget ({@link TeamBudget}); over budget, the read waits for the next second. A target whose chunk is not loaded is
 * not read: its last values stay, and the sensor reads once more when the chunk loads.
 *
 * <p>
 * Listeners ({@link #watch}) hear of a value only when it changed. The handler lookups are cached and made anew only
 * when a block update comes from the target's side or the target moves. Server thread only.
 */
public final class SensorWorker implements StorageTask {
    private static final String SETTINGS = "sensor";
    /** Most values a list holds; a screen or the editor never needs more. */
    public static final int MAX_VALUES = 256;
    /** Checks one read costs of the owner's team budget; a constant for the prototype, like the logic node costs. */
    public static final int READ_COST = 2;
    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Whether a position is loaded; replaced only by GameTests, which cannot unload a chunk next to a test. */
    @FunctionalInterface
    public interface LoadedCheck {
        boolean isLoaded(ServerLevel level, BlockPos pos);
    }

    private static final LoadedCheck CHUNK_LOADED = (level, pos) -> level.getChunkSource().getChunkNow(
            SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())) != null;
    private static LoadedCheck loadedCheck = CHUNK_LOADED;

    /** What the worker needs from the block or part that holds it. */
    public interface Host {
        /** The level the sensor is in; null before it is placed. */
        @Nullable
        ServerLevel serverLevel();

        /** The sensor's block: its own position, or the cable a part sits on. */
        BlockPos sensorPos();

        /** The side the target is on. */
        Direction facing();

        /** The settings changed: save and sync. */
        void settingsChanged();
    }

    /** One listener for one value, with what it was last told. */
    private static final class Watch {
        final String id;
        final SensorRef ref;
        final SensorListener listener;
        long last;
        boolean told;

        Watch(String id, SensorRef ref, SensorListener listener) {
            this.id = id;
            this.ref = ref;
            this.listener = listener;
        }
    }

    private final NetworkDevice device;
    private final Host host;
    private final SensorReading reading = new SensorReading();
    private final ObjectArrayList<Watch> watches = new ObjectArrayList<>(0);
    /** Told after every read (Logic Controllers); see {@link #listenReads}. */
    private final ObjectArrayList<Runnable> readListeners = new ObjectArrayList<>(0);
    private final Runnable invalidated = this::handlersChanged;
    private final FacingAccess access = new FacingAccess();
    private SensorSettings settings = SensorSettings.DEFAULT;
    private boolean started;
    private boolean pending;
    private boolean targetLoaded = true;
    /** Bumped whenever the lookups are made anew, so the old ones' listeners stop. */
    private int generation;
    private @Nullable BlockPos lookupTarget;
    private @Nullable CachedBlockLookup<ResourceHandler<ItemResource>> items;
    private @Nullable CachedBlockLookup<ResourceHandler<FluidResource>> fluids;
    private @Nullable CachedBlockLookup<EnergyHandler> energy;
    private @Nullable CachedBlockLookup<EnergyEndpoint> endpoint;
    private int reads;
    private long version;
    /** Game time of the last saved-data read; {@link #NEVER} before the first for the current readers. */
    private long lastDataRead = NEVER;
    private int dataReads;
    private boolean saveFailed;

    public SensorWorker(NetworkDevice device, Host host) {
        this.device = device;
        this.host = host;
    }

    /** Replaces the loaded check (GameTests only); null restores the real one. */
    public static void setLoadedCheck(@Nullable LoadedCheck check) {
        loadedCheck = check == null ? CHUNK_LOADED : check;
    }

    public SensorSettings settings() {
        return settings;
    }

    /** Reads since the sensor was made; tests use it to see that nothing polls. */
    public int reads() {
        return reads;
    }

    /** Reads of the target's saved data for pack readers since the sensor was made; for tests. */
    public int dataReads() {
        return dataReads;
    }

    /** Bumped by every read and every change of the target's loaded state; screens resend their list when it moves. */
    public long version() {
        return version;
    }

    /** Whether the target's chunk was loaded at the last attempt to read it. */
    public boolean targetLoaded() {
        return targetLoaded;
    }

    /** Whether a land claim keeps the sensor from its target, at the last check; it then reads nothing. */
    public boolean claimBlocked() {
        return access.blocked();
    }

    /** The sensor's claim check; tests read how often it asked. */
    public FacingAccess access() {
        return access;
    }

    /** The target's block state at the last read; air before the first. */
    public BlockState targetState() {
        return reading.state;
    }

    /** Whether the target was read at least once. */
    public boolean hasRead() {
        return reading.read;
    }

    /** The block the sensor reads, as {@link BlockPos#asLong()}. */
    public long targetPos() {
        return BlockPos.offset(host.sensorPos().asLong(), host.facing());
    }

    /** The value {@code ref} names at the last read; 0 for one the target does not have, and before the first read. */
    public long value(SensorRef ref) {
        return reading.value(ref);
    }

    /** The value with the id {@code id} at the last read ({@link SensorRef}); 0 for an unknown id. Parses the id. */
    public long value(String id) {
        Optional<SensorRef> ref = SensorRef.parse(id);
        return ref.isPresent() ? reading.value(ref.get()) : 0;
    }

    /**
     * Whether the value with the id {@code id} was found at the last read. Only pack reader values
     * ({@code data.<name>}) and ids that name no value at all can be missing: no reader of the target names it, or its
     * path was not in the target's saved data. Every other id counts as found (an absent item reads 0). Parses the id.
     */
    public boolean found(String id) {
        Optional<SensorRef> ref = SensorRef.parse(id);
        return ref.isPresent() && reading.found(ref.get());
    }

    /** The names of the pack reader values the last read did not find, for info lines. Allocates. */
    public List<String> missingValues() {
        List<String> names = new ArrayList<>();
        reading.data.missing(names);
        return names;
    }

    /**
     * The values the target offered at the last read, in a stable order (see {@link SensorReading#values}), at most
     * {@link #MAX_VALUES}. Allocates; for screens and the editor.
     */
    public List<SensorValue> values() {
        return reading.values(MAX_VALUES);
    }

    /**
     * Tells {@code listener} whenever the value {@code id} changes; an unknown id is never told. Watching the same id
     * with the same listener twice adds it once. The watch stays until {@link #unwatch}; it is not saved.
     */
    // Listeners are their owners' objects, compared by identity.
    @SuppressWarnings("ReferenceEquality")
    public void watch(String id, SensorListener listener) {
        Optional<SensorRef> ref = SensorRef.parse(id);
        if (ref.isEmpty()) {
            return;
        }
        for (int i = 0; i < watches.size(); i++) {
            Watch watch = watches.get(i);
            if (watch.listener == listener && watch.id.equals(id)) {
                return;
            }
        }
        Watch watch = new Watch(id, ref.get(), listener);
        if (reading.read) {
            watch.last = reading.value(watch.ref);
            watch.told = true;
        }
        watches.add(watch);
    }

    /** Stops telling {@code listener} about {@code id}. */
    @SuppressWarnings("ReferenceEquality") // as in watch
    public void unwatch(String id, SensorListener listener) {
        for (int i = watches.size() - 1; i >= 0; i--) {
            Watch watch = watches.get(i);
            if (watch.listener == listener && watch.id.equals(id)) {
                watches.remove(i);
            }
        }
    }

    /**
     * Runs {@code listener} after every read, for the Logic Controller, which compares the values it watches itself;
     * adding the same listener twice adds it once. The listener only marks work. Not saved.
     */
    // Listeners are their owners' objects, compared by identity.
    @SuppressWarnings("ReferenceEquality")
    public void listenReads(Runnable listener) {
        for (int i = 0; i < readListeners.size(); i++) {
            if (readListeners.get(i) == listener) {
                return;
            }
        }
        readListeners.add(listener);
    }

    /** Stops running {@code listener} after reads. */
    public void unlistenReads(Runnable listener) {
        readListeners.remove(listener);
    }

    /** Starts work: schedules the read task and reads once. Call when the sensor is in a loaded level. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        access.forget();
        device.deviceLink().schedule(this);
        refresh();
    }

    /** Replaces the settings; a new polling interval takes effect with a read at the next chance. */
    public void setSettings(SensorSettings settings) {
        if (settings.equals(this.settings)) {
            return;
        }
        this.settings = settings;
        host.settingsChanged();
        refresh();
    }

    /** The sensor joined a network (or left one): read on its next pass there. */
    public void networkChanged(@Nullable StorageNetwork network) {
        if (network != null) {
            pending = false;
            refresh();
        }
    }

    /**
     * The target may have changed: {@code blockUpdate} for a block update from its side (a block placed, removed or
     * changed there), which also makes the handler lookups anew; false for a comparator update of its contents.
     */
    public void targetChanged(boolean blockUpdate) {
        if (blockUpdate) {
            lookupTarget = null;
            access.targetChanged();
        }
        refresh();
    }

    /** Asks for a read on the network's next pass, once; repeated events before it runs cost nothing. */
    public void refresh() {
        if (started && !pending) {
            pending = true;
            device.deviceLink().wake(this);
        }
    }

    /** A cached lookup's answer may have changed; it must not be queried from here. */
    private void handlersChanged() {
        access.targetChanged();
        refresh();
    }

    @Override
    public int run(StorageTransfer transfer, long gameTime) {
        pending = false;
        ServerLevel level = host.serverLevel();
        if (level == null || device.isRemoved()) {
            return WAIT;
        }
        Direction facing = host.facing();
        BlockPos target = host.sensorPos().relative(facing);
        BlockPos cachedFor = lookupTarget;
        if (cachedFor == null || !cachedFor.equals(target)) {
            makeLookups(level, target, facing.getOpposite());
        }
        if (!loadedCheck.isLoaded(level, target)) {
            // Query the caches anyway: that arms their listeners, which fire when the chunk loads.
            find(items);
            find(fluids);
            find(energy);
            find(endpoint);
            if (targetLoaded) {
                targetLoaded = false;
                version++;
            }
            return WAIT;
        }
        if (!access.allowed(level, host.sensorPos(), facing, device.ownerRecord())) {
            // A claim keeps the sensor off the target: it reads nothing until a later check allows it.
            if (reading.read) {
                reading.clear();
                version++;
                tellWatches();
            }
            return access.retryTicks(level);
        }
        if (reading.data.resolve(level.getBlockState(target))) {
            // Other readers, or another block: read its saved data at once.
            lastDataRead = NEVER;
        }
        boolean wantsData = !reading.data.isEmpty();
        int interval = StorageServerConfig.SENSOR_SAVED_DATA_INTERVAL.getAsInt();
        boolean dataDue = wantsData && gameTime - lastDataRead >= interval;
        int cost = dataDue ? READ_COST + StorageServerConfig.SENSOR_SAVED_DATA_COST.getAsInt() : READ_COST;
        int wait = TeamBudget.charge(TeamBudget.team(level, device), cost, gameTime);
        if (wait > 0) {
            pending = true;
            return Math.max(2, wait);
        }
        targetLoaded = true;
        reading.read(level, target, facing, find(items), find(fluids), find(energy), find(endpoint));
        if (dataDue) {
            reading.data.read(savedData(level, target));
            lastDataRead = gameTime;
            dataReads++;
        }
        reads++;
        version++;
        tellWatches();
        for (int i = 0; i < readListeners.size(); i++) {
            readListeners.get(i).run();
        }
        int poll = settings.poll().ticks();
        if (wantsData && !dataDue) {
            // Too soon for another saved-data read: read again once the interval is over, so the reader values catch
            // up with the change that woke this read. Events until then fold into that read.
            int left = (int) Math.max(1, lastDataRead + interval - gameTime);
            pending = true;
            return poll > 0 ? Math.min(poll, left) : left;
        }
        return poll > 0 ? poll : WAIT;
    }

    /**
     * The target's saved data, as its block entity writes it for saving, or null when it has none. A block entity whose
     * saving throws counts as having none (logged once per sensor), so a broken mod never breaks the sensor.
     */
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
                LOGGER.warn("Sensor at {} could not read the saved data of {} at {}", host.sensorPos(),
                        BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()), target, e);
            }
            return null;
        }
    }

    private void makeLookups(ServerLevel level, BlockPos target, Direction side) {
        int current = ++generation;
        BooleanSupplier valid = () -> generation == current && !device.isRemoved();
        items = Platform.get().transfer().items().cache(level, target, side, valid, invalidated);
        fluids = Platform.get().transfer().fluids().cache(level, target, side, valid, invalidated);
        energy = FeLookups.BLOCK.cache(level, target, side, valid, invalidated);
        endpoint = EnergyLookups.BLOCK.cache(level, target, side, valid, invalidated);
        lookupTarget = target;
    }

    private static <T> @Nullable T find(@Nullable CachedBlockLookup<T> lookup) {
        return lookup == null ? null : lookup.find();
    }

    private void tellWatches() {
        for (int i = 0; i < watches.size(); i++) {
            Watch watch = watches.get(i);
            long value = reading.value(watch.ref);
            if (!watch.told || value != watch.last) {
                watch.told = true;
                watch.last = value;
                watch.listener.sensorChanged(this, watch.id, value);
            }
        }
    }

    public void save(ValueOutput out) {
        out.store(SETTINGS, SensorSettings.CODEC, settings);
    }

    public void load(ValueInput in) {
        settings = in.read(SETTINGS, SensorSettings.CODEC).orElse(SensorSettings.DEFAULT);
    }

    /** Takes the settings from an item without saving or reading; before the sensor starts. */
    public void loadSettings(SensorSettings settings) {
        this.settings = settings;
    }
}
