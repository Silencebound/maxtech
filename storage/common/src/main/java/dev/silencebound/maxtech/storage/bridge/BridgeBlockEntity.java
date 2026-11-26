package dev.silencebound.maxtech.storage.bridge;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.port.PortRate;
import dev.silencebound.maxtech.storage.port.PortSpeed;
import dev.silencebound.maxtech.storage.port.PortTier;
import dev.silencebound.maxtech.storage.save.KeptList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The network bridge's block entity (M7b): finds the foreign network on the face it touches ({@link ForeignNetworks}),
 * shows it to its Storage network as one provider ({@link BridgeStorage}) and mounts the Storage network into it
 * ({@link ExportedStorage}). Its back links to the Storage network, like a port's.
 *
 * <p>
 * Never ticks: one task on the network's scheduler finds the foreign network (on load and neighbour changes), reads its
 * contents again when it signals a change (at most every {@code bridges.refreshTicks}) or on a slow poll when it cannot
 * signal ({@code bridges.pollIntervalTicks}), and offers the held store back to it. Takes, stores and the foreign
 * side's moves through the export share the rate of a port of the bridge's tier.
 */
public final class BridgeBlockEntity extends StorageDeviceBlockEntity implements BridgeStorage.Host {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String HELD = "held";
    private static final Codec<KeptList<BridgeStorage.Held>> HELD_CODEC = KeptList.codec(BridgeStorage.Held.CODEC);
    private static final byte DROP_NONE = 0;
    private static final byte DROP_BRIDGE = 1;
    private static final byte DROP_IF_HELD = 2;

    private final PortTier tier;
    private final BridgeStorage storage = new BridgeStorage(this);
    private final List<StorageProvider> providers = List.of(storage);
    private final ExportedStorage exported = new ExportedStorage(this);
    private final PortRate itemRate = new PortRate();
    private final PortRate fluidRate = new PortRate();
    private final Task task = new Task();
    private final Runnable changed = this::foreignChanged;
    /** The foreign network in front, mounted or not. */
    private @Nullable ForeignNetwork found;
    /** Where the export is mounted now, so it is taken out of the same network. */
    private @Nullable ForeignNetwork exportedTo;
    /** Whether {@link #found} signals its changes; otherwise it is polled. */
    private boolean events;
    /** Whether this bridge mounts {@link #found} for its network (it is the network's first bridge to it). */
    private boolean mounted;
    /** Look for the foreign network again on the next pass. */
    private boolean detect = true;
    /** Read the foreign contents again on the next pass. */
    private boolean dirty = true;
    /** A pass found moves through the bridge open and waits for {@link #settled} to run again. */
    private boolean passWaits;
    /** The unmount found moves through the bridge open: forget the foreign contents once they close. */
    private boolean clearWaits;
    /** Removed while moves through the bridge were open: let go of the foreign network once they close. */
    private boolean removedWaits;
    /** Whether {@link #settled} drops a fresh bridge item for a removal while moves were open ({@link #deferDrop}). */
    private byte deferredDrop = DROP_NONE;
    private long nextRefresh;
    /** When a foreign network without change signals is read next. */
    private long nextPoll;
    private boolean loaded;
    /** Held entries whose keys no longer load (a removed mod), saved back unchanged until the mod returns. */
    private List<Tag> keptHeld = List.of();

    public BridgeBlockEntity(BlockPos pos, BlockState state) {
        super(BridgeContent.BLOCK_ENTITY.get(), pos, state);
        tier = state.getBlock() instanceof BridgeBlock bridge ? bridge.tier() : PortTier.TIER_1;
    }

    @Override
    public Role role() {
        return Role.STORAGE;
    }

    public PortTier tier() {
        return tier;
    }

    /** Only the back links to the Storage network; the front touches the foreign one. */
    @Override
    public boolean linksOn(Direction side) {
        return side == facing().getOpposite();
    }

