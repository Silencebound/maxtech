package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.energy.FeLookups;
import dev.silencebound.maxtech.lib.energy.FeTransfer;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.schedule.ScheduledTask;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.energy.FeView;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.ChannelFree;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageNetworkLogic;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import java.util.List;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: the generator's MU buffer is a NeoForge transfer handler; other mods get FE through its view (MLE).
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The fuel generator (M6a): burns any furnace fuel for its vanilla burn time at {@code machines.fuelGenerator.
 * energyPerTick} MU a tick, so a first network runs without other mods. It is a device of the storage network it
 * touches, holding no channel ({@link GeneratorDevice}), and a producer in that network's MaxTech energy network
 * ({@link GeneratorLink}, MLE item 4): the network's pass draws what it burned, up to {@code maxEnergyOutput} per tick.
 * It also pushes into other neighbours: MU as they are into Storage machines, and FE through Lib's rate into other
 * mods' blocks, under Lib's export settings. It pauses a burn rather than waste it while its buffer is full, and puts a
 * fuel's remainder (a lava bucket's bucket) into its output slot. Burning and pushing run each tick; with a full buffer
 * and nobody taking energy it backs off through the task group's idle delay, and a neighbour change, a neighbour
 * pulling or the network drawing wakes it at once.
 */
public final class FuelGeneratorBlockEntity extends StorageMachineBlockEntity {
    private static final Direction[] SIDES = Direction.values();
    private static final long NO_NODE = Long.MIN_VALUE;
    private static final String JOIN_SEQ = "join_seq";

    @SuppressWarnings("unchecked") // a generic array, filled per side on first use
    private final @Nullable CachedBlockLookup<EnergyHandler>[] neighbours = new CachedBlockLookup[SIDES.length];
    private int burnLeft;
    private int burnTotal;
    // Set while the generator's own task runs, so its own burning and pushing do not wake it again.
    private boolean working;
    /** The generator's producer in its storage network's energy network; made on the server when it loads. */
    private @Nullable GeneratorLink link;
    /** The generator as a storage network device on the node it touches (no channel, like an energy cell). */
    private final GeneratorDevice device = new GeneratorDevice();
    /** The node {@link #device} is attached to, {@link #NO_NODE} for none. */
    private long attachedTo = NO_NODE;

