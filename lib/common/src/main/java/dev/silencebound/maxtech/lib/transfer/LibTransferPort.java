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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Lib's {@link TransferPort}: one {@link ScheduledTask} in the owner's group runs one operation per kind and then
 * sleeps until an event (a buffer, neighbour, capability or settings change, or {@link #start}). After a move it waits
 * {@code transfer.minIntervalTicks}; when a partner refused or held nothing it backs off ({@code IDLE}, capped by
 * {@code scheduler.maxBackoffTicks}), since a vertical or modded neighbour may change without telling it; with nothing
 * to do it waits for an event. Events during its own operation come from its own moves and are ignored. While started
 * it is in {@link PortRegistry}. Server thread only.
 */
public final class LibTransferPort implements TransferPort {
    private static final String SETTINGS = "port_settings";
    /** {@link #lastMove} before any move; far enough below any game time that the difference cannot overflow. */
    private static final long NEVER = Long.MIN_VALUE / 2;

    private final TaskGroup tasks;
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
    private boolean running;
    private long lastMove = NEVER;

    LibTransferPort(TaskGroup tasks, @Nullable ItemChannel items, PortSettings defaults, Runnable onSettingsChanged) {
        this.tasks = tasks;
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

    /** Wakes the task for an event, unless it comes from the port's own operation or the port is stopped. */
    void wakeForEvent() {
        if (level != null && !running) {
            task.wake();
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
        lastMove = NEVER;
        tasks.add(task);
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
        tasks.remove(task);
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

    /** Saves the settings unless they are the builder's defaults, which {@link #load} falls back to. */
    @Override
    public void save(ValueOutput output) {
        if (!settings.equals(defaults)) {
            output.store(SETTINGS, PortSettings.CODEC, settings);
        }
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

    /** The task group's runs of this port's task are its operations; for tests. */
    public TaskGroup tasks() {
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
            long since = gameTime - lastMove;
            if (since < interval) {
                return (int) (interval - since);
            }
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
                    lastMove = gameTime;
                    return interval;
                }
                return poll ? IDLE : WAIT;
            } finally {
                running = false;
            }
        }
    }
}
