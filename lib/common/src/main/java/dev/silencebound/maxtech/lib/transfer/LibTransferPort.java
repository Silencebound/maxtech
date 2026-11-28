package dev.silencebound.maxtech.lib.transfer;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskPriority;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortFaces;
import dev.silencebound.maxtech.lib.api.transfer.PortSettings;
import dev.silencebound.maxtech.lib.api.transfer.PortStatus;
import dev.silencebound.maxtech.lib.api.transfer.TransferPort;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Lib's {@link TransferPort}: one {@link ScheduledTask}, in Lib's transfer group of the level ({@link PortRegistry}) or
 * the owner's group, runs one operation per kind and then sleeps until an event (a buffer, neighbour, capability or
 * settings change, a neighbouring port's settings change, or {@link #start}). An event less than
 * {@code transfer.minIntervalTicks} after the last operation, whatever it did, runs the port only once that interval is
 * over, so events every tick cannot run a blocked port every tick. When a partner refused or held nothing it backs off
 * ({@code IDLE}, capped by {@code scheduler.maxBackoffTicks}), since a vertical or modded neighbour may change without
 * telling it; with nothing to do it waits for an event. Events during its own operation come from its own moves and are
 * ignored. While started it is in {@link PortRegistry}. Like Storage's device scheduler, the port runs whatever the
 * ticking status of its own chunk, a border chunk included; it only moves through faces whose neighbour is at full
 * status. Server thread only.
 */
public final class LibTransferPort implements TransferPort {
    private static final String SETTINGS = "port_settings";
    /**
     * {@link #lastRun} and {@link #deferredUntil} before any run; far enough below any game time that the difference
     * cannot overflow.
     */
    private static final long NEVER = Long.MIN_VALUE / 2;

    private final @Nullable Function<ServerLevel, TaskGroup> groups;
    private final Task task = new Task();
    private final PortChannel[] channels;
    private final @Nullable ItemChannel items;
    private final List<ResourceKind<?>> kinds;
    private final PortSettings defaults;
    private final Runnable onSettingsChanged;
    private final long[] neighbours = new long[6];
    private final BlockPos[] neighbourPositions = new BlockPos[6];
    private PortSettings settings;
    private @Nullable ServerLevel level;
    private BlockPos pos = BlockPos.ZERO;
    private long packedPos;
    private @Nullable Long2ObjectOpenHashMap<LibTransferPort> registry;
    private @Nullable TaskGroup tasks;
    private boolean running;
    /** Game time of the last operation, whatever it did. */
    private long lastRun = NEVER;
    /** Until this game time a timer will run the task, so events need not queue it again. */
    private long deferredUntil = NEVER;
    /** An event came since the last run. */
    private boolean woken;

    LibTransferPort(@Nullable Function<ServerLevel, TaskGroup> groups, @Nullable ItemChannel items,
            PortSettings defaults, Runnable onSettingsChanged) {
        this.groups = groups;
        this.items = items;
        this.channels = items == null ? new PortChannel[0] : new PortChannel[]{items};
        ResourceKind<?>[] moved = new ResourceKind<?>[channels.length];
        for (int i = 0; i < channels.length; i++) {
            moved[i] = channels[i].kind;
        }
        this.kinds = List.of(moved);
        this.onSettingsChanged = onSettingsChanged;
        this.defaults = sanitise(defaults);
        this.settings = this.defaults;
        for (PortChannel channel : channels) {
            channel.settingsChanged(settings);
        }
    }

    /** The item side, or null for a port without item buffers. */
    @Nullable
    ItemChannel items() {
        return items;
    }

    /** The started port beside face {@code index} ({@code get3DDataValue}), or null. Allocates nothing. */
    @Nullable
    LibTransferPort neighbour(int index) {
        Long2ObjectOpenHashMap<LibTransferPort> ports = registry;
        return ports == null ? null : ports.get(neighbours[index]);
    }

    /**
     * Whether the block beside face {@code index} is in a chunk at full status ({@code Level.isLoaded}, which loads
     * nothing); false while stopped.
     */
    boolean neighbourLoaded(int index) {
        ServerLevel current = level;
        return current != null && current.isLoaded(neighbourPositions[index]);
    }

    /** The position beside face {@code index}; only while started. */
    BlockPos neighbourPos(int index) {
        return neighbourPositions[index];
    }

    /** The level; only while started (operations run only then). */
    ServerLevel level() {
        ServerLevel current = level;
        if (current == null) {
            throw new IllegalStateException("Port is stopped");
        }
        return current;
    }

    /**
     * Wakes the task for an event, unless it comes from the port's own operation or the port is stopped. Within the
     * interval after an operation the timer already set runs it; a port whose group closed joins a group again.
     */
    void wakeForEvent() {
        ServerLevel current = level;
        if (current == null || running) {
            return;
        }
        woken = true;
        if (task.group() == null) {
            join(current);
        } else if (current.getGameTime() >= deferredUntil) {
            task.wake();
        }
    }

    /** Wakes the started ports beside this one: they may have slept because of this port's settings. */
    private void wakeNeighbours() {
        for (int i = 0; i < 6; i++) {
            LibTransferPort neighbour = neighbour(i);
            if (neighbour != null) {
                neighbour.wakeForEvent();
            }
        }
    }

    /** Adds the task to the group for {@code current}: the owner's, or Lib's while the owner's is closed. */
    private void join(ServerLevel current) {
        TaskGroup group = groups == null ? null : groups.apply(current);
        if (group == null || group.isClosed()) {
            group = PortRegistry.group(current);
        }
        tasks = group;
        group.add(task);
    }

    /** Forgets the filters' cached answers (tags may have changed). */
    void forgetFilterAnswers() {
        if (items != null) {
            items.forgetFilterAnswers();
        }
    }