    public FuelGeneratorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        // The fallback only matters outside a world, where no server config is loaded (guide export scenes).
        super(type, pos, state, MachineKind.FUEL_GENERATOR, 0,
                StorageServerConfig.loadedOr(() -> StorageServerConfig.GENERATOR_MAX_ENERGY_OUTPUT.getAsInt(),
                        StorageServerConfig.GENERATOR_MAX_ENERGY_OUTPUT_DEFAULT));
    }

    /** Only fuel goes in, so a hopper carrying mixed items cannot block the slot. */
    @Override
    protected boolean acceptsInput(ItemStack stack) {
        Level current = level;
        return current == null || burnTime(current, stack) > 0;
    }

    /** Ticks {@code stack} burns, from NeoForge's burn time hook (the furnace fuel data map and item overrides). */
    static int burnTime(Level level, ItemStack stack) {
        // PLATFORM-LEAK: NeoForge's patched ItemStack#getBurnTime, which vanilla's deprecated FuelValues lookup skips.
        return stack.getBurnTime(RecipeType.SMELTING, level.fuelValues());
    }

    @Override
    public EnergyHandler energyHandler() {
        return energy.outputOnly();
    }

    /** Other mods may draw FE, under Lib's export settings and counted against the linked energy network. */
    @Override
    protected FeView createFeView(EnergyHandler handler) {
        return FeView.of(handler, false, true, false, this::exportEndpoint, this::gameTime, this::markDirty);
    }

    private @Nullable EnergyEndpoint exportEndpoint() {
        GeneratorLink current = link;
        return current == null ? null : current.producer();
    }

    private long gameTime() {
        Level current = level;
        return current == null ? 0 : current.getGameTime();
    }

    /** The producer endpoint of the generator's link, for diagnostics; null before the server made it. */
    @Override
    public @Nullable EnergyEndpoint energyEndpoint() {
        GeneratorLink current = link;
        return current == null ? null : current.producer();
    }

    /** The storage network whose energy network the generator feeds, or null; for tests. */
    public @Nullable NetworkState linkedNetwork() {
        GeneratorLink current = link;
        return current != null && current.network().isPresent() ? device.link.network() : null;
    }

    /** The generator as its storage network sees it: a device holding no channel. */
    public NetworkDevice device() {
        return device;
    }

    @Override
    public int progress() {
        return burnTotal - burnLeft;
    }

    @Override
    public int duration() {
        return burnLeft > 0 ? burnTotal : 0;
    }

    @Override
    protected int work(ServerLevel level) {
        working = true;
        try {
            return burnAndPush(level);
        } finally {
            working = false;
        }
    }

    private int burnAndPush(ServerLevel level) {
        int rate = StorageServerConfig.GENERATOR_ENERGY_PER_TICK.getAsInt();
        boolean burned = false;
        if (burnLeft == 0 && energy.capacity() - energy.stored() >= rate) {
            startBurn(level);
        }
        if (burnLeft > 0 && energy.capacity() - energy.stored() >= rate) {
            energy.receive(rate);
            burnLeft--;
            burned = true;
        }
        boolean pushed = push(level);
        markDirty();
        if (burned) {
            setState(MachineState.RUNNING);
            return ScheduledTask.NEXT_TICK;
        }
        if (burnLeft > 0) {
            // Full and paused mid-burn.
            setState(MachineState.OUTPUT_FULL);
            return pushed ? ScheduledTask.NEXT_TICK : ScheduledTask.IDLE;
        }
        setState(MachineState.IDLE);
        if (energy.stored() == 0) {
            return ScheduledTask.WAIT;
        }
        return pushed ? ScheduledTask.NEXT_TICK : ScheduledTask.IDLE;
    }

    /** Takes one fuel item, if there is one that burns, and puts its remainder in the output slot or the fuel slot. */
    private void startBurn(ServerLevel level) {
        ItemStack fuel = inputs.stack(0);
        int time = fuel.isEmpty() ? 0 : burnTime(level, fuel);
        if (time <= 0) {
            return;
        }
        ItemStackTemplate remainder = fuel.getCraftingRemainder();
        if (remainder != null && !fits(remainder.create())) {
            return;
        }
        inputs.shrink(0, 1);
        if (remainder != null) {
            ItemStack stack = remainder.create();
            try (Transaction transaction = Transaction.openRoot()) {
                ResourceHandlerUtil.insertStacking(outputs, ItemResource.of(stack), stack.getCount(), transaction);
                transaction.commit();
            }
        }
        burnLeft = time;
        burnTotal = time;
    }

    private boolean fits(ItemStack stack) {
        // Closed without a commit, so nothing is inserted.
        try (Transaction transaction = Transaction.openRoot()) {
            return ResourceHandlerUtil.insertStacking(outputs, ItemResource.of(stack), stack.getCount(),
                    transaction) == stack.getCount();
        }
    }

    /**
     * Pushes into each neighbour that takes energy, up to the per-neighbour limit: MU into Storage machines, FE through
     * the conversion and export settings into other mods' blocks; storage devices get it through the energy network.
     * Returns whether any moved.
     */
    private boolean push(ServerLevel level) {
        boolean moved = false;
        long limit = StorageServerConfig.GENERATOR_MAX_ENERGY_OUTPUT.getAsInt();
        for (Direction side : SIDES) {
            if (energy.stored() == 0) {
                break;
            }
            EnergyHandler target = neighbour(level, side).find();
            if (target == null) {
                continue;
            }
            long sent;
            if (target instanceof FeView view) {
                // A storage device is fed through the energy network; a Storage machine takes MU as they are.
                sent = view.isDevice() ? 0 : FeTransfer.move(energy, view.store(), limit);
            } else {
                sent = FeTransfer.move(feView(), target, Math.max(1, ServerConfig.feRate().toFe(limit)));
            }
            if (sent > 0) {
                moved = true;
            }
        }
        return moved;
    }

    private CachedBlockLookup<EnergyHandler> neighbour(ServerLevel level, Direction side) {
        CachedBlockLookup<EnergyHandler> lookup = neighbours[side.get3DDataValue()];
        if (lookup == null) {
            lookup = FeLookups.BLOCK.cache(level, worldPosition.relative(side), side.getOpposite());
            neighbours[side.get3DDataValue()] = lookup;
        }
        return lookup;
    }

    @Override
    protected void inputsChanged() {
        markDirty();
        wake();
    }

    @Override
    protected void outputsChanged() {
        markDirty();
        wake();
    }

    @Override
    protected void energyChanged() {
        markDirty();
        // A neighbour pulling FE makes room: resume a paused burn at once rather than after the idle backoff. The
        // backoff stays for consumers that use FE without pulling (they can only take what the generator pushes).
        if (!working) {
            wake();
        }
    }

    @Override
    void neighbourChanged() {
        if (level instanceof ServerLevel server) {
            attach(server);
        }
        wake();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            if (link == null) {
                link = new GeneratorLink(server, energy,
                        () -> StorageServerConfig.GENERATOR_MAX_ENERGY_OUTPUT.getAsInt());
            }
            attach(server);
        }
    }

    /**
     * Attaches the generator's device to the storage node it touches (the first storage device or cable side by side),
     * moving it when that changed; it joins that node's network now or when the node appears. Called on load and on
     * neighbour changes, never per tick.
     */
    private void attach(ServerLevel server) {
        if (isRemoved()) {
            return;
        }
        long node = NO_NODE;
        for (Direction side : SIDES) {
            BlockPos at = worldPosition.relative(side);
            if (!server.isLoaded(at)) {
                continue;
            }
            BlockEntity entity = server.getBlockEntity(at);
            if ((entity instanceof StorageDeviceBlockEntity storage && storage.networkId().equals(StorageNetworks.ID))
                    || (entity instanceof ConduitBlockEntity conduit
                            && conduit.findNetwork(StorageNetworks.ID) != null)) {
                node = at.asLong();
                break;
            }
        }
        if (node == attachedTo) {
            return;
        }
        detach(server);
        StorageNetworkLogic logic = StorageNetworks.logic(server, StorageNetworks.ID);
        if (node != NO_NODE && logic != null) {
            attachedTo = node;
            logic.attach(node, device);
        }
    }

    private void detach(ServerLevel server) {
        long node = attachedTo;
        if (node == NO_NODE) {
            return;
        }
        attachedTo = NO_NODE;
        StorageNetworkLogic logic = StorageNetworks.logic(server, StorageNetworks.ID);
        if (logic != null) {
            logic.detach(node, device);
        }
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onChunkUnloaded() {
        // Saved already, with what the link holds: it is dropped without giving that back.
        GeneratorLink current = link;
        if (current != null) {
            current.restoreHeldFe(0);
            current.close();
            link = null;
        }
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        // Broken: what the link holds goes back into the buffer before the generator drops.
        GeneratorLink current = link;
        if (current != null) {
            energy.receive(current.close());
            link = null;
        }
        if (level instanceof ServerLevel server) {
            detach(server);
        }
        super.setRemoved();
    }

    /** What the link holds counts as the generator's, so an unload right after this save loses none of it. */
    @Override
    protected long savedEnergy() {
        GeneratorLink current = link;
        return super.savedEnergy() + (current == null ? 0 : current.heldFe());
    }

    @Override
    protected boolean isPristine() {
        return super.isPristine() && burnLeft == 0;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("burn_left", burnLeft);
        output.putInt("burn_total", burnTotal);
        if (device.link.joinSeq() > 0) {
            output.putLong(JOIN_SEQ, device.link.joinSeq());
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        burnLeft = input.getIntOr("burn_left", 0);
        burnTotal = input.getIntOr("burn_total", 0);
        if (level == null) {
            // Kept from when generators held a channel (MLE item 4 to 2026-10-17), so a save keeps its number.
            device.link.loadJoinSeq(input.getLongOr(JOIN_SEQ, 0L));
        }
    }

    /**
     * The generator on its storage network (MLE Decided: generators feed the controller's buffer as storage network
     * devices): attached to the node it touches like a subnet controller's uplink, and while it is a member its
     * {@link GeneratorLink} is in the network's energy network. It holds no channel ({@link ChannelFree}, owner
     * 2026-10-17), so it is a member whenever that node is in the network, however full the channels are.
     */
    private final class GeneratorDevice implements NetworkDevice, ChannelFree {
        final DeviceLink link = new DeviceLink(this);

        @Override
        public Role role() {
            return Role.OTHER;
        }

        @Override
        public DeviceLink deviceLink() {
            return link;
        }

        @Override
        public long nodePos() {
            return attachedTo;
        }

        @Override
        public long blockPos() {
            return worldPosition.asLong();
        }

        @Override
        public List<StorageProvider> providers() {
            return List.of();
        }

        @Override
        public boolean isRemoved() {
            return FuelGeneratorBlockEntity.this.isRemoved();
        }

        @Override
        public OwnerRecord ownerRecord() {
            return FuelGeneratorBlockEntity.this.ownerRecord();
        }

        @Override
        public void networkChanged(@Nullable StorageNetwork network) {
            GeneratorLink current = FuelGeneratorBlockEntity.this.link;
            if (current == null || current.isClosed()) {
                return;
            }
            if (network instanceof NetworkState state && !state.isClosed()) {
                current.join(state.energy());
            } else {
                current.leave();
            }
            wake();
        }
    }
}
