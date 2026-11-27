package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.security.Owned;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.api.network.NetworkType;
import dev.silencebound.maxtech.storage.energy.FeView;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import dev.silencebound.maxtech.lib.save.VersionedBlockEntity;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: Storage's MU stores are NeoForge transfer handlers inside transactions; FE views wrap them (MLE).
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The block entity every storage device extends (controller, drive, terminal, port). It makes the device a node of the
 * storage network: added when it loads, removed when it is broken or unloads, linked to the storage cables and devices
 * it touches ({@link StorageConnectivity}). It keeps the device's owner, its scheduled {@link StorageTask}s (which move
 * with it between networks), and an energy input that feeds its network's store in MU, shown to other mods as FE
 * ({@link #feHandler()}), so a generator touching any device powers the network. Never ticks.
 *
 * <p>
 * Subclasses give their {@link #role()}, override {@link #providers()} if they hold storage and call
 * {@link #providersChanged()} when that list changes.
 */
public abstract class StorageDeviceBlockEntity extends VersionedBlockEntity
        implements
            NetworkDevice,
            Owned,
            BlockInfoProvider {
    private static final String OWNER = "owner";
    private static final String LINKS = "links";
    private static final String LABEL = "label";
    private static final String SWITCHED_OFF = "switched_off";
    private static final String SWITCHED_OFF_BY = "switched_off_by";
    private static final String POWER_PRIORITY = "power_priority";
    private static final String JOIN_SEQ = "join_seq";

    private OwnerRecord owner = OwnerRecord.UNOWNED;
    /** Sides linked into the network the last time they were loaded; kept for sides whose chunk is unloaded. */
    private int linkMask;
    private final DeviceLink link = new DeviceLink(this);
    private final EnergyHandler energyInput = new EnergyInput();
    private @Nullable FeView feView;

    protected StorageDeviceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** The network type this device joins; the storage network unless a test says otherwise. */
    public Identifier networkId() {
        return StorageNetworks.ID;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public long nodePos() {
        return worldPosition.asLong();
    }

    /** Sets the label (see {@link NetworkDevice#setLabel}) and saves the block when it changed. */
    @Override
    public void setLabel(DeviceLabel label) {
        if (link.setLabel(label)) {
            setChanged();
        }
    }

    /** Turns the device off or on for logic (see {@link DeviceLink#setSwitchedOff}) and saves the block. */
    @Override
    public void setSwitchedOff(boolean off, @Nullable SwitchSource by) {
        if (link.setSwitchedOff(off, by)) {
            setChanged();
        }
    }

    /** Sets the power priority (see {@link DeviceLink#setPowerPriority}) and saves the block when it changed. */
    @Override
    public void setPowerPriority(int priority) {
        if (link.setPowerPriority(priority)) {
            setChanged();
        }
    }

    /** The network this device is in, or {@code null} while it is in none (just placed, or between rebuilds). */
    public @Nullable NetworkState network() {
        return link.network;
    }

    /**
     * Shows {@code look} in the block's {@link LinkLook#PROPERTY} when the block has one and shows another: sent to
     * clients only, with no neighbour, shape or placement updates, so the network sees no topology change.
     */
    @Override
    public void linkLookChanged(LinkLook look) {
        Level current = level;
        if (current == null || current.isClientSide() || isRemoved()) {
            return;
        }
        BlockState state = current.getBlockState(worldPosition);
        if (state.hasProperty(LinkLook.PROPERTY) && state.getValue(LinkLook.PROPERTY) != look) {
            current.setBlock(worldPosition, state.setValue(LinkLook.PROPERTY, look),
                    Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        }
    }

    @Override
    public List<StorageProvider> providers() {
        return List.of();
    }

    /**
     * Whether the device links to a storage cable or device on {@code side}; every side by default. A port links only
     * on its back, where its cable stub ends. Read whenever the device's or a neighbour's links are re-read, so the
     * answer must not change while the block stays the same.
     */
    public boolean linksOn(Direction side) {
        return true;
    }

    @Override
    public OwnerRecord ownerRecord() {
        return owner;
    }

    /** Sets the owner, normally from the player who placed the block. Server side. */
    public void setOwner(OwnerRecord owner) {
        this.owner = owner;
        setChanged();
        NetworkState current = link.network;
        if (current != null) {
            current.markChanged();
        }
    }

    /**
     * The MU input of this device: it fills its network's energy store, and takes nothing without a network. Other mods
     * reach it converted to FE through {@link #feHandler()}.
     */
    public EnergyHandler energyInput() {
        return energyInput;
    }

    /**
     * What this device shows other mods on every side through NeoForge's energy capability: {@link #energyInput()} in
     * FE at Lib's configured rate ({@link FeView}). The same instance for the device's life.
     */
    public final EnergyHandler feHandler() {
        return feView();
    }

    private FeView feView() {
        FeView view = feView;
        if (view == null) {
            view = createFeView(energyInput());
            feView = view;
        }
        return view;
    }

    /** A neighbouring block changed (the block's neighbour update, server side); nothing by default. */
    public void onNeighbourChanged() {
    }

    /**
     * The MaxTech energy endpoint this block shows diagnostics through (Lib's endpoint capability: Jade, the scanner,
     * the analyzer, the probe and sensors): a controller its buffer's, an energy cell its own; null for devices that
     * own none, whose readouts then show their storage network's energy network. For reading only.
     */
    public @Nullable EnergyEndpoint energyEndpoint() {
        return null;
    }

    /** Makes the FE view over {@code input}: input only by default; a device that gives energy out overrides it. */
    protected FeView createFeView(EnergyHandler input) {
        return FeView.deviceInput(input, this::setChanged);
    }

    /** The level's game time, 0 without a level; for FE views counting exports per tick. */
    protected final long gameTime() {
        Level current = level;
        return current == null ? 0 : current.getGameTime();
    }

    /** See {@link DeviceLink#providersChanged()}. */
    public void providersChanged() {
        link.providersChanged();
    }

    /**
     * Hands {@code task} to the device's network's scheduler ({@link DeviceLink#schedule}). Tasks belong to this block
     * entity instance, so a device schedules them in {@code onLoad}.
     */
    public void schedule(StorageTask task) {
        link.schedule(task);
    }

    /** Takes {@code task} out of the scheduler for good. */
    public void cancel(StorageTask task) {
        link.cancel(task);
    }

    /** Tasks scheduled for this device and not finished, in or out of a network. */
    public int scheduledTasks() {
        return link.scheduledTasks();
    }

    /** Wakes {@code task} for its network's next pass; for events such as a neighbour change. */
    public void wake(StorageTask task) {
        link.wake(task);
    }

    int linkMask() {
        return linkMask;
    }

    void setLinkMask(int mask) {
        if (mask != linkMask) {
            linkMask = mask;
            setChanged();
        }
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook; it runs once the block entity is in a loaded chunk, for
    // placement and chunk load alike.
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            // The saved front line is stale: show what this device's network has shown so far, offline until a
            // network shows it (one that stays paused never would).
            linkLookChanged(link.linkLook());
            StorageNetworkLogic logic = StorageNetworks.logic(server, networkId());
            NetworkType type = NetworkType.byId(networkId()).orElse(null);
            if (logic != null && type != null) {
                logic.deviceLoaded(this);
                NetworkManager.get(server).addNode(type, worldPosition);
            }
        }
    }

    /**
     * Broken or unloaded: the device leaves its network at once, so no transfer reaches its providers after this, and
     * its node goes at the end of the tick.
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel server) {
            StorageNetworkLogic logic = StorageNetworks.logic(server, networkId());
            NetworkType type = NetworkType.byId(networkId()).orElse(null);
            if (logic != null && type != null) {
                logic.deviceRemoved(this);
                NetworkManager.get(server).removeNode(type, worldPosition);
            }
        }
    }

    @Override
    public void addInfo(BlockInfoRequest request, BlockInfoSink sink) {
        NetworkInfo.device(this, request, sink);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (owner.isOwned()) {
            output.store(OWNER, OwnerRecord.CODEC, owner);
        }
        output.putByte(LINKS, (byte) linkMask);
        DeviceLabel label = link.label();
        if (!label.isEmpty()) {
            output.store(LABEL, DeviceLabel.CODEC, label);
        }
        if (link.switchedOff()) {
            output.putBoolean(SWITCHED_OFF, true);
            link.switchedOffBy().ifPresent(by -> output.store(SWITCHED_OFF_BY, SwitchSource.CODEC, by));
        }
        if (link.savedPowerPriority() != 0) {
            output.putInt(POWER_PRIORITY, link.savedPowerPriority());
        }
        if (link.joinSeq() > 0) {
            output.putLong(JOIN_SEQ, link.joinSeq());
        }
        FeView view = feView;
        if (view != null) {
            view.save(output);
        }
    }

    @Override
    protected final SaveFormat saveFormat() {
        return StorageSaveFormats.BLOCK_ENTITIES;
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        owner = input.read(OWNER, OwnerRecord.CODEC).orElse(OwnerRecord.UNOWNED);
        linkMask = input.getByteOr(LINKS, (byte) 0);
        link.setLabel(input.read(LABEL, DeviceLabel.CODEC).orElse(DeviceLabel.NONE));
        link.setSwitchedOff(input.getBooleanOr(SWITCHED_OFF, false),
                input.read(SWITCHED_OFF_BY, SwitchSource.CODEC).orElse(null));
        link.loadPowerPriority(input.getIntOr(POWER_PRIORITY, 0));
        // The fraction of one MU an FE insert or the move from FE (MLE item 4) left.
        feView().load(input);
        if (level == null) {
            // Only a chunk load (the level is set afterwards) keeps the join order for channels (M7N); data from a
            // placed item (a drive's cells) or a command must not, so a block placed again joins as the newest device.
            link.loadJoinSeq(input.getLongOr(JOIN_SEQ, 0L));
        }
    }

    /**
     * The label, the logic on/off state and the power priority travel in the block's item like other settings; blocks
     * drop it ({@link StorageDeviceBlock}).
     */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        DeviceLabel label = link.label();
        if (!label.isEmpty()) {
            components.set(ModDataComponents.DEVICE_LABEL.get(), label);
        }
        if (link.switchedOff()) {
            components.set(ModDataComponents.SWITCHED_OFF.get(), true);
        }
        if (link.savedPowerPriority() != 0) {
            components.set(ModDataComponents.POWER_PRIORITY.get(), link.savedPowerPriority());
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        // Only when the item has one: items that carry block entity data (the subnet controller's) already brought the
        // label through loadSaved, which runs first, and a missing component must not clear it.
        DeviceLabel label = components.get(ModDataComponents.DEVICE_LABEL.get());
        if (label != null) {
            setLabel(label);
        }
        // Not when block entity data already turned it off: that keeps the graph that did it.
        if (Boolean.TRUE.equals(components.get(ModDataComponents.SWITCHED_OFF.get())) && !link.switchedOff()) {
            setSwitchedOff(true);
        }
        Integer priority = components.get(ModDataComponents.POWER_PRIORITY.get());
        if (priority != null) {
            setPowerPriority(priority);
        }
    }

    /** Takes MU into the network's store; gives none back. */
    private final class EnergyInput implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            NetworkState current = link.network;
            return current == null ? 0 : current.storedEnergy();
        }

        @Override
        public long getCapacityAsLong() {
            NetworkState current = link.network;
            return current == null ? 0 : current.energyCapacity();
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            NetworkState current = link.network;
            EnergyHandler store = current == null ? null : current.energyStore();
            if (current == null || store == null) {
                return 0;
            }
            int inserted = store.insert(amount, transaction);
            current.countReceived(inserted, transaction);
            return inserted;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            return 0;
        }
    }
}