    @Override
    public void start(ServerLevel newLevel, BlockPos newPos) {
        if (level != null) {
            return;
        }
        level = newLevel;
        pos = newPos.immutable();
        packedPos = pos.asLong();
        for (int i = 0; i < 6; i++) {
            neighbourPositions[i] = pos.relative(PortChannel.FACES[i]);
            neighbours[i] = neighbourPositions[i].asLong();
        }
        Long2ObjectOpenHashMap<LibTransferPort> ports = PortRegistry.of(newLevel);
        ports.put(packedPos, this);
        registry = ports;
        for (PortChannel channel : channels) {
            channel.open(this, newLevel, pos, settings.faces(channel.kind));
        }
        lastRun = NEVER;
        deferredUntil = NEVER;
        woken = false;
        forgetFilterAnswers();
        join(newLevel);
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // only this port's own entry is removed
    public void stop() {
        if (level == null) {
            return;
        }
        Long2ObjectOpenHashMap<LibTransferPort> ports = registry;
        if (ports != null && ports.get(packedPos) == this) {
            ports.remove(packedPos);
        }
        registry = null;
        level = null;
        TaskGroup group = tasks;
        if (group != null) {
            group.remove(task);
        }
        tasks = null;
        for (PortChannel channel : channels) {
            channel.close();
        }
    }

    @Override
    public void neighbourChanged() {
        wakeForEvent();
    }

    @Override
    public void bufferChanged() {
        wakeForEvent();
    }

    @Override
    public List<ResourceKind<?>> kinds() {
        return kinds;
    }

    @Override
    public Set<PortFaceMode> allowed(ResourceKind<?> kind) {
        PortChannel channel = channel(kind);
        return channel == null ? Set.of() : channel.allowed;
    }

    @Override
    public PortFaceMode cycleFace(ResourceKind<?> kind, Direction face) {
        PortChannel channel = channel(kind);
        if (channel == null) {
            return PortFaceMode.OFF;
        }
        PortFaceMode next = settings.mode(kind, face).next(channel.allowed);
        setSettings(settings.withMode(kind, face, next));
        return next;
    }

    @Override
    public PortSettings settings() {
        return settings;
    }

    @Override
    public void setSettings(PortSettings newSettings) {
        if (apply(newSettings)) {
            onSettingsChanged.run();
        }
    }

    @Override
    public PortStatus status(ResourceKind<?> kind) {
        PortChannel channel = channel(kind);
        return channel == null || level == null ? PortStatus.OFF : channel.status;
    }

    /** Saves the settings, defaults included, so changed builder defaults never reconfigure an existing port. */
    @Override
    public void save(ValueOutput output) {
        output.store(SETTINGS, PortSettings.CODEC, settings);
    }

    @Override
    public void load(ValueInput input) {
        Optional<PortSettings> loaded = input.read(SETTINGS, PortSettings.CODEC);
        apply(loaded.orElse(defaults));
    }

    /** Puts {@code newSettings} within the allowed modes and uses them; returns whether anything changed. */
    private boolean apply(PortSettings newSettings) {
        PortSettings sanitised = sanitise(newSettings);
        if (sanitised.equals(settings)) {
            return false;
        }
        settings = sanitised;
        ServerLevel current = level;
        for (PortChannel channel : channels) {
            channel.settingsChanged(sanitised);
            if (current != null) {
                channel.open(this, current, pos, sanitised.faces(channel.kind));
            }
        }
        wakeForEvent();
        if (current != null) {
            wakeNeighbours();
        }
        return true;
    }

    private PortSettings sanitise(PortSettings raw) {
        PortSettings result = raw;
        for (PortChannel channel : channels) {
            int faces = raw.faces(channel.kind);
            int allowed = PortFaces.sanitise(faces, channel.allowed);
            if (allowed != faces) {
                result = result.withFaces(channel.kind, allowed);
            }
        }
        return result;
    }

    private @Nullable PortChannel channel(ResourceKind<?> kind) {
        for (PortChannel channel : channels) {
            if (channel.kind == kind) {
                return channel;
            }
        }
        return null;
    }

    /** The group the task is in while started, or null; for tests. */
    public @Nullable TaskGroup tasks() {
        return tasks;
    }

    @Override
    public String toString() {
        return "TransferPort[" + pos + (level == null ? " stopped" : "") + " " + kinds + "]";
    }

    private final class Task extends ScheduledTask {
        Task() {
            super(TaskPriority.BACKGROUND);
        }

        @Override
        protected int run(TaskGroup group, long gameTime) {
            if (level == null) {
                return WAIT;
            }
            int interval = Math.max(1, ServerConfig.TRANSFER_MIN_INTERVAL_TICKS.getAsInt());
            boolean event = woken;
            woken = false;
            long since = gameTime - lastRun;
            if (since < interval && (event || gameTime < deferredUntil)) {
                // Only events wait out the interval: a delay would reset a backoff timer's doubling.
                deferredUntil = gameTime + interval - since;
                return (int) (interval - since);
            }
            lastRun = gameTime;
            deferredUntil = NEVER;
            running = true;
            try {
                boolean moved = false;
                boolean poll = false;
                for (PortChannel channel : channels) {
                    int result = channel.operate(LibTransferPort.this, settings.faces(channel.kind),
                            channel.perOp(settings.rate(channel.kind)));
                    moved |= result == PortChannel.MOVED;
                    poll |= result == PortChannel.POLL;
                }
                if (moved) {
                    deferredUntil = gameTime + interval;
                    return interval;
                }
                return poll ? IDLE : WAIT;
            } finally {
                running = false;
            }
        }
    }
}
