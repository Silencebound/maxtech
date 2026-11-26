package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.security.Owned;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.KeyListener;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * What a working world device (collector, placer, breaker) is, whatever its form: a {@link StorageTask} on its
 * network's scheduler, its filter, mode and research gate, and listeners on the filter's exact keys that wake the task
 * when the network's stock of them changes. Nothing ticks: the task sleeps ({@link StorageTask#WAIT}) whenever there is
 * nothing to do and the device's events wake it. Subclasses do one run's work in {@link #work}. Server thread.
 */
public abstract class WorldWorker implements WorldDevice {
    private static final String SETTINGS = "settings";

    /** Where a device stands and what it is part of, from the block entity or the part that holds it. */
    public record Host(DeviceLink link, Owned owner, Supplier<@Nullable Level> level, Supplier<BlockPos> pos,
            Supplier<Direction> facing, BooleanSupplier removed, Runnable saved) {
    }

    protected final Host host;
    private final WorldFilter filter = new WorldFilter(this::filterChanged);
    private final WorldGate gate;
    private final Task task = new Task();
    private final KeyListener listener = (key, total) -> wake();
    private ItemKey[] listened = new ItemKey[0];
    private int mode;
    private int action;
    private boolean started;

    protected WorldWorker(Host host) {
        this.host = host;
        this.gate = new WorldGate(unlock(kind()), host.owner(), this::wake);
    }

    /** The worker for a device of {@code kind}; not for the network chest, which has no task. */
    public static WorldWorker create(WorldKind kind, Host host) {
        return switch (kind) {
            case COLLECTOR -> new CollectorWorker(host);
            case PLACER -> new PlacerWorker(host);
            case BREAKER -> new BreakerWorker(host);
            case NETWORK_CHEST -> throw new IllegalArgumentException("The network chest has no worker");
        };
    }

    private static Identifier unlock(WorldKind kind) {
        return switch (kind) {
            case NETWORK_CHEST -> ResearchGates.NETWORK_CHEST;
            case COLLECTOR -> ResearchGates.COLLECTOR;
            case PLACER -> ResearchGates.PLACER;
            case BREAKER -> ResearchGates.BREAKER;
        };
    }

    @Override
    public WorldFilter filter() {
        return filter;
    }

    @Override
    public int mode() {
        return mode;
    }

    @Override
    public void setMode(int mode) {
        int next = Math.floorMod(mode, kind().modeCount());
        if (next != this.mode) {
            this.mode = next;
            host.saved().run();
            wake();
        }
    }

    @Override
    public int action() {
        return action;
    }

    @Override
    public void setAction(int action) {
        int next = Math.floorMod(action, kind().actionCount());
        if (next != this.action) {
            this.action = next;
            host.saved().run();
            wake();
        }
    }

    @Override
    public OwnerRecord ownerRecord() {
        return host.owner().ownerRecord();
    }

    @Override
    public Owned holder() {
        return host.owner();
    }

    @Override
    public boolean researched() {
        return host.level().get() instanceof ServerLevel server ? gate.open(server) : gate.lastAnswer();
    }

    @Override
    public boolean stillValid(Player player) {
        return !host.removed().getAsBoolean()
                && player.isWithinBlockInteractionRange(host.pos().get(), NetworkChestView.RANGE_BUFFER);
    }

    /** The research gate; for tests. */
    public WorldGate gate() {
        return gate;
    }

    /** Jade and the scanner at a glance: the placer's action ("Drop stacks"); nothing for a kind with one. */
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        if (kind().actionCount() > 1) {
            sink.line(Component.translatable(kind().actionKey(action)));
        }
    }

    /** Jade and the scanner in detail: the mode and the action, for kinds that switch between them. */
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        if (kind().modeCount() > 1) {
            sink.line(
                    Component.translatable(WorldContent.MODE_LABEL_KEY, Component.translatable(kind().modeKey(mode))));
        }
        if (kind().actionCount() > 1) {
            sink.line(Component.translatable(WorldContent.ACTION_LABEL_KEY,
                    Component.translatable(kind().actionKey(action))));
        }
    }

    /** Wakes the task for the network's next pass. */
    public final void wake() {
        host.link().wake(task);
    }

    /** The device is in a loaded level: schedule the task and listen to the filter's keys. */
    public void start(ServerLevel level) {
        started = true;
        relisten();
        host.link().schedule(task);
    }

    /** A neighbour of the device changed (a block, or the redstone signal); wakes the task by default. */
    public void neighbourChanged() {
        wake();
    }

    /** The device is gone (broken or unloaded): it waits for nothing any more. */
    public void removedFromLevel() {
        gate.removed();
    }

    private void filterChanged() {
        host.saved().run();
        if (started) {
            relisten();
        }
        wake();
    }

    private void relisten() {
        for (ItemKey key : listened) {
            host.link().unlisten(key, listener);
        }
        listened = filter.exactKeys();
        for (ItemKey key : listened) {
            host.link().listen(key, listener);
        }
    }

    /** Writes the settings and the subclass's own state. */
    public void save(ValueOutput output) {
        WorldSettings settings = WorldSettings.of(this);
        if (settings != null) {
            output.store(SETTINGS, WorldSettings.CODEC, settings);
        }
    }

    /** Reads what {@link #save} wrote, without saving or waking. */
    public void load(ValueInput input) {
        apply(input.read(SETTINGS, WorldSettings.CODEC).orElse(null));
    }

    /** Takes over settings from an item (or a new device's when null), without saving. */
    public void apply(@Nullable WorldSettings settings) {
        mode = Math.floorMod(WorldSettings.apply(settings, filter, tool()), kind().modeCount());
        action = settings == null ? 0 : kind().actionIndex(settings.action());
        // Loading runs before start; a placed item's settings arrive after it.
        if (started) {
            relisten();
            wake();
        }
    }

    /**
     * One run while the owner's team has the research: the work, and when to run next as a {@link StorageTask} answer.
     * {@code transfer} is valid only during the call.
     */
    protected abstract int work(ServerLevel level, StorageTransfer transfer, long gameTime);

    /** The worker's task on its network's scheduler; one per worker. */
    private final class Task implements StorageTask {
        @Override
        public int run(StorageTransfer transfer, long gameTime) {
            if (host.removed().getAsBoolean()) {
                return DONE;
            }
            // A switched-off module (common config, needs a restart) leaves the device inert for good.
            if (!StorageModules.enabled(kind()) || !(host.level().get() instanceof ServerLevel level)
                    || !gate.open(level)) {
                return WAIT;
            }
            return work(level, transfer, gameTime);
        }
    }
}