    /** The side the foreign network's block is on. */
    public Direction facing() {
        return getBlockState().getValue(BridgeBlock.FACING);
    }

    @Override
    public List<StorageProvider> providers() {
        return providers;
    }

    /** The provider its network reads; for tests and tools. */
    public BridgeStorage storage() {
        return storage;
    }

    /** The export it mounts into the foreign network; for tests. */
    public ExportedStorage exported() {
        return exported;
    }

    /** Whether it mounts its foreign network now (not on standby, not without one). */
    public boolean isMounted() {
        return mounted;
    }

    @Override
    public int operationsPerSecond() {
        return (int) Math.min(Integer.MAX_VALUE,
                PortSpeed.operations(tier.itemsPerSecond(), StorageServerConfig.BATCH_ITEMS.getAsInt())
                        + PortSpeed.operations(tier.fluidMbPerSecond(), StorageServerConfig.BATCH_FLUID_MB.getAsInt()));
    }

    // --- BridgeStorage.Host --------------------------------------------------------------------------------------

    @Override
    public @Nullable ForeignNetwork mounted() {
        return mounted && network() != null ? found : null;
    }

    @Override
    public @Nullable ForeignNetwork found() {
        return found;
    }

    @Override
    public long allowance(StackKey key) {
        Level current = level;
        if (current == null) {
            return 0;
        }
        boolean fluid = key.kind() == StackKey.Kind.FLUID;
        return (fluid ? fluidRate : itemRate).available(current.getGameTime(), perSecond(fluid));
    }

    @Override
    public void spend(StackKey key, long amount) {
        allowance(key);
        (key.kind() == StackKey.Kind.FLUID ? fluidRate : itemRate).spend(amount);
    }

    private long perSecond(boolean fluid) {
        return fluid ? tier.fluidMbPerSecond() : tier.itemsPerSecond();
    }

    @Override
    public void heldChanged() {
        setChanged();
        wake(task);
    }

    @Override
    public void settled() {
        if (clearWaits) {
            clearWaits = false;
            if (!mounted) {
                storage.refresh(null);
            }
        }
        if (passWaits) {
            passWaits = false;
            wake(task);
        }
        byte drop = deferredDrop;
        deferredDrop = DROP_NONE;
        if (removedWaits) {
            removedWaits = false;
            Level current = level;
            if (current != null && (drop == DROP_BRIDGE || (drop == DROP_IF_HELD && holdsAnything()))) {
                // The final held store: what it held at the break plus what the foreign network refused since.
                dropBridge(current);
            } else if (drop == DROP_NONE || current == null) {
                spillHeld();
            }
            attach(null);
        }
    }

    /**
     * A bridge removed while moves were open without dropping itself (replaced by a command, say): its held store
     * belongs to a block entity nobody saves, so items drop where the bridge stood. A fluid cannot drop; it is logged.
     */
    private void spillHeld() {
        List<BridgeStorage.Held> held = storage.held();
        storage.clearHeld();
        Level current = level;
        for (BridgeStorage.Held entry : held) {
            if (current != null && entry.key() instanceof ItemKey item) {
                long left = entry.amount();
                while (left > 0) {
                    int count = (int) Math.min(left, item.toStack(1).getMaxStackSize());
                    Containers.dropItemStack(current, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                            item.toStack(count));
                    left -= count;
                }
            } else {
                LOGGER.warn("Bridge at {} removed holding {} of {} that the foreign network refused", worldPosition,
                        entry.amount(), entry.key());
            }
        }
    }

    // --- Events --------------------------------------------------------------------------------------------------

    /** The foreign network signalled a change: read it again on the next pass. Cheap, it may come often. */
    private void foreignChanged() {
        if (!dirty) {
            dirty = true;
            wake(task);
        }
    }

    /** The block in front changed: look for the foreign network again. */
    void neighbourChanged() {
        detect = true;
        wake(task);
    }

