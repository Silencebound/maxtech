package dev.silencebound.maxtech.storage.p2p;

import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.energy.FeLookups;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.api.energy.EdgeSettings;
import dev.silencebound.maxtech.lib.platform.BlockLookup;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.energy.FeView;
import dev.silencebound.maxtech.storage.network.FacingAccess;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: P2P links hand items, fluid and FE through NeoForge transfer handlers in the caller's transaction
// (PRD 7.2: every transfer is a transaction against the real handlers).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.resource.Resource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * One end of a P2P link (PRD 7.2, Devices: P2P tunnels in basic form), shared by the block and the part. An end has a
 * kind, a role and a frequency; the one input of a frequency hands what it receives to that frequency's outputs in the
 * same network, and each output gives it to the block it faces.
 *
 * <ul>
 * <li>Items and fluid: the input offers a handler on its face that only takes inserts. Each output that has room costs
 * one network operation between the two ends ({@link NetworkState#operateBetween}: energy), inside the inserter's
 * transaction, so a rollback moves and costs nothing. Outputs are tried in turn, starting one further each call.</li>
 * <li>Energy: the input offers an FE handler that only takes FE, as MU at Lib's rate (MLE item 4); outputs get it minus
 * {@code p2p.energyLossPercent} per cent, as MU into Storage's blocks and as FE into other mods' under Lib's export
 * settings. No network energy: the network only joins the ends.</li>
 * <li>Redstone: the input reads the signal of the block it faces whenever a neighbour changes; each output gives that
 * signal to the block it faces and tells its neighbours when it changes. Nothing ticks.</li>
 * </ul>
 * Item, fluid and FE outputs give nothing to a block a land claim keeps their owner from ({@link FacingAccess}). Both
 * ends must be in one network and their owners' teams must have {@link ResearchGates#P2P_LINKS}. A transfer that
 * reaches its own channel again (an output feeding its input) gets nothing. Calls from other threads move nothing.
 * Server thread.
 */
public final class P2PEndpoint {
    private static final String LINK = "p2p";
    /** Ends that found their owner's team without the unlock; research completing looks at them again. */
    private static final ReferenceOpenHashSet<P2PEndpoint> LOCKED = new ReferenceOpenHashSet<>();
    private static final TargetLookup<ItemResource> ITEMS = P2PEndpoint::itemTarget;
    private static final TargetLookup<FluidResource> FLUIDS = P2PEndpoint::fluidTarget;

    private final P2PKind kind;
    private final P2PDevice device;
    private final Runnable saved;
    private final Runnable signalOut;
    private final ItemInput itemInput = new ItemInput();
    private final FluidInput fluidInput = new FluidInput();
    private final EnergyInput muInput = new EnergyInput();
    /** What other mods see of the energy input: FE into {@link #muInput} at Lib's rate. */
    private final FeView energyInput = FeView.input(muInput, P2PEndpoint::nothing);
    private final FacingAccess access = new FacingAccess();
    private final Runnable targetReplaced = access::targetChanged;
    /** Bumped by every start, so the caches of an earlier start stop telling the claim check. */
    private int generation;
    private P2PSettings settings = P2PSettings.NONE;
    private @Nullable ServerLevel level;
    private Direction face = Direction.NORTH;
    private @Nullable BlockPos pos;
    private @Nullable CachedBlockLookup<ResourceHandler<ItemResource>> items;
    private @Nullable CachedBlockLookup<ResourceHandler<FluidResource>> fluids;
    private @Nullable CachedBlockLookup<EnergyHandler> energy;
    private boolean registered;
    /** An input refused because another loaded input holds its frequency. */
    private boolean conflict;
    private int inputSignal;
    private int outputSignal;
    private boolean unlocked;

    /** A functional view of which cached handler an output gives a transfer of one resource kind. */
    @FunctionalInterface
    private interface TargetLookup<T extends Resource> {
        @Nullable
        ResourceHandler<T> target(P2PEndpoint output);
    }

    /**
     * @param saved
     *            marks the end's holder for saving
     * @param signalOut
     *            tells the blocks around the end that its redstone output changed
     */
    public P2PEndpoint(P2PKind kind, P2PDevice device, Runnable saved, Runnable signalOut) {
        this.kind = kind;
        this.device = device;
        this.saved = saved;
        this.signalOut = signalOut;
    }

    /** Registers the research listener and the cleanup; once, during mod construction. */
    static void init() {
        Research.onCompleted((server, team, node) -> researchChanged());
        Platform.get().lifecycle().onServerStopped(server -> LOCKED.clear());
        P2PChannels.init();
    }

    public P2PKind kind() {
        return kind;
    }

    public P2PRole role() {
        return settings.role();
    }

    /** The frequency, 0 while not linked. */
    public long frequency() {
        return settings.frequency();
    }

    public P2PSettings settings() {
        return settings;
    }

    boolean isRemoved() {
        return device.isRemoved();
    }

    /** Whether this input was refused because another input holds its frequency. */
    public boolean isConflicting() {
        return conflict;
    }

    /**
     * Starts work in {@code level} for an end at {@code pos} facing {@code face}: caches the faced block's handlers,
     * reads its redstone and joins its channel. Call when the end is placed or loaded.
     */
    public void start(ServerLevel level, BlockPos pos, Direction face) {
        this.level = level;
        this.face = face;
        this.pos = pos.immutable();
        access.forget();
        BlockPos target = pos.relative(face);
        Direction side = face.getOpposite();
        // The caches tell the claim check when the faced block is replaced; a later start makes new ones.
        int current = ++generation;
        BooleanSupplier valid = () -> generation == current && !device.isRemoved();
        switch (kind) {
            case ITEM -> items = Platform.get().transfer().items().cache(level, target, side, valid, targetReplaced);
            case FLUID -> fluids = Platform.get().transfer().fluids().cache(level, target, side, valid, targetReplaced);
            case ENERGY -> energy = cacheEnergy(FeLookups.BLOCK, level, target, side, valid);
            case REDSTONE -> inputSignal = readSignal();
        }
        register();
        refreshSignal();
    }

    private CachedBlockLookup<EnergyHandler> cacheEnergy(BlockLookup<EnergyHandler> lookup, ServerLevel level,
            BlockPos pos, Direction side, BooleanSupplier valid) {
        return lookup.cache(level, pos, side, valid, targetReplaced);
    }

    /** Leaves the channel for good (broken) or until loaded again. */
    public void stop() {
        unregister();
        LOCKED.remove(this);
    }

    /** Sets the link (the linking tool), moving the end to its new channel. Server side. */
    public void link(P2PSettings next) {
        unregister();
        settings = next;
        saved.run();
        register();
        refreshSignal();
    }

    private void register() {
        ServerLevel current = level;
        conflict = false;
        if (current == null || !settings.isLinked() || registered) {
            return;
        }
        registered = true;
        conflict = !P2PChannels.add(current, this);
    }

    private void unregister() {
        ServerLevel current = level;
        if (current != null && registered) {
            registered = false;
            P2PChannels.remove(current, this, settings.frequency());
        }
    }

    /** The end joined or left a network: what it can reach changed. */
    public void networkChanged() {
        if (settings.role() == P2PRole.INPUT) {
            refreshChannel();
        } else {
            refreshSignal();
        }
    }

    /** A neighbour changed: an input of redstone reads the faced block's signal again. */
    public void neighbourChanged() {
        if (kind != P2PKind.REDSTONE || settings.role() != P2PRole.INPUT || level == null) {
            return;
        }
        int signal = readSignal();
        if (signal != inputSignal) {
            inputSignal = signal;
            refreshChannel();
        }
    }

    private void refreshChannel() {
        ServerLevel current = level;
        P2PChannels.Channel channel = current == null ? null : P2PChannels.get(current, settings.frequency());
        if (channel != null && settings.isLinked()) {
            P2PChannels.refreshOutputs(channel);
        }
    }

    private int readSignal() {
        ServerLevel current = level;
        if (current == null || device.isRemoved()) {
            return 0;
        }
        BlockPos pos = BlockPos.of(device.nodePos()).relative(face);
        return current.getSignal(pos, face);
    }

    /** The redstone signal (0 to 15) this end gives the block it faces: an output's mirrored input, else 0. */
    public int outputSignal() {
        return outputSignal;
    }

    /** The signal this end's input reads now; for the info lines and tests. */
    public int inputSignal() {
        return inputSignal;
    }

    /** An output works its redstone signal out again and tells its neighbours when it changed. */
    void refreshSignal() {
        int signal = 0;
        if (kind == P2PKind.REDSTONE && settings.role() == P2PRole.OUTPUT) {
            P2PEndpoint input = connectedInput();
            signal = input == null ? 0 : input.inputSignal;
        }
        if (signal != outputSignal) {
            outputSignal = signal;
            signalOut.run();
        }
    }

    /**
     * The input this output is connected to now: the channel's input, in the same network as this end, both owners'
     * teams having the unlock; null otherwise.
     */
    private @Nullable P2PEndpoint connectedInput() {
        ServerLevel current = level;
        if (current == null || !settings.isLinked() || device.isRemoved()) {
            return null;
        }
        P2PChannels.Channel channel = P2PChannels.get(current, settings.frequency());
        P2PEndpoint input = channel == null ? null : channel.input();
        if (input == null || !StorageModules.p2p(kind).isEnabled() || !sameNetwork(input) || !unlocked()
                || !input.unlocked()) {
            return null;
        }
        return input;
    }

    private boolean sameNetwork(P2PEndpoint other) {
        NetworkState network = device.deviceLink().network();
        return network != null && network == other.device.deviceLink().network();
    }

    /** Whether the owner's team has the unlock; cached once true, else remembered for research to wake. */
    boolean unlocked() {
        if (unlocked) {
            return true;
        }
        ServerLevel current = level;
        if (current == null) {
            return false;
        }
        unlocked = ResearchUnlocks.isUnlocked(current, device.ownerRecord(), ResearchGates.P2P_LINKS);
        if (!unlocked) {
            LOCKED.add(this);
        }
        return unlocked;
    }

    private static void researchChanged() {
        if (LOCKED.isEmpty()) {
            return;
        }
        // Copied: a refresh may register an end again.
        P2PEndpoint[] ends = LOCKED.toArray(new P2PEndpoint[0]);
        LOCKED.clear();
        for (P2PEndpoint end : ends) {
            if (!end.isRemoved()) {
                end.networkChanged();
            }
        }
    }

    /**
     * Whether no land claim keeps this output's owner from the block it faces ({@link FacingAccess}); an output that is
     * refused hands nothing on until a later check allows it.
     */
    private boolean targetAllowed() {
        ServerLevel current = level;
        BlockPos at = pos;
        return current != null && at != null && access.allowed(current, at, face, device.ownerRecord());
    }

    /** Whether a land claim keeps this end from the block it faces, at the last check. */
    public boolean claimBlocked() {
        return access.blocked();
    }

    private boolean onServerThread() {
        ServerLevel current = level;
        return current != null && current.getServer().isSameThread();
    }

    /** The item handler offered on {@code side}: an item input's on its face; null otherwise. */
    public @Nullable ResourceHandler<ItemResource> itemHandler(@Nullable Direction side) {
        return kind == P2PKind.ITEM && settings.role() == P2PRole.INPUT && side == face ? itemInput : null;
    }

    /** The fluid handler offered on {@code side}: a fluid input's on its face; null otherwise. */
    public @Nullable ResourceHandler<FluidResource> fluidHandler(@Nullable Direction side) {
        return kind == P2PKind.FLUID && settings.role() == P2PRole.INPUT && side == face ? fluidInput : null;
    }

    /** The FE handler offered on {@code side}: an energy input's on its face; null otherwise. */
    public @Nullable EnergyHandler energyHandler(@Nullable Direction side) {
        return kind == P2PKind.ENERGY && settings.role() == P2PRole.INPUT && side == face ? energyInput : null;
    }

    private @Nullable ResourceHandler<ItemResource> itemTarget() {
        CachedBlockLookup<ResourceHandler<ItemResource>> cache = items;
        return cache == null ? null : cache.find();
    }

    private @Nullable ResourceHandler<FluidResource> fluidTarget() {
        CachedBlockLookup<ResourceHandler<FluidResource>> cache = fluids;
        return cache == null ? null : cache.find();
    }

    private @Nullable EnergyHandler energyTarget() {
        CachedBlockLookup<EnergyHandler> cache = energy;
        return cache == null ? null : cache.find();
    }

    /** The channel an input transfer may use now, or null. */
    private P2PChannels.@Nullable Channel channelForTransfer() {
        ServerLevel current = level;
        if (current == null || !onServerThread() || device.isRemoved() || settings.role() != P2PRole.INPUT || conflict
                || !StorageModules.p2p(kind).isEnabled() || !unlocked()) {
            return null;
        }
        P2PChannels.Channel channel = P2PChannels.get(current, settings.frequency());
        if (channel == null || channel.busy || channel.input() != this) {
            return null;
        }
        channel.purge();
        return channel.outputs.isEmpty() ? null : channel;
    }

    /**
     * Hands up to {@code amount} of {@code resource} to the outputs, each with room costing one operation between the
     * ends, in {@code transaction}. Returns what went out.
     */
    private <T extends Resource> int transfer(T resource, int amount, StackKey.Kind keyKind, TargetLookup<T> lookup,
            TransactionContext transaction) {
        P2PChannels.Channel channel = channelForTransfer();
        NetworkState network = device.deviceLink().network();
        if (channel == null || network == null) {
            return 0;
        }
        channel.busy = true;
        try {
            int moved = 0;
            int count = channel.outputs.size();
            int start = Math.floorMod(channel.next++, count);
            for (int n = 0; n < count && moved < amount; n++) {
                P2PEndpoint output = channel.outputs.get((start + n) % count);
                ResourceHandler<T> target = output.device.isRemoved() || !output.sameNetwork(this) || !output.unlocked()
                        || !output.targetAllowed() ? null : lookup.target(output);
                if (target == null) {
                    continue;
                }
                try (Transaction attempt = Transaction.open(transaction)) {
                    int room;
                    try (Transaction simulation = Transaction.open(attempt)) {
                        room = ResourceHandlerUtil.insertStacking(target, resource, amount - moved, simulation);
                    }
                    if (room <= 0) {
                        continue;
                    }
                    long paid = network.operateBetween(device, output.device, keyKind, room, attempt);
                    if (paid <= 0) {
                        continue;
                    }
                    int put = ResourceHandlerUtil.insertStacking(target, resource, (int) paid, attempt);
                    if (put != paid) {
                        continue;
                    }
                    attempt.commit();
                    moved += put;
                }
            }
            return moved;
        } finally {
            channel.busy = false;
        }
    }

    private static void nothing() {
    }

    /**
     * MU handed to the outputs, less the configured loss; returns what the input took. A Storage block behind an output
     * takes MU as they are; another mod's block gets FE at Lib's rate, rounded down, only while export is on and at
     * most the per-edge limit per transfer. Rounding is paid by the input, so nothing is created.
     */
    private int transferEnergy(int amount, TransactionContext transaction) {
        P2PChannels.Channel channel = channelForTransfer();
        if (channel == null) {
            return 0;
        }
        int keep = 100 - StorageServerConfig.P2P_ENERGY_LOSS_PERCENT.getAsInt();
        EdgeSettings export = ServerConfig.edgeSettings();
        FeRate rate = export.rate();
        channel.busy = true;
        try {
            int taken = 0;
            int count = channel.outputs.size();
            int start = Math.floorMod(channel.next++, count);
            for (int n = 0; n < count && taken < amount; n++) {
                P2PEndpoint output = channel.outputs.get((start + n) % count);
                EnergyHandler target = output.device.isRemoved() || !output.sameNetwork(this) || !output.unlocked()
                        || !output.targetAllowed() ? null : output.energyTarget();
                int offer = (int) ((long) (amount - taken) * keep / 100);
                if (target == null || offer <= 0) {
                    continue;
                }
                long put;
                if (target instanceof FeView view) {
                    put = view.store().insert(offer, transaction);
                } else if (export.exportEnabled()) {
                    long fe = rate.toFe(Math.min(offer, export.edgeLimit()));
                    int given = fe <= 0 ? 0 : target.insert((int) Math.min(Integer.MAX_VALUE, fe), transaction);
                    // The MU those FE are worth, rounded up: the input pays for the rounding.
                    put = rate.muCovering(rate.unitsOfFe(given));
                } else {
                    put = 0;
                }
                // What the input pays for what arrived, rounded up, never more than it had left.
                taken += (int) Math.min(amount - taken, ((long) put * 100 + keep - 1) / keep);
            }
            return taken;
        } finally {
            channel.busy = false;
        }
    }

    /** The info glance: kind and role, or the conflict. */
    public void addGlance(BlockInfoSink sink) {
        if (conflict) {
            sink.line(Component.translatable(P2PContent.INFO_CONFLICT_KEY));
            return;
        }
        sink.line(Component.translatable(P2PContent.INFO_LINK_KEY, Component.translatable(kind.translationKey()),
                Component.translatable(settings.role().translationKey())));
    }

    /** The info details: frequency, and a redstone output's signal. */
    public void addDetails(BlockInfoSink sink) {
        if (settings.isLinked()) {
            sink.line(Component.translatable(P2PContent.INFO_FREQUENCY_KEY, frequencyText(settings.frequency())));
        }
        if (!conflict && settings.role() == P2PRole.OUTPUT && kind == P2PKind.REDSTONE) {
            sink.line(Component.translatable(P2PContent.INFO_SIGNAL_KEY, outputSignal));
        }
    }

    /** A frequency as players see it: four hex groups. */
    public static String frequencyText(long frequency) {
        String hex = String.format(Locale.ROOT, "%016X", frequency);
        return hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12);
    }

    /** Writes the link. */
    public void save(ValueOutput output) {
        if (settings.isLinked()) {
            output.store(LINK, P2PSettings.CODEC, settings);
        }
    }

    /** Reads the link without saving or registering; the end registers when it starts. */
    public void load(ValueInput input) {
        settings = input.read(LINK, P2PSettings.CODEC).orElse(P2PSettings.NONE);
    }

    /**
     * Reads a placed item's link. A block's end has already started when its item's data arrives (vanilla places the
     * block first), so a started end joins its new channel at once.
     */
    public void read(P2PSettings next) {
        if (level != null) {
            link(next);
        } else {
            settings = next;
        }
    }

    /** The item input: one index that takes inserts and gives nothing. */
    private final class ItemInput implements ResourceHandler<ItemResource> {
        @Override
        public int size() {
            return 1;
        }

        @Override
        public ItemResource getResource(int index) {
            return ItemResource.EMPTY;
        }

        @Override
        public long getAmountAsLong(int index) {
            return 0;
        }

        @Override
        public long getCapacityAsLong(int index, ItemResource resource) {
            return resource.isEmpty() ? 64 : resource.getMaxStackSize();
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return !resource.isEmpty();
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            return amount == 0 ? 0 : transfer(resource, amount, StackKey.Kind.ITEM, ITEMS, transaction);
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return 0;
        }
    }

    /** The fluid input: one tank that takes inserts and gives nothing. */
    private final class FluidInput implements ResourceHandler<FluidResource> {
        @Override
        public int size() {
            return 1;
        }

        @Override
        public FluidResource getResource(int index) {
            return FluidResource.EMPTY;
        }

        @Override
        public long getAmountAsLong(int index) {
            return 0;
        }

        @Override
        public long getCapacityAsLong(int index, FluidResource resource) {
            return StorageServerConfig.BATCH_FLUID_MB.getAsInt();
        }

        @Override
        public boolean isValid(int index, FluidResource resource) {
            return !resource.isEmpty();
        }

        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            return amount == 0 ? 0 : transfer(resource, amount, StackKey.Kind.FLUID, FLUIDS, transaction);
        }

        @Override
        public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
            return 0;
        }
    }

    /** The MU input behind {@link #energyInput}: takes MU for the outputs and holds none. */
    private final class EnergyInput implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            return 0;
        }

        @Override
        public long getCapacityAsLong() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            return amount == 0 ? 0 : transferEnergy(amount, transaction);
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            return 0;
        }
    }
}
