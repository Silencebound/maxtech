package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.Owned;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafts;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.FacingAccess;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.OperationMoves;
import dev.silencebound.maxtech.storage.storagechest.StorageChestBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.redstone.RedstoneControl;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: ports move items and fluids through NeoForge transfer handlers in transactions (PRD 7.2).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.resource.Resource;
// PLATFORM-LEAK: see above; a storage bus looks only while no transaction is open.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * What every port does, whatever its form (the block or the part on a cable) and tier: a {@link StorageTask} on its
 * network's scheduler that moves items and fluids between the faced inventory and the network at its rate (the tier's
 * {@code ports.tier<n>.*}, times {@code ports.speedCards.multiplier} once per speed card), in operations of up to one
 * batch, each a {@link PortTransfers} transaction. The faced inventory's handlers are cached (Lib's block lookups) and
 * re-read only when they change. With nothing to move the task backs off (Lib's idle backoff), and the inventory's own
 * change events wake it again; a refused operation waits as long as the network says. A tier that needs research sleeps
 * until a node completes for its owner's team ({@link PortResearchWaits}). A port a land claim keeps from the inventory
 * moves nothing and sleeps until its claim answer runs out ({@link FacingAccess}). Never ticks. Server thread.
 *
 * <p>
 * In {@link PortMode#STORAGE} the port moves nothing itself: it adds {@link PortStorage} to its network's storage, and
 * the same task only looks at the inventory now and then ({@link BusCadence}): it compares a cheap fingerprint of the
 * contents and builds them again only when that changed, its settings changed or the mount changed. Before each look it
 * checks the mount: the claim, and whether the faced block is a device of a network that would loop
 * ({@link BusStatus#LOOP}). The rate, speed cards and research do not apply in storage mode.
 */
public final class PortWorker {
    private static final String FILTER = "filter";
    private static final String CARDS = "cards";
    private static final String ACCESS = "access";
    private static final String PRIORITY = "priority";
    private static final String ENTRIES = "filter_entries";
    private static final String ORDER = "order";

    private final DeviceLink link;
    private final PortTier tier;
    private final Owned owner;
    private final Supplier<PortMode> mode;
    private final Runnable saved;
    private final PortFilter filter = new PortFilter(this::settingsChanged);
    private final PortCards cards = new PortCards(this::settingsChanged);
    private final PortRate itemRate = new PortRate();
    private final PortRate fluidRate = new PortRate();
    /** What the last item import moved, reused by every run. */
    private final OperationMoves itemMoves = new OperationMoves();
    /** What the last item export moved, reused by every run. */
    private final OperationMoves exportMoves = new OperationMoves();
    /** What an export of each kind tries, in order (B09 item 18). */
    private final PortExports itemExports = new PortExports(false);
    private final PortExports fluidExports = new PortExports(true);
    /** What each entry may still move this operation (the regulator, B09 item 22); reused. */
    private final long[] room = new long[PortFilter.SIZE];
    /** Which key of each kind's export list a round-robin export tries first. */
    private int itemTurn;
    private int fluidTurn;
    private PortOrder order = PortOrder.FILTER;
    private final Task task = new Task();
    private @Nullable CachedBlockLookup<ResourceHandler<ItemResource>> items;
    private @Nullable CachedBlockLookup<ResourceHandler<FluidResource>> fluids;
    private final FacingAccess access = new FacingAccess();
    private @Nullable ServerLevel level;
    private @Nullable BlockPos pos;
    private Direction facing = Direction.NORTH;
    private BooleanSupplier loaded = () -> false;
    private boolean facedChestFull;
    /** Set while the task runs, so the inventory's change events from the port's own moves do not wake it again. */
    private boolean running;
    /** Where the next import looks first: the index the last import took from, so a slot is emptied before the next. */
    private int itemIndex;
    private int fluidIndex;
    /** Whether the owner's team had the tier's research at the last check; once true it is not checked again. */
    private boolean researched;
    private boolean researchChecked;
    /** Whether the crafting card's last request is still out; a card has one request out at most. */
    private boolean cardRequestOut;
    /** Game time before which the crafting card asks for nothing ({@code crafting.cardIntervalTicks}). */
    private long nextCardRequest;
    private int cardRequests;
    /**
     * Game time at which the run in progress wants to run again for the crafting card, 0 for none: a request refused or
     * held back by the interval is tried again after it, once, instead of waiting for the idle backoff.
     */
    private long cardRetryAt;
    /** The game time of the run in progress, for the crafting card's rate limit. */
    private long now;
    /** The port in storage mode as network storage; in the network's index only while the mode is storage. */
    private final PortStorage bus = new PortStorage(new BusHost());
    private final List<StorageProvider> busProviders = List.of(bus);
    private final BusCadence cadence = new BusCadence();
    private BusAccess busAccess = BusAccess.BOTH;
    /** The storage priority in storage mode (the shared priority page); higher takes stores first. */
    private int priority;
    private BusStatus busStatus = BusStatus.NOTHING;
    /** The fingerprint of the contents at the last look that built them. */
    private long fingerprint;
    /** The filter, access, mode or on/off state changed: the next look builds the contents whatever the fingerprint. */
    private boolean busDirty = true;
    /** The Storage device the port faces (an interface, a network chest, a part), when {@link #facedKnown}. */
    private @Nullable NetworkDevice facedDevice;
    private boolean facedKnown;
    /** The redstone mode (B09 item 9); pulse mode counts except in storage mode. */
    private final RedstoneControl redstone;

    /**
     * @param owner
     *            whose team's research decides whether a tier that needs research works
     * @param mode
     *            the port's mode now
     * @param saved
     *            marks the port's holder for saving (a filter or card change)
     */
    public PortWorker(DeviceLink link, PortTier tier, Owned owner, Supplier<PortMode> mode, Runnable saved) {
        this.link = link;
        this.tier = tier;
        this.owner = owner;
        this.mode = mode;
        this.saved = saved;
        this.redstone = new RedstoneControl(link, true, () -> this.mode.get() != PortMode.STORAGE, saved,
                () -> link.wake(task));
    }

    /** The redstone mode and last reading. */
    public RedstoneControl redstone() {
        return redstone;
    }

    /** A neighbour of the port (the block, or the cable a part sits on) changed: reads the redstone signal again. */
    public void redstoneChanged() {
        ServerLevel current = level;
        BlockPos at = pos;
        if (current != null && at != null) {
            redstone.neighbourChanged(current, at);
        }
    }

    public PortTier tier() {
        return tier;
    }

    /** Items per second now: the tier's rate with the speed cards that count. */
    public long itemsPerSecond() {
        return PortSpeed.rate(tier.itemsPerSecond(), cards.active(),
                StorageServerConfig.PORT_SPEED_CARD_MULTIPLIER.getAsDouble());
    }

    /** mB of fluid per second now: the tier's rate with the speed cards that count. */
    public long fluidMbPerSecond() {
        return PortSpeed.rate(tier.fluidMbPerSecond(), cards.active(),
                StorageServerConfig.PORT_SPEED_CARD_MULTIPLIER.getAsDouble());
    }

    /** Operations per second the port asks at most: one per step of items and one per step of fluid. */
    public int operationsPerSecond() {
        if (mode.get() == PortMode.STORAGE) {
            // A storage bus asks for nothing itself; its moves are its callers' operations.
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE,
                PortSpeed.operations(itemsPerSecond(), StorageServerConfig.BATCH_ITEMS.getAsInt())
                        + PortSpeed.operations(fluidMbPerSecond(), StorageServerConfig.BATCH_FLUID_MB.getAsInt()));
    }

    /** The ghost filter; changing a slot saves the port and wakes it. */
    public PortFilter filter() {
        return filter;
    }

    /** Crafts the crafting card requested since the port was made; tests read it. */
    public int cardRequests() {
        return cardRequests;
    }

    /** The speed cards; changing a slot saves the port and wakes it. */
    public PortCards cards() {
        return cards;
    }

    Owned owner() {
        return owner;
    }

    /**
     * Whether the port waits for its tier's research: true once a run found the owner's team without it, until a run
     * finds it.
     */
    public boolean waitsForResearch() {
        return researchChecked && !researched;
    }

    /** Whether a land claim keeps the port from the inventory it faces, at the last check. */
    public boolean claimBlocked() {
        return access.blocked();
    }

    /** The port's claim check; tests read how often it asked. */
    public FacingAccess access() {
        return access;
    }

    /** Whether the port's holder is gone; {@link PortResearchWaits} drops gone ports. */
    boolean isGone() {
        return !loaded.getAsBoolean();
    }

    /** A node completed for the owner's team: the next run looks at the research again. */
    void researchMayHaveChanged() {
        link.wake(task);
    }

    /**
     * Starts work: caches the handlers of the block at {@code pos} + {@code facing} and schedules the task. Call when
     * the port loads or is placed; {@code loaded} says whether the port is still there.
     */
    public void start(ServerLevel level, BlockPos pos, Direction facing, BooleanSupplier loaded) {
        this.level = level;
        this.loaded = loaded;
        this.pos = pos.immutable();
        this.facing = facing;
        access.forget();
        facedKnown = false;
        busDirty = true;
        cadence.reset();
        BlockPos target = pos.relative(facing);
        items = Platform.get().transfer().items().cache(level, target, facing.getOpposite(), loaded,
                this::inventoryChanged);
        fluids = Platform.get().transfer().fluids().cache(level, target, facing.getOpposite(), loaded,
                this::inventoryChanged);
        facedChestFull = level.isLoaded(target) && level.getBlockEntity(target) instanceof StorageChestBlockEntity chest
                && chest.isFull();
        redstone.start(level, pos);
        link.schedule(task);
    }

    /**
     * Whether the port faces a storage chest with every slot in use. A storage chest is no network device, so a port
     * facing it is how logic's "drive or cell full" counts it (M7L): the chest tells the ports facing it when it turns
     * full or stops being full ({@link #facedChestChanged}), and the port reads it once when it starts.
     */
    public boolean facedChestFull() {
        return facedChestFull;
    }

    /** The faced storage chest turned full or stopped being full (or went away): the network's fill watchers look. */
    public void facedChestChanged(boolean full) {
        if (full == facedChestFull) {
            return;
        }
        facedChestFull = full;
        NetworkState network = link.network();
        if (network != null) {
            network.fullCellsChanged();
        }
    }

    /** The faced inventory changed or was replaced, or the mode changed: there may be work again. */
    public void inventoryChanged() {
        access.targetChanged();
        // The faced block may have been replaced: a storage bus looks up the device there again.
        facedKnown = false;
        cadence.poke();
        if (!running) {
            link.wake(task);
        }
    }

    private void settingsChanged() {
        saved.run();
        busDirty = true;
        cadence.reset();
        link.wake(task);
    }

    /**
     * The port's mode changed from {@code before} to the one {@link #mode} gives now: entering or leaving storage mode
     * adds or drops {@link PortStorage} in the network's index, and the next look starts from nothing.
     */
    public void modeChanged(PortMode before) {
        PortMode after = mode.get();
        if (before == after) {
            return;
        }
        if (before == PortMode.STORAGE || after == PortMode.STORAGE) {
            link.providersChanged();
            if (before == PortMode.STORAGE && bus.idle()) {
                // The network dropped the provider and took off what it was told; start clean next time.
                bus.forget();
            }
        }
        busDirty = true;
        cadence.reset();
        // Pulse mode counts only outside storage mode.
        redstone.refresh();
        inventoryChanged();
    }

    /**
     * Writes the filter with its entries' settings, the speed cards and, when not the defaults, the export order and
     * the storage access and priority.
     */
    public void save(ValueOutput output) {
        ContainerHelper.saveAllItems(output.child(FILTER), filter.getItems());
        List<PortEntrySettings> entries = filter.settings();
        if (!entries.isEmpty()) {
            output.store(ENTRIES, PortEntrySettings.LIST_CODEC, entries);
        }
        if (order != PortOrder.FILTER) {
            output.putString(ORDER, order.getSerializedName());
        }
        ContainerHelper.saveAllItems(output.child(CARDS), cards.getItems());
        if (busAccess != BusAccess.BOTH) {
            output.putString(ACCESS, busAccess.getSerializedName());
        }
        if (priority != 0) {
            output.putInt(PRIORITY, priority);
        }
        redstone.save(output);
    }

    /** Reads the filter and the cards straight into the slots: loading must not save or wake. */
    public void load(ValueInput input) {
        filter.getItems().clear();
        input.child(FILTER).ifPresent(child -> ContainerHelper.loadAllItems(child, filter.getItems()));
        // Ports saved before the entry settings existed have none: every slot loads as exact.
        filter.loadSettings(input.read(ENTRIES, PortEntrySettings.LIST_CODEC).orElse(List.of()));
        filter.rebuild();
        order = PortOrder.byName(input.getStringOr(ORDER, ""));
        cards.getItems().clear();
        input.child(CARDS).ifPresent(child -> ContainerHelper.loadAllItems(child, cards.getItems()));
        cards.recount();
        busAccess = BusAccess.byName(input.getStringOr(ACCESS, ""));
        priority = PriorityControl.clamp(input.getIntOr(PRIORITY, 0));
        redstone.load(input);
    }

    /** Which key an export tries first (B09 item 18). */
    public PortOrder order() {
        return order;
    }

    /** Sets the export order; saves and wakes. Server side. */
    public void setOrder(PortOrder next) {
        if (next == order) {
            return;
        }
        order = next;
        itemTurn = 0;
        fluidTurn = 0;
        settingsChanged();
    }

    /** Takes the export order from a placed item, without saving or waking (the port is not running). */
    void restoreOrder(PortOrder order) {
        this.order = order;
    }

    // --- Storage mode ------------------------------------------------------------------------------------------

    /** What the port adds to its network's storage: {@link PortStorage} in storage mode, else nothing. */
    public List<StorageProvider> providers() {
        return mode.get() == PortMode.STORAGE ? busProviders : List.of();
    }

    /** The port in storage mode as network storage; tests and Jade read it. */
    public PortStorage storage() {
        return bus;
    }

    /** What storage mode lets the network do with the inventory. */
    public BusAccess busAccess() {
        return busAccess;
    }

    /** Sets the storage access; saves, and the next look shows or hides the contents. Server side. */
    public void setBusAccess(BusAccess next) {
        if (next == busAccess) {
            return;
        }
        busAccess = next;
        settingsChanged();
    }

    /** Takes the storage access and priority from a placed item, without saving or waking (the port is not running). */
    void restoreBus(BusAccess access, int priority) {
        this.busAccess = access;
        this.priority = PriorityControl.clamp(priority);
    }

    /** The storage priority (whole int range, like a drive's). */
    public int priority() {
        return priority;
    }

    /** Sets the storage priority; in storage mode the network indexes the port again at it. Server side. */
    public void setPriority(int next) {
        int clamped = PriorityControl.clamp(next);
        if (clamped == priority) {
            return;
        }
        priority = clamped;
        saved.run();
        if (mode.get() == PortMode.STORAGE) {
            link.providersChanged();
        }
    }

    /** What storage mode found at its last look. */
    public BusStatus busStatus() {
        return busStatus;
    }

    /**
     * The port was turned off or on by logic or load shedding: in storage mode an off port shows nothing (its contents
     * leave the index at once), and on again it looks at once.
     */
    public void stoppedChanged(boolean stopped) {
        if (mode.get() != PortMode.STORAGE) {
            return;
        }
        busDirty = true;
        cadence.reset();
        if (stopped && bus.idle() && Transaction.getLifecycle() == Transaction.Lifecycle.NONE) {
            bus.refresh(null, null, false);
        }
        link.wake(task);
    }

    /**
     * The name of the block a storage bus mounts, for Jade and the analyzer; empty when the port faces nothing loaded.
     * Reads one block state; never loads a chunk.
     */
    public Optional<Component> mountedName() {
        ServerLevel current = level;
        BlockPos at = pos;
        if (current == null || at == null) {
            return Optional.empty();
        }
        BlockPos target = at.relative(facing);
        if (current.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(target.getX()),
                SectionPos.blockToSectionCoord(target.getZ())) == null) {
            return Optional.empty();
        }
        BlockState state = current.getBlockState(target);
        return state.isAir() ? Optional.empty() : Optional.of(state.getBlock().getName());
    }

    /**
     * One look of storage mode (see the class): checks the mount, then rebuilds the contents if the fingerprint, the
     * mount or the settings changed. Returns when to look again ({@link BusCadence}).
     */
    private int storageWork(long gameTime) {
        ServerLevel current = level;
        BlockPos at = pos;
        if (current == null || at == null) {
            return StorageTask.WAIT;
        }
        if (!bus.idle() || Transaction.getLifecycle() != Transaction.Lifecycle.NONE) {
            // Moves through the provider are still open; look once they settled.
            return StorageTask.NEXT_TICK;
        }
        int min = StorageServerConfig.STORAGE_BUS_MIN_SCAN_TICKS.getAsInt();
        int max = StorageServerConfig.STORAGE_BUS_MAX_SCAN_TICKS.getAsInt();
        int wait = cadence.delay(gameTime, min);
        if (wait > 0) {
            return wait;
        }
        cadence.scanned(gameTime);
        CachedBlockLookup<ResourceHandler<ItemResource>> itemLookup = items;
        CachedBlockLookup<ResourceHandler<FluidResource>> fluidLookup = fluids;
        ResourceHandler<ItemResource> itemHandler = itemLookup == null ? null : itemLookup.find();
        ResourceHandler<FluidResource> fluidHandler = fluidLookup == null ? null : fluidLookup.find();
        BusStatus status;
        if (itemHandler == null && fluidHandler == null) {
            status = BusStatus.NOTHING;
        } else if (!access.allowed(current, at, facing, owner.ownerRecord())) {
            status = BusStatus.BLOCKED;
        } else if (loops(current, at)) {
            status = BusStatus.LOOP;
        } else {
            status = BusStatus.MOUNTED;
        }
        boolean show = status == BusStatus.MOUNTED && busAccess.reads() && !link.stopped();
        long mark = show ? PortStorage.fingerprint(fluidHandler, PortStorage.fingerprint(itemHandler, 1)) : 0;
        if (!busDirty && status == busStatus && show == bus.visible() && mark == fingerprint) {
            return cadence.unchanged(min, max);
        }
        busDirty = false;
        busStatus = status;
        fingerprint = mark;
        boolean changed = bus.refresh(show ? itemHandler : null, show ? fluidHandler : null, show);
        return changed ? cadence.active(min) : cadence.unchanged(min, max);
    }

    /**
     * Whether mounting the faced block would loop: it is a Storage device (block or part) of this port's own network,
     * or of a network whose storage already reaches this one ({@link NetworkState#reaches}). A plain inventory never
     * loops. One block entity lookup after the faced block changed; otherwise reads cached references.
     */
    private boolean loops(ServerLevel current, BlockPos at) {
        NetworkDevice device = facedDevice(current, at);
        NetworkState own = link.network();
        NetworkState other = device == null ? null : device.deviceLink().network();
        return own != null && other != null && (other == own || other.reaches(own));
    }

    private @Nullable NetworkDevice facedDevice(ServerLevel current, BlockPos at) {
        NetworkDevice known = facedDevice;
        if (facedKnown && (known == null || !known.isRemoved())) {
            return known;
        }
        facedKnown = true;
        BlockPos target = at.relative(facing);
        NetworkDevice found = null;
        if (current.getBlockEntity(target) instanceof NetworkDevice device) {
            found = device;
        } else {
            CableParts parts = StorageCableBlock.parts(current, target);
            CablePart part = parts == null ? null : parts.part(facing.getOpposite());
            if (part != null) {
                found = part;
            }
        }
        facedDevice = found;
        return found;
    }

    /** How the port's storage reaches its port. */
    private final class BusHost implements PortStorage.Host {
        @Override
        public @Nullable ResourceHandler<ItemResource> items() {
            CachedBlockLookup<ResourceHandler<ItemResource>> lookup = items;
            return lookup == null ? null : lookup.find();
        }

        @Override
        public @Nullable ResourceHandler<FluidResource> fluids() {
            CachedBlockLookup<ResourceHandler<FluidResource>> lookup = fluids;
            return lookup == null ? null : lookup.find();
        }

        @Override
        public boolean usable() {
            return loaded.getAsBoolean() && busStatus == BusStatus.MOUNTED && mode.get() == PortMode.STORAGE
                    && !link.stopped() && !access.blocked();
        }

        @Override
        public BusAccess access() {
            return busAccess;
        }

        @Override
        public PortFilter filter() {
            return filter;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public @Nullable NetworkState mountedNetwork() {
            NetworkDevice device = facedDevice;
            return busStatus != BusStatus.MOUNTED || device == null || device.isRemoved()
                    ? null
                    : device.deviceLink().network();
        }

        @Override
        public void moved() {
            cadence.poke();
            if (!running) {
                link.wake(task);
            }
        }
    }

    /**
     * Whether the owner's team has the tier's research. Checked until it has once: research is not taken back, so a
     * port whose owner later joins a team without it keeps working rather than paying the check on every run. A port
     * without it joins {@link PortResearchWaits}.
     */
    private boolean researchReady() {
        Identifier gate = tier.research();
        ServerLevel current = level;
        if (gate == null || researched || current == null) {
            return true;
        }
        researched = ResearchUnlocks.isUnlocked(current, owner.ownerRecord(), gate);
        researchChecked = true;
        PortResearchWaits.waiting(this, !researched);
        return researched;
    }

    /**
     * One run: items, then fluids, each moving as many full steps of its rate as have built up. Returns the sooner of
     * the kinds' next steps, the network's wait after a refused operation, {@link StorageTask#WAIT} without the tier's
     * research, or {@link StorageTask#IDLE} when neither kind found anything to move.
     */
    int work(StorageTransfer transfer, long gameTime) {
        // Read once per run: the mode of a block port is a block state property, and a run does not change it.
        PortMode current = mode.get();
        if (current == PortMode.STORAGE) {
            return storageWork(gameTime);
        }
        if (!researchReady()) {
            return StorageTask.WAIT;
        }
        boolean importing = current == PortMode.IMPORT;
        ServerLevel world = level;
        BlockPos at = pos;
        if (world != null && at != null && !access.allowed(world, at, facing, owner.ownerRecord())) {
            return access.retryTicks(world);
        }
        if (!transfer.canOperate()) {
            return Math.max(2, transfer.retryTicks());
        }
        if (redstone.pulsing()) {
            return pulseWork(transfer, gameTime, importing);
        }
        running = true;
        now = gameTime;
        cardRetryAt = 0;
        try {
            int next = Integer.MAX_VALUE;
            CachedBlockLookup<ResourceHandler<ItemResource>> itemLookup = items;
            ResourceHandler<ItemResource> itemHandler = itemLookup == null ? null : itemLookup.find();
            if (itemHandler != null) {
                next = Math.min(next, kind(itemHandler, itemRate, itemsPerSecond(),
                        transfer.batchLimit(StackKey.Kind.ITEM), true, importing, transfer, gameTime));
            }
            CachedBlockLookup<ResourceHandler<FluidResource>> fluidLookup = fluids;
            ResourceHandler<FluidResource> fluidHandler = fluidLookup == null ? null : fluidLookup.find();
            if (fluidHandler != null && next != REFUSED) {
                next = Math.min(next, kind(fluidHandler, fluidRate, fluidMbPerSecond(),
                        transfer.batchLimit(StackKey.Kind.FLUID), false, importing, transfer, gameTime));
            }
            if (next == REFUSED) {
                return Math.max(2, transfer.retryTicks());
            }
            if (cardRetryAt > gameTime) {
                next = (int) Math.min(next, Math.max(2, cardRetryAt - gameTime));
            }
            return next == Integer.MAX_VALUE ? StorageTask.IDLE : next;
        } finally {
            running = false;
        }
    }

    /**
     * Pulse mode (B09 item 9): one operation per counted rising edge for each kind, of one step (the rate per second,
     * at most one batch), still paid from the rate's credit, so a fast clock cannot beat the port's rate. Edges the
     * network refuses are dropped. Sleeps until the next edge wakes it.
     */
    private int pulseWork(StorageTransfer transfer, long gameTime, boolean importing) {
        int pulses = redstone.takePulses();
        if (pulses == 0) {
            return StorageTask.WAIT;
        }
        running = true;
        now = gameTime;
        try {
            CachedBlockLookup<ResourceHandler<ItemResource>> itemLookup = items;
            ResourceHandler<ItemResource> itemHandler = itemLookup == null ? null : itemLookup.find();
            CachedBlockLookup<ResourceHandler<FluidResource>> fluidLookup = fluids;
            ResourceHandler<FluidResource> fluidHandler = fluidLookup == null ? null : fluidLookup.find();
            for (int i = 0; i < pulses && transfer.canOperate(); i++) {
                if (itemHandler != null) {
                    pulseKind(itemHandler, itemRate, itemsPerSecond(), transfer.batchLimit(StackKey.Kind.ITEM), true,
                            importing, transfer, gameTime);
                }
                if (fluidHandler != null && transfer.canOperate()) {
                    pulseKind(fluidHandler, fluidRate, fluidMbPerSecond(), transfer.batchLimit(StackKey.Kind.FLUID),
                            false, importing, transfer, gameTime);
                }
            }
        } finally {
            running = false;
        }
        return StorageTask.WAIT;
    }

    /** One pulse's operation for one kind; returns what moved. */
    private <T extends Resource> long pulseKind(ResourceHandler<T> handler, PortRate rate, long perSecond, long batch,
            boolean item, boolean importing, StorageTransfer transfer, long gameTime) {
        long amount = Math.min(PortRate.step(perSecond, batch), rate.available(gameTime, perSecond));
        if (amount <= 0) {
            return 0;
        }
        int clamped = (int) Math.min(amount, Integer.MAX_VALUE);
        long moved = importing ? pull(handler, clamped, item, transfer) : push(handler, clamped, item, transfer);
        if (moved > 0) {
            rate.spend(moved);
        }
        return moved;
    }

    /** {@link #kind}'s answer when the network refused an operation. */
    private static final int REFUSED = Integer.MIN_VALUE;

    /**
     * One kind's part of a run: as many operations as full steps have built up, stopping at the first that moves
     * nothing. A fast port (tier 3 with cards moves more than one batch a tick) cannot wait for a run per step, since a
     * task runs at most once a tick. Returns the ticks until the next step when it moved something or is waiting for
     * its rate, {@link #REFUSED} when the network refused, else {@code Integer.MAX_VALUE} (nothing to move).
     */
    private <T extends Resource> int kind(ResourceHandler<T> handler, PortRate rate, long perSecond, long batch,
            boolean item, boolean importing, StorageTransfer transfer, long gameTime) {
        long step = PortRate.step(perSecond, batch);
        long available = rate.available(gameTime, perSecond);
        if (available < step) {
            return rate.ticksUntil(step, perSecond);
        }
        boolean movedAny = false;
        // The credit holds at most one second's worth, so this loop runs at most once per step of a second.
        while (available >= step) {
            int amount = (int) Math.min(Math.min(available, batch), Integer.MAX_VALUE);
            long moved = importing ? pull(handler, amount, item, transfer) : push(handler, amount, item, transfer);
            if (moved <= 0) {
                break;
            }
            movedAny = true;
            rate.spend(moved);
            available = rate.available(gameTime, perSecond);
        }
        if (movedAny) {
            return rate.ticksUntil(step, perSecond);
        }
        return transfer.canOperate() ? Integer.MAX_VALUE : REFUSED;
    }

    /**
     * One import operation. Items: up to {@code amount} from as many indices as it takes, whatever their kinds, as one
     * operation (M7E decision 4), so mixed loot pays per batch rather than per slot. Fluids: from the first index
     * holding something the filter accepts.
     */
    private <T extends Resource> long pull(ResourceHandler<T> handler, int amount, boolean item,
            StorageTransfer transfer) {
        if (item) {
            long moved = PortTransfers.pullMany(handler, itemIndex, filter, amount, StackKey.Kind.ITEM, transfer,
                    itemMoves);
            if (moved > 0) {
                itemIndex = itemMoves.lastIndex();
            }
            return moved;
        }
        int size = handler.size();
        int start = fluidIndex;
        for (int n = 0; n < size; n++) {
            int index = start + n < size ? start + n : start + n - size;
            T resource = handler.getResource(index);
            if (resource.isEmpty() || handler.getAmountAsLong(index) <= 0 || !filter.accepts(resource)) {
                continue;
            }
            long moved = PortTransfers.pull(handler, index, resource, amount, transfer);
            if (moved > 0) {
                fluidIndex = index;
                return moved;
            }
            if (!transfer.canOperate()) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * One export operation over the kind's export list ({@link PortExports}: exact entries, and the network's keys a
     * fuzzy, tag or mod entry matches), from the first key in filter order or, in round robin, after the key the last
     * operation moved. An entry with a keep amount moves only while the inventory holds less than it of what the entry
     * matches, counted through the cached handler now (the regulator, B09 item 22). Items: up to {@code amount} of as
     * many keys as the network holds and the inventory has room for, as one operation (M7E follow-up). Fluids: the
     * first key the network holds and the inventory has room for. Exact keys the network lacks go to the crafting card
     * first. At most {@code ports.filter.exportKeysPerOperation} keys the network holds and the inventory may take are
     * tried per operation; keys that cannot fit do not count, up to {@link PortTransfers#LOOKS_PER_TRY} times as many
     * looks.
     */
    @SuppressWarnings("unchecked") // the list of a kind holds resources of the handler's kind only
    private <T extends Resource> long push(ResourceHandler<T> handler, int amount, boolean item,
            StorageTransfer transfer) {
        NetworkState network = transfer.network();
        PortExports list = item ? itemExports : fluidExports;
        list.refresh(filter, network);
        int size = list.size();
        if (size == 0 || !PortTransfers.anyRoom(handler) || !room(handler, list.filterEntries())) {
            return 0;
        }
        PortFilter.Entry[] entries = list.filterEntries();
        for (int e = 0; e < entries.length; e++) {
            PortFilter.Entry entry = entries[e];
            if (entry.mode == MatchMode.EXACT && room[e] > 0 && network.contents().amount(entry.key) <= 0) {
                requestCraft(transfer, entry.key);
            }
        }
        int tries = StorageServerConfig.PORT_EXPORT_KEYS_PER_OPERATION.getAsInt();
        boolean roundRobin = order == PortOrder.ROUND_ROBIN;
        int start = roundRobin ? (item ? itemTurn : fluidTurn) : 0;
        if (item) {
            long moved = PortTransfers.pushMany(handler, list, start, room, tries, amount, StackKey.Kind.ITEM, transfer,
                    exportMoves);
            if (moved > 0 && roundRobin) {
                itemTurn = (exportMoves.lastIndex() + 1) % size;
            }
            return moved;
        }
        int tried = 0;
        int looks = 0;
        int maxLooks = PortTransfers.looksFor(tries);
        for (int n = 0; n < size && tried < tries && looks < maxLooks; n++) {
            int i = (start + n) % size;
            long allowed = Math.min(amount, room[list.entry(i)]);
            StackKey key = list.key(i);
            if (allowed <= 0 || network.contents().amount(key) <= 0) {
                continue;
            }
            looks++;
            if (!PortTransfers.mayFit(handler, (T) list.resource(i))) {
                continue;
            }
            tried++;
            long moved = PortTransfers.push(handler, key, (T) list.resource(i), (int) allowed, transfer);
            if (moved > 0) {
                if (roundRobin) {
                    fluidTurn = (i + 1) % size;
                }
                return moved;
            }
            if (!transfer.canOperate()) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Works out what each entry may still move into {@code handler} this operation: without a keep amount anything,
     * else what the keep amount leaves after what the inventory holds that the entry matches. Reads the cached handler
     * once per entry with a keep amount; allocates nothing. Returns whether any entry may move something.
     */
    private <T extends Resource> boolean room(ResourceHandler<T> handler, PortFilter.Entry[] entries) {
        boolean any = false;
        for (int e = 0; e < entries.length; e++) {
            PortFilter.Entry entry = entries[e];
            long left = Long.MAX_VALUE;
            if (entry.keep > 0) {
                left = Math.max(0, entry.keep - matched(handler, entry));
            }
            room[e] = left;
            any |= left > 0;
        }
        return any;
    }

    /** How much of what {@code entry} matches {@code handler} holds, summed over its indices. */
    private static <T extends Resource> long matched(ResourceHandler<T> handler, PortFilter.Entry entry) {
        long total = 0;
        int size = handler.size();
        for (int index = 0; index < size; index++) {
            T resource = handler.getResource(index);
            if (!resource.isEmpty() && entry.matches(resource)) {
                total += handler.getAmountAsLong(index);
            }
        }
        return total;
    }

    /**
     * The crafting card (M7 C21): the network ran out of {@code key}, so ask for a craft of up to
     * {@code crafting.cardMaxRequest} as a background job. One request out at a time, at most one per
     * {@code crafting.cardIntervalTicks}, only with the owner's team's {@link ResearchGates#CRAFTING_CARD}. A refused
     * request still counts for the interval, so a key without a pattern costs one check per interval. A request held
     * back by the interval or refused makes the run come back once the interval is over ({@link #cardRetryAt}).
     */
    private void requestCraft(StorageTransfer transfer, StackKey key) {
        ServerLevel current = level;
        if (cardRequestOut || current == null || !cards.craftingCard()) {
            return;
        }
        if (now < nextCardRequest) {
            cardRetryAt = nextCardRequest;
            return;
        }
        nextCardRequest = now + StorageServerConfig.CRAFTING_CARD_INTERVAL_TICKS.getAsInt();
        if (!ResearchUnlocks.isUnlocked(current, owner.ownerRecord(), ResearchGates.CRAFTING_CARD)) {
            return;
        }
        BackgroundCrafting.Request request = new BackgroundCrafting.Request(key,
                StorageServerConfig.CRAFTING_CARD_MAX_REQUEST.getAsInt(), 0, 0, owner.ownerRecord());
        cardRequestOut = true;
        if (BackgroundCrafts.get().submit(transfer.network(), request, this::cardRequestEnded)) {
            cardRequests++;
        } else {
            cardRequestOut = false;
            cardRetryAt = nextCardRequest;
        }
    }

    private void cardRequestEnded(BackgroundCrafting.Outcome outcome) {
        cardRequestOut = false;
        if (loaded.getAsBoolean()) {
            link.wake(task);
        }
    }

    /**
     * A logic graph's move through this port (M7L, {@link dev.silencebound.maxtech.storage.network.LogicMover}): up to
     * {@code amount} of {@code key} from the network into the faced inventory ({@code intoDevice}) or from the faced
     * inventory into the network, whatever the port's mode and filter (the graph names the key), and only where its
     * land claim check allows. Each operation is one {@link PortTransfers} transaction on the port's own transfer, at
     * most one batch, and the total is capped by the port's rate credit, which the port's own work shares, so logic
     * can't move faster than the port. Returns what moved; 0 while the port is off, waits for research, faces nothing,
     * or the network refuses.
     */
    public long logicMove(StackKey key, long amount, boolean intoDevice, long gameTime) {
        StorageTransfer transfer = link.transfer();
        if (transfer == null || amount <= 0 || link.stopped() || !researchReady()) {
            return 0;
        }
        ServerLevel current = level;
        BlockPos at = pos;
        if (current == null || at == null || !access.allowed(current, at, facing, owner.ownerRecord())) {
            // A land claim keeps the port from the inventory: logic can't reach past it either.
            return 0;
        }
        boolean item = key.kind() == StackKey.Kind.ITEM;
        PortRate rate = item ? itemRate : fluidRate;
        long perSecond = item ? itemsPerSecond() : fluidMbPerSecond();
        long budget = Math.min(amount, rate.available(gameTime, perSecond));
        if (budget <= 0) {
            return 0;
        }
        long batch = transfer.batchLimit(key.kind());
        running = true;
        try {
            if (item) {
                CachedBlockLookup<ResourceHandler<ItemResource>> lookup = items;
                ResourceHandler<ItemResource> handler = lookup == null ? null : lookup.find();
                return handler == null
                        ? 0
                        : logicSteps(handler, key, (ItemResource) StackKeys.toResource(key), budget, batch, intoDevice,
                                rate, transfer);
            }
            CachedBlockLookup<ResourceHandler<FluidResource>> lookup = fluids;
            ResourceHandler<FluidResource> handler = lookup == null ? null : lookup.find();
            return handler == null
                    ? 0
                    : logicSteps(handler, key, (FluidResource) StackKeys.toResource(key), budget, batch, intoDevice,
                            rate, transfer);
        } finally {
            running = false;
        }
    }

    /** The block the port faces as {@link BlockPos#asLong()}; {@link Long#MIN_VALUE} before it is placed. */
    public long facedPos() {
        BlockPos at = pos;
        return at == null ? Long.MIN_VALUE : BlockPos.offset(at.asLong(), facing);
    }

    /**
     * How much of {@code key} the faced inventory holds, summed over its slots or tanks; 0 when it faces nothing that
     * holds the key or a land claim keeps the port from it. For logic's "least full" pool strategy, read when a send is
     * planned.
     */
    public long facedAmount(StackKey key) {
        ServerLevel current = level;
        BlockPos at = pos;
        if (current == null || at == null || !access.allowed(current, at, facing, owner.ownerRecord())) {
            return 0;
        }
        if (key.kind() == StackKey.Kind.ITEM) {
            CachedBlockLookup<ResourceHandler<ItemResource>> lookup = items;
            ResourceHandler<ItemResource> handler = lookup == null ? null : lookup.find();
            return handler == null ? 0 : held(handler, (ItemResource) StackKeys.toResource(key));
        }
        CachedBlockLookup<ResourceHandler<FluidResource>> lookup = fluids;
        ResourceHandler<FluidResource> handler = lookup == null ? null : lookup.find();
        return handler == null ? 0 : held(handler, (FluidResource) StackKeys.toResource(key));
    }

    private static <T extends Resource> long held(ResourceHandler<T> handler, T resource) {
        long total = 0;
        for (int index = 0; index < handler.size(); index++) {
            if (resource.equals(handler.getResource(index))) {
                total += handler.getAmountAsLong(index);
            }
        }
        return total;
    }

    private static <T extends Resource> long logicSteps(ResourceHandler<T> handler, StackKey key, T resource,
            long budget, long batch, boolean intoDevice, PortRate rate, StorageTransfer transfer) {
        long moved = 0;
        while (moved < budget) {
            int step = (int) Math.min(Math.min(budget - moved, Math.max(1, batch)), Integer.MAX_VALUE);
            long done = intoDevice
                    ? PortTransfers.push(handler, key, resource, step, transfer)
                    : pullKey(handler, resource, step, transfer);
            if (done <= 0) {
                break;
            }
            moved += done;
            rate.spend(done);
        }
        return moved;
    }

    /**
     * One operation taking up to {@code amount} of {@code resource} out of the first index of {@code source} that has
     * it.
     */
    private static <T extends Resource> long pullKey(ResourceHandler<T> source, T resource, int amount,
            StorageTransfer transfer) {
        for (int index = 0; index < source.size(); index++) {
            if (!resource.equals(source.getResource(index)) || source.getAmountAsLong(index) <= 0) {
                continue;
            }
            long moved = PortTransfers.pull(source, index, resource, amount, transfer);
            if (moved > 0 || !transfer.canOperate()) {
                return moved;
            }
        }
        return 0;
    }

    /** The port's work on the network's scheduler; one per port, scheduled when it starts. */
    private final class Task implements StorageTask {
        @Override
        public int run(StorageTransfer transfer, long gameTime) {
            return work(transfer, gameTime);
        }
    }
}
