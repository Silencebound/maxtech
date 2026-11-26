package dev.silencebound.maxtech.storage.crafting.generator;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternProvider;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.pattern.GeneratorSource;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.FacingAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: the generator pulls its machine's output through NeoForge transfer handlers in transactions
// (PRD 7.2: every transfer is a transaction against the real handlers).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.resource.Resource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * What the generator provider does (M7C item 11): it makes one output, set by the player, by switching the machine it
 * faces on with a redstone signal and collecting what arrives. The network lists it by that output
 * ({@link GeneratorSource}); a crafting job's generator step {@linkplain #order orders} an amount, kept per job so a
 * job that ends or is cancelled {@linkplain #cancel withdraws} its order at once. While an order is open and the device
 * is not stopped (logic, load shedding) the signal is on and a task on the network's scheduler pulls the output from
 * the machine's face touching the generator (only the ordered key, at most what is still owed) every
 * {@code crafting.providerPullTicks}, sooner when the machine reports a change. Every pull is a transaction: network
 * insert first, then exactly that much out of the machine, so only what moved counts, and each arrival is reported to
 * {@link CraftingDeliveries}. Arrivals fill the orders oldest first; once all are filled the signal goes off and the
 * task sleeps without a timer. With nothing arriving for {@code crafting.generatorTimeoutTicks} it reports
 * {@link StuckReason#NOTHING_ARRIVED}, switches off and gives every order up; a job still running orders again on its
 * next run. Measures its rate while it works. Never ticks; server thread.
 */
public final class GeneratorLogic implements PatternProvider, GeneratorSource {
    private static final String SETTINGS = "settings";
    private static final String ORDERS = "orders";
    private static final String RATE = "ticks_per_thousand";

    /** How the block entity gives the signal. */
    public interface Signal {
        /** Switches the signal on or off; does nothing when it already is (the block update tells the neighbours). */
        void apply(boolean on);

        /** The strength or sides changed while the signal stays as it is: tells the neighbours. */
        void refresh();
    }

    private final NetworkDevice device;
    private final DeviceLink link;
    private final Supplier<BlockPos> pos;
    private final Supplier<Direction> facing;
    private final Runnable saved;
    private final Signal signal;
    private final FacingAccess access = new FacingAccess();
    private final Task task = new Task();
    /** Units still owed per job, oldest order first. */
    private final GeneratorOrders orders = new GeneratorOrders();
    private @Nullable CachedBlockLookup<ResourceHandler<ItemResource>> items;
    private @Nullable CachedBlockLookup<ResourceHandler<FluidResource>> fluids;
    private @Nullable ServerLevel level;
    private GeneratorSettings settings = GeneratorSettings.DEFAULT;
    /** Game time of the last arrival, or of the order that started the work. */
    private long lastArrival;
    /** Measured ticks per 1,000 units, a running average; 0 before the first arrival. */
    private long ticksPerThousand;
    private @Nullable StuckReason stuck;
    /** Set by a pull pass when the network could operate but took none of the output. */
    private boolean refused;

    public GeneratorLogic(NetworkDevice device, Supplier<BlockPos> pos, Supplier<Direction> facing, Runnable saved,
            Signal signal) {
        this.device = device;
        this.link = device.deviceLink();
        this.pos = pos;
        this.facing = facing;
        this.saved = saved;
        this.signal = signal;
    }

    /**
     * Starts work in {@code level}: drops orders of jobs that are no longer running (they ended while the generator was
     * unloaded, so their cancel never reached it) and schedules the pull task, which turns a stale signal off when
     * nothing is owed.
     */
    public void start(ServerLevel level) {
        this.level = level;
        GeneratorDispatch.register(level, this);
        if (orders.dropEnded(level)) {
            saved.run();
        }
        access.forget();
        link.schedule(task);
        link.wake(task);
    }

    /** The block entity is gone (broken or unloaded): the level's cancel walk no longer reaches it. */
    public void stop() {
        ServerLevel current = level;
        if (current != null) {
            GeneratorDispatch.unregister(current, this);
        }
    }

    public GeneratorSettings settings() {
        return settings;
    }

    /**
     * Applies new settings; a new output gives open orders up (they were for the old one) and tells the network, and a
     * signal change while working reaches the neighbours.
     */
    public void setSettings(GeneratorSettings next) {
        if (next.equals(settings)) {
            return;
        }
        boolean outputChanged = !next.output().equals(settings.output());
        settings = next;
        if (outputChanged) {
            orders.clear();
            stuck = null;
            ticksPerThousand = 0;
            signal.apply(false);
            link.patternsChanged();
        } else if (signalOn()) {
            signal.refresh();
        }
        saved.run();
    }

    /**
     * The device was stopped or started again (logic switched it, or load shedding paused it, M7L and M7L2): stopped,
     * the signal goes off and the open orders wait with the task; started, the task wakes and switches it on again
     * while something is owed.
     */
    public void stoppedChanged(boolean stopped) {
        if (stopped) {
            signal.apply(false);
        } else if (!orders.isEmpty()) {
            // The time stopped does not count toward the timeout.
            ServerLevel current = level;
            if (current != null) {
                lastArrival = current.getGameTime();
            }
            link.wake(task);
        }
    }

    /** Units still owed on open orders. */
    public long remaining() {
        return orders.remaining();
    }

    /** Whether the signal should be on now: an order is open and the device is not stopped. */
    public boolean signalOn() {
        return !orders.isEmpty() && !link.stopped();
    }

    /** The pull task, for tests and diagnostics. */
    public StorageTask task() {
        return task;
    }

    /** The generator's block position. */
    public BlockPos pos() {
        return pos.get();
    }

    private BlockPos target() {
        return pos.get().relative(facing.get());
    }

    /** The machine changed: look again soon while an order is open. */
    public void targetChanged() {
        access.targetChanged();
        if (!orders.isEmpty()) {
            link.wake(task);
        }
    }

    /** Whether a land claim keeps the generator from the block it faces, at the last check. */
    public boolean claimBlocked() {
        return access.blocked();
    }

    private boolean ready() {
        ServerLevel current = level;
        return current != null && current.getServer().isSameThread() && !device.isRemoved()
                && access.allowed(current, pos.get(), facing.get(), device.ownerRecord());
    }

    @Override
    public Optional<StackKey> output() {
        return settings.output();
    }

    @Override
    public int priority() {
        return settings.priority();
    }

    @Override
    public boolean canGenerate() {
        return settings.output().isPresent() && !link.stopped() && ready();
    }

    @Override
    public boolean gone() {
        return device.isRemoved();
    }

    @Override
    public boolean busy() {
        return !orders.isEmpty();
    }

    @Override
    public long owed(UUID job) {
        return orders.owed(job);
    }

    @Override
    public long order(UUID job, long amount) {
        ServerLevel current = level;
        if (amount <= 0 || current == null || !canGenerate()) {
            return 0;
        }
        if (orders.isEmpty()) {
            lastArrival = current.getGameTime();
            signal.apply(true);
        }
        orders.add(job, amount);
        saved.run();
        link.wake(task);
        return amount;
    }

    @Override
    public void cancel(UUID job) {
        if (!orders.cancel(job)) {
            return;
        }
        if (orders.isEmpty()) {
            signal.apply(false);
        }
        saved.run();
    }

    @Override
    public long ticksPerThousand() {
        return ticksPerThousand;
    }

    /**
     * Why the generator holds a craft up now, or null: its network has no power, storage takes none of the output, or
     * nothing arrived from the machine within the timeout (kept until something arrives). Allocation-free.
     */
    public @Nullable StuckReason stuck() {
        NetworkState network = link.network();
        if (network != null && !network.isPowered()) {
            return StuckReason.NO_POWER;
        }
        return stuck;
    }

    /** Patterns: none; the network lists the generator by its output instead. */
    @Override
    public List<CraftingPattern> patterns() {
        return List.of();
    }

    @Override
    public boolean canAccept(CraftingPattern pattern) {
        return false;
    }

    @Override
    public boolean push(CraftingPattern pattern, int crafts, Object2LongMap<StackKey> inputs) {
        return false;
    }

    /** One run of the pull task. */
    int pull(StorageTransfer transfer, long now) {
        if (orders.isEmpty()) {
            signal.apply(false);
            return StorageTask.WAIT;
        }
        StackKey key = settings.output().orElse(null);
        if (key == null) {
            orders.clear();
            signal.apply(false);
            return StorageTask.WAIT;
        }
        if (link.stopped()) {
            // Turned off by logic or shed: the orders wait, without the signal, until the device runs again.
            signal.apply(false);
            return StorageTask.WAIT;
        }
        // On again after the device was stopped, or after a reload.
        signal.apply(true);
        if (!ready()) {
            return StorageTask.IDLE;
        }
        if (!transfer.canOperate()) {
            return Math.max(2, transfer.retryTicks());
        }
        Direction face = facing.get().getOpposite();
        refused = false;
        long moved = switch (key) {
            case ItemKey item -> pullFrom(items(face), item, transfer);
            case FluidKey fluid -> pullFrom(fluids(face), fluid, transfer);
        };
        if (moved > 0) {
            measure(moved, now);
            lastArrival = now;
            stuck = null;
            orders.fill(moved);
            saved.run();
            CraftingDeliveries.delivered(transfer.network(), key, moved);
            if (orders.isEmpty()) {
                signal.apply(false);
                return StorageTask.WAIT;
            }
            return StorageTask.NEXT_TICK;
        }
        if (refused) {
            stuck = StuckReason.OUTPUT_BLOCKED;
        } else if (now - lastArrival > StorageServerConfig.CRAFTING_GENERATOR_TIMEOUT_TICKS.getAsInt()) {
            stuck = StuckReason.NOTHING_ARRIVED;
            orders.clear();
            signal.apply(false);
            saved.run();
            return StorageTask.WAIT;
        }
        return Math.max(1, StorageServerConfig.CRAFTING_PROVIDER_PULL_TICKS.getAsInt());
    }

    /** A running average of ticks per 1,000 units over the time since the last arrival. */
    private void measure(long moved, long now) {
        long elapsed = Math.max(1, now - lastArrival);
        long sample = Math.max(1, saturatedMultiply(elapsed, 1000) / moved);
        ticksPerThousand = ticksPerThousand == 0 ? sample : (ticksPerThousand * 3 + sample) / 4;
    }

    private @Nullable ResourceHandler<ItemResource> items(Direction face) {
        ServerLevel current = level;
        if (current == null) {
            return null;
        }
        CachedBlockLookup<ResourceHandler<ItemResource>> cache = items;
        if (cache == null) {
            cache = Platform.get().transfer().items().cache(current, target(), face, () -> !device.isRemoved(),
                    this::targetChanged);
            items = cache;
        }
        return cache.find();
    }

    private @Nullable ResourceHandler<FluidResource> fluids(Direction face) {
        ServerLevel current = level;
        if (current == null) {
            return null;
        }
        CachedBlockLookup<ResourceHandler<FluidResource>> cache = fluids;
        if (cache == null) {
            cache = Platform.get().transfer().fluids().cache(current, target(), face, () -> !device.isRemoved(),
                    this::targetChanged);
            fluids = cache;
        }
        return cache.find();
    }

    /** Pulls up to what is owed of {@code key} from every slot holding it; returns what moved. */
    private <T extends Resource> long pullFrom(@Nullable ResourceHandler<T> handler, StackKey key,
            StorageTransfer transfer) {
        if (handler == null) {
            return 0;
        }
        long total = 0;
        long remaining = orders.remaining();
        for (int i = 0; i < handler.size() && total < remaining; i++) {
            T resource = handler.getResource(i);
            long amount = handler.getAmountAsLong(i);
            if (resource.isEmpty() || amount <= 0 || !StackKeys.of(resource).equals(key)) {
                continue;
            }
            long want = Math.min(Math.min(amount, remaining - total), transfer.batchLimit(key.kind()));
            want = Math.min(want, Integer.MAX_VALUE);
            long got = move(handler, i, resource, key, (int) want, transfer);
            if (got > 0) {
                total += got;
            } else if (!transfer.canOperate()) {
                break;
            } else {
                refused = true;
            }
        }
        return total;
    }

    /** One operation: into the network first, then exactly that much out of the machine, or nothing. */
    private static <T extends Resource> long move(ResourceHandler<T> handler, int index, T resource, StackKey key,
            int amount, StorageTransfer transfer) {
        try (Transaction root = Transaction.openRoot()) {
            long stored = transfer.insert(key, amount, root);
            if (stored <= 0) {
                return 0;
            }
            int taken = handler.extract(index, resource, (int) stored, root);
            if (taken != stored) {
                return 0;
            }
            root.commit();
            return stored;
        }
    }

    /** Writes the settings, the open orders and the measured rate. */
    public void save(ValueOutput output) {
        output.store(SETTINGS, GeneratorSettings.CODEC, settings);
        orders.save(output, ORDERS);
        output.putLong(RATE, ticksPerThousand);
    }

    /** Reads what {@link #save} wrote, without saving or telling the network. */
    public void load(ValueInput input) {
        settings = input.read(SETTINGS, GeneratorSettings.CODEC).orElse(GeneratorSettings.DEFAULT);
        orders.load(input, ORDERS);
        ticksPerThousand = Math.max(0, input.getLongOr(RATE, 0L));
    }

    /** Takes settings from a placed item; open orders are not kept in items. */
    public void read(GeneratorSettings components) {
        settings = components;
    }

    private static long saturatedMultiply(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        return high == 0 && low >= 0 ? low : Long.MAX_VALUE;
    }

    /** The pull work on the network's scheduler; one per generator, scheduled when it starts. */
    private final class Task implements StorageTask {
        @Override
        public int run(StorageTransfer transfer, long gameTime) {
            return pull(transfer, gameTime);
        }
    }
}