    /** Another bridge attached, left or changed network: whose turn it is to mount may have changed. */
    void linksChanged() {
        wake(task);
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        super.networkChanged(network);
        exported.networkChanged();
        if (network == null && mounted) {
            // Out of a network: no task runs until it joins one, so take the mount down now.
            unmount();
        }
        BridgeLinks.changed();
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook; it runs once the block entity is in a loaded chunk.
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            loaded = true;
            detect = true;
            schedule(task);
            BridgeLinks.add(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (loaded) {
            loaded = false;
            unmount();
            BridgeLinks.remove(this);
            if (storage.idle()) {
                attach(null);
            } else {
                // Gone inside a transaction with moves through it open: keep the foreign network until they close, so
                // committed stores still reach it and rolled-back takes still go back; what it then refuses goes into
                // the dropped bridge item in settled().
                removedWaits = true;
            }
        }
    }

    // --- Mounting ------------------------------------------------------------------------------------------------

    /**
     * Switches to {@code now} as the foreign network in front, letting go of the old one. A finder may hand out a new
     * object for the same network each time; one with the same identity as the connected current one changes nothing.
     */
    private void attach(@Nullable ForeignNetwork now) {
        ForeignNetwork old = found;
        if (now == null ? old == null : old != null && old.connected() && now.identity().equals(old.identity())) {
            return;
        }
        if (mounted) {
            unmount();
        }
        if (old != null) {
            old.setChangeListener(null);
        }
        found = now;
        events = now != null && now.setChangeListener(changed);
        dirty = true;
        BridgeLinks.changed();
    }

    /** Starts showing and exporting {@link #found}: reads it, has the network index it again, mounts the export. */
    private void mount(ForeignNetwork foreign, long gameTime) {
        mounted = true;
        clearWaits = false;
        storage.refresh(foreign);
        storage.show(true);
        dirty = false;
        nextRefresh = gameTime + StorageServerConfig.BRIDGE_REFRESH_TICKS.getAsInt();
        nextPoll = gameTime + StorageServerConfig.BRIDGE_POLL_TICKS.getAsInt();
        providersChanged();
        exportedTo = foreign;
        foreign.export(exported);
    }

    /** Stops showing and exporting: the network indexes only the held store again. */
    private void unmount() {
        mounted = false;
        storage.show(false);
        if (storage.idle()) {
            clearWaits = false;
            storage.refresh(null);
        } else {
            // Moves through the bridge are open (the block went or left its network inside a transaction): their
            // commit or rollback still updates the last read, so it is cleared once they close.
            clearWaits = true;
        }
        ForeignNetwork target = exportedTo;
        exportedTo = null;
        if (target != null) {
            target.export(null);
        }
        providersChanged();
    }

    /** One pass of the task; see the class comment. */
    private int pass(ServerLevel server, long gameTime) {
        if (!storage.idle()) {
            // Moves through the bridge are open: reading, mounting and draining wait until they close (settled).
            passWaits = true;
            return StorageTask.WAIT;
        }
        ForeignNetwork current = found;
        if (detect || (current != null && !current.connected())) {
            detect = false;
            Direction facing = facing();
            attach(ForeignNetworks.find(server, worldPosition.relative(facing), facing.getOpposite()));
            current = found;
        }
        boolean lead = current != null && network() != null && BridgeLinks.leads(this);
        if (lead != mounted) {
            if (current != null && lead) {
                mount(current, gameTime);
            } else {
                unmount();
            }
        } else if (mounted && (dirty || (!events && gameTime >= nextPoll))) {
            if (gameTime < nextRefresh) {
                return (int) Math.max(StorageTask.NEXT_TICK, nextRefresh - gameTime);
            }
            dirty = false;
            nextRefresh = gameTime + StorageServerConfig.BRIDGE_REFRESH_TICKS.getAsInt();
            nextPoll = gameTime + StorageServerConfig.BRIDGE_POLL_TICKS.getAsInt();
            storage.refresh(current);
        }
        if (current == null) {
            // Nothing in front: wait for a neighbour change; the held store stays where the network can take it.
            return StorageTask.WAIT;
        }
        boolean holding = storage.drain(current);
        if (!events && mounted) {
            // Woken early by something else, the poll still waits for its time.
            return (int) Math.max(StorageTask.NEXT_TICK, nextPoll - gameTime);
        }
        return holding ? StorageServerConfig.BRIDGE_POLL_TICKS.getAsInt() : StorageTask.WAIT;
    }

    private final class Task implements StorageTask {
        @Override
        public int run(StorageTransfer transfer, long gameTime) {
            return level instanceof ServerLevel server && !isRemoved() ? pass(server, gameTime) : StorageTask.WAIT;
        }
    }

    // --- Info, saving, drops -------------------------------------------------------------------------------------

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        ForeignNetwork current = found;
        if (current == null) {
            sink.line(Component.translatable(BridgeContent.INFO_NONE));
        } else if (mounted) {
            sink.line(Component.translatable(BridgeContent.INFO_MOUNTED, current.name()));
        } else {
            sink.line(Component.translatable(BridgeContent.INFO_STANDBY, current.name()));
        }
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        sink.line(Component.translatable(BridgeContent.INFO_RATE, InfoFormat.number(tier.itemsPerSecond()),
                InfoFormat.number(tier.fluidMbPerSecond())));
        long held = storage.heldTotal();
        if (held > 0) {
            sink.line(Component.translatable(BridgeContent.INFO_HELD, InfoFormat.number(held)));
        }
    }

    /** Whether the held store holds anything, entries kept unread (a removed mod) included. */
    public boolean holdsAnything() {
        return storage.heldTotal() > 0 || !keptHeld.isEmpty();
    }

    /**
     * Puts the held store, when there is one, on {@code stack} (the dropped bridge) in vanilla's block entity data, so
     * placing it again brings it back and nothing held is lost when the bridge is broken.
     */
    public void writeHeld(ItemStack stack, HolderLookup.Provider registries) {
        if (holdsAnything()) {
            stack.set(DataComponents.BLOCK_ENTITY_DATA,
                    TypedEntityData.<BlockEntityType<?>>of(getType(), saveCustomOnly(registries)));
        } else {
            stack.remove(DataComponents.BLOCK_ENTITY_DATA);
        }
    }

    /**
     * Asked for its drops ({@code always}: a normal break) or broken by a creative player ({@code false}: drop only
     * when it ends up holding something). While moves through the bridge are open, the held store is not final yet, so
     * this returns true and the bridge item is left out of the drops: once the moves close and the bridge is gone,
     * {@link #settled} spawns a fresh bridge item with the final held store. Never writes into a stack already handed
     * out, which others may copy or swap.
     */
    boolean deferDrop(boolean always) {
        if (storage.idle()) {
            return false;
        }
        if (always) {
            deferredDrop = DROP_BRIDGE;
        } else if (deferredDrop == DROP_NONE) {
            deferredDrop = DROP_IF_HELD;
        }
        return true;
    }

    /** Spawns a fresh bridge item where the bridge stood, carrying its held store, and empties the held store. */
    private void dropBridge(Level current) {
        ItemStack stack = new ItemStack(getBlockState().getBlock());
        stack.applyComponents(collectComponents());
        writeHeld(stack, current.registryAccess());
        storage.clearHeld();
        Containers.dropItemStack(current, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), stack);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        List<BridgeStorage.Held> held = storage.held();
        if (!held.isEmpty() || !keptHeld.isEmpty()) {
            output.store(HELD, HELD_CODEC, KeptList.of(held, keptHeld));
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        KeptList<BridgeStorage.Held> held = input.read(HELD, HELD_CODEC).orElse(KeptList.of(List.of()));
        keptHeld = held.kept();
        storage.loadHeld(held.known());
        if (level != null && !level.isClientSide()) {
            // Placed from an item that carried a held store: the network indexes it.
            providersChanged();
        }
    }
}
