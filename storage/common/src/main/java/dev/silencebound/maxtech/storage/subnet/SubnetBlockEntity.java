package dev.silencebound.maxtech.storage.subnet;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.energy.EndpointBuffer;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.NetworkController;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.NetworkView;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageNetworkLogic;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.priority.Prioritized;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: every network transfer runs in NeoForge's transfer transaction (PRD 7.2, cache is a view).
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The subnet controller (PRD 7.2, Subnet controller; M6d): one block that starts a subnetwork and marks its boundary.
 * Its front ({@link SubnetBlock#FACING}) faces the parent network and links to nothing there; its other five sides link
 * into the subnet, which is a network of its own with this block as its controller and route root, so the subnet has
 * its own routes, zones, channels, device limit and MU buffer. On the parent side an {@link Uplink} device sits on the
 * node in front of the block.
 *
 * <p>
 * The {@link SubnetMode} decides what each side sees of the other: the parent mounts the subnet's own storage through
 * the uplink's view (up), the subnet mounts the parent's own storage through this block's view (down). A view reads the
 * other network's index directly (its own providers only, never its views, so nothing is counted twice or loops),
 * passes what the {@link SubnetFilter} allows and sits at the controller's priority among the other side's providers.
 * Storing through a view needs merged mode. The side that started an operation pays its energy; the other side charges
 * nothing. Never ticks.
 *
 * <p>
 * Like a controller core it goes on standby when another controller runs its network (M7N item 3): any controller core,
 * or a subnet controller placed before it. Then both its views show nothing, so the parent never sees the same storage
 * twice.
 */
public final class SubnetBlockEntity extends StorageDeviceBlockEntity implements NetworkController, Prioritized {
    private static final String ENERGY = "energy";
    private static final String SHEDDING_ARMED = "shedding_armed";
    private static final String MODE = "mode";
    private static final String PRIORITY = "priority";
    private static final String FILTER = "filter";
    private static final String UPLINK_JOIN_SEQ = "uplink_join_seq";
    private static final String PLACED_AT = "placed_at";
    /** {@link #placedAt} before the block first loaded in a level. */
    private static final long UNPLACED = Long.MIN_VALUE;
    /** Lowest and highest priority: the shared range ({@link PriorityControl}). */
    public static final int MIN_PRIORITY = PriorityControl.MIN;
    public static final int MAX_PRIORITY = PriorityControl.MAX;

    /** Load shedding armed on this network (M7L2): charged past the highest resume level once; saved. */
    private boolean sheddingArmed;
    private final EndpointBuffer buffer = new EndpointBuffer(StorageServerConfig.SUBNET_BUFFER_MU.getAsLong(),
            StorageServerConfig.SUBNET_INPUT_PER_TICK.getAsLong(), 0,
            StorageServerConfig.SUBNET_INPUT_PER_TICK.getAsLong(), 0, EnergyPriority.DEFAULT, this::setChanged);
    private final Uplink uplink = new Uplink();
    private final SubnetView up = new SubnetView(true);
    private final SubnetView down = new SubnetView(false);
    private final SubnetFilter filter = new SubnetFilter(this::viewsChanged);
    private SubnetMode mode = SubnetMode.MERGED;
    private int priority;
    private LongList exits = LongLists.EMPTY_LIST;
    private List<StorageProvider> downProviders = List.of();
    private List<StorageProvider> upProviders = List.of();
    /** Whether the uplink is attached to the node in front; between onLoad and setRemoved. */
    private boolean attached;
    /** Game time the block was placed at; saved, so which controller is in charge survives reloads. */
    private long placedAt = UNPLACED;
    /** On standby as of the last {@link #refreshStatus()}: another controller runs this block's network. */
    private boolean standby;

    public SubnetBlockEntity(BlockPos pos, BlockState state) {
        super(SubnetContent.SUBNET_BLOCK_ENTITY.get(), pos, state);
        refreshProviders();
    }

    @Override
    public Role role() {
        return Role.CONTROLLER;
    }

    /** The side facing the parent network. */
    public Direction front() {
        return getBlockState().getValue(SubnetBlock.FACING);
    }

    /** The subnet links on every side but its front. */
    @Override
    public boolean linksOn(Direction side) {
        return side != front();
    }

    @Override
    public List<StorageProvider> providers() {
        return downProviders;
    }

    /** The device the parent network sees in front of this block; its network is the parent. */
    public Uplink uplink() {
        return uplink;
    }

    /** The parent network, or null while the node in front is in none. */
    public @Nullable NetworkState parent() {
        return uplink.link.network();
    }

    public SubnetMode mode() {
        return mode;
    }

    @Override
    public int priority() {
        return priority;
    }

    public SubnetFilter filter() {
        return filter;
    }

    /** Sets the mode; both sides re-read what they see. Server side. */
    public void setMode(SubnetMode mode) {
        if (mode != this.mode) {
            this.mode = mode;
            viewsChanged();
        }
    }

    /** Sets the priority, clamped to {@link #MIN_PRIORITY}..{@link #MAX_PRIORITY}. Server side. */
    @Override
    public void setPriority(int priority) {
        int clamped = Math.clamp(priority, MIN_PRIORITY, MAX_PRIORITY);
        if (clamped != this.priority) {
            this.priority = clamped;
            viewsChanged();
        }
    }

    /** Mode, priority or filter changed: both views leave and rejoin their networks' indexes with the new rules. */
    private void viewsChanged() {
        setChanged();
        refreshProviders();
        providersChanged();
        uplink.link.providersChanged();
    }

    private void refreshProviders() {
        downProviders = mode.down() ? List.of(down) : List.of();
        upProviders = mode.up() ? List.of(up) : List.of();
    }

    /** This block's network changed: the parent's view of it reads the new one. */
    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        super.networkChanged(network);
        uplink.link.providersChanged();
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook; it runs once the block entity is in a loaded chunk.
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            if (placedAt == UNPLACED) {
                placedAt = server.getGameTime();
                setChanged();
            }
            exits = LongLists.singleton(worldPosition.asLong());
            StorageNetworkLogic logic = StorageNetworks.logic(server, networkId());
            if (logic != null) {
                attached = true;
                logic.attach(uplink.nodePos(), uplink);
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (attached && level instanceof ServerLevel server) {
            attached = false;
            StorageNetworkLogic logic = StorageNetworks.logic(server, networkId());
            if (logic != null) {
                logic.detach(uplink.nodePos(), uplink);
            }
        }
    }

    @Override
    public boolean sheddingArmed() {
        return sheddingArmed;
    }

    @Override
    public void armShedding() {
        if (!sheddingArmed) {
            sheddingArmed = true;
            setChanged();
        }
    }

    @Override
    public FeBuffer buffer() {
        return buffer;
    }

    @Override
    public EnergyEndpoint energyEndpoint() {
        return buffer.endpoint();
    }

    /** The parent pays what the subnet's own store cannot, in every mode; none while there is no separate parent. */
    @Override
    public @Nullable NetworkState energyParent() {
        NetworkState parent = parent();
        return parent == network() ? null : parent;
    }

    @Override
    public long placedAt() {
        return placedAt;
    }

    @Override
    public boolean isSubnetController() {
        return true;
    }

    /** Whether another controller runs this block's network, as of the last {@link #refreshStatus()}. */
    public boolean isStandby() {
        return standby;
    }

    /** The block has one look; the info lines show the status. Going on or off standby turns both views off or on. */
    @Override
    public void refreshStatus() {
        NetworkState current = network();
        boolean now = current != null && current.isStandby(this);
        if (now != standby) {
            standby = now;
            providersChanged();
            uplink.link.providersChanged();
        }
    }

    /** Every side but the front may carry a route, so no link is ever left over. */
    @Override
    public int rootLinks() {
        return Direction.values().length;
    }

    @Override
    public LongList exitNodes() {
        return exits;
    }

    @Override
    public int maxDevices() {
        return StorageServerConfig.SUBNET_MAX_DEVICES.getAsInt();
    }

    @Override
    public int batchPercent() {
        return 100;
    }

    @Override
    public void refreshLoad(NetworkState network) {
        // No load display.
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        sink.line(standby
                ? Component.translatable(ControllerContent.STANDBY_KEY)
                : Component.translatable(SubnetTexts.INFO_MODE, Component.translatable(mode.translationKey()),
                        InfoFormat.number(priority), InfoFormat.number(filter.size())));
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        if (standby) {
            sink.line(Component.translatable(SubnetTexts.INFO_MODE, Component.translatable(mode.translationKey()),
                    InfoFormat.number(priority), InfoFormat.number(filter.size())));
        }
        NetworkState parent = parent();
        sink.line(parent == null
                ? Component.translatable(SubnetTexts.INFO_NO_PARENT)
                : parent == network()
                        ? Component.translatable(SubnetTexts.INFO_SAME_NETWORK)
                        : Component.translatable(SubnetTexts.INFO_PARENT, InfoFormat.number(parent.deviceCount())));
    }

    /** The block as an item with its settings and energy in vanilla's block entity data. */
    public ItemStack toItemStack(HolderLookup.Provider registries) {
        ItemStack stack = new ItemStack(getBlockState().getBlock());
        stack.set(DataComponents.BLOCK_ENTITY_DATA,
                TypedEntityData.<BlockEntityType<?>>of(getType(), saveCustomOnly(registries)));
        return stack;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong(ENERGY, buffer.stored());
        output.putBoolean(SHEDDING_ARMED, sheddingArmed);
        output.putInt(MODE, mode.id());
        output.putInt(PRIORITY, priority);
        ContainerHelper.saveAllItems(output.child(FILTER), filter.getItems());
        if (uplink.link.joinSeq() > 0) {
            output.putLong(UPLINK_JOIN_SEQ, uplink.link.joinSeq());
        }
        if (placedAt != UNPLACED) {
            output.putLong(PLACED_AT, placedAt);
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        buffer.load(input.getLongOr(ENERGY, 0L));
        sheddingArmed = input.getBooleanOr(SHEDDING_ARMED, false);
        mode = SubnetMode.byId(input.getIntOr(MODE, SubnetMode.MERGED.id()));
        priority = Math.clamp(input.getIntOr(PRIORITY, 0), MIN_PRIORITY, MAX_PRIORITY);
        if (level == null) {
            // Only a chunk load keeps the placement time; block data put on a placed block (an item) does not.
            placedAt = input.getLongOr(PLACED_AT, UNPLACED);
        }
        filter.getItems().clear();
        input.child(FILTER).ifPresent(child -> ContainerHelper.loadAllItems(child, filter.getItems()));
        filter.rebuild();
        if (level == null) {
            // As for the block's own link: only a chunk load keeps the uplink's join order for the parent's channels.
            uplink.link.loadJoinSeq(input.getLongOr(UPLINK_JOIN_SEQ, 0L));
        }
        refreshProviders();
        if (level != null && !level.isClientSide()) {
            // Settings placed with an item, or changed by a command: both sides read them again.
            providersChanged();
            uplink.link.providersChanged();
        }
    }

    /**
     * The subnet controller as its parent network sees it: a device on the node in front of the block (a cable or
     * another device), owned like the block, holding the parent's view of the subnet. It counts towards the parent's
     * device limit like any device.
     */
    public final class Uplink implements NetworkDevice {
        final DeviceLink link = new DeviceLink(this);

        /** The subnet controller this uplink belongs to. */
        public SubnetBlockEntity subnet() {
            return SubnetBlockEntity.this;
        }

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
            return worldPosition.relative(front()).asLong();
        }

        @Override
        public long blockPos() {
            return worldPosition.asLong();
        }

        @Override
        public List<StorageProvider> providers() {
            return upProviders;
        }

        @Override
        public boolean isRemoved() {
            return SubnetBlockEntity.this.isRemoved();
        }

        @Override
        public OwnerRecord ownerRecord() {
            return SubnetBlockEntity.this.ownerRecord();
        }

        /** The parent changed: the subnet's view of it reads the new one. */
        @Override
        public void networkChanged(@Nullable StorageNetwork network) {
            providersChanged();
        }
    }

    /**
     * One side's view of the other network's own storage. {@code toParent} false is the parent's view of the subnet
     * (held by the uplink), true the subnet's view of the parent (held by this block). Empty while the other side is in
     * no network or in the same network as this side (the subnet joined its parent elsewhere).
     */
    private final class SubnetView implements NetworkView {
        private final boolean upward;
        private StorageProvider.@Nullable Listener listener;
        private @Nullable NetworkState watching;
        private final StorageProvider.Listener forward = this::forward;

        SubnetView(boolean upward) {
            this.upward = upward;
        }

        /** The network this view shows, or null when it shows nothing (also while the block is on standby). */
        private @Nullable NetworkState target() {
            NetworkState own = network();
            // Asked of the network, not the flag refreshStatus keeps, so a view shows nothing from the moment the
            // block is on standby.
            if (own != null && own.isStandby(SubnetBlockEntity.this)) {
                return null;
            }
            NetworkState subnet = network();
            NetworkState parent = parent();
            if (subnet == null || parent == null || subnet == parent) {
                return parent == network() ? null : parent;
            }
            return upward ? subnet : parent;
        }

        @Override
        public @Nullable NetworkState viewed() {
            return target();
        }

        private void forward(StackKey key, long delta) {
            StorageProvider.Listener current = listener;
            if (current != null && filter.allows(key)) {
                current.changed(key, delta);
            }
        }

        @Override
        public long insert(StackKey key, long amount, TransactionContext transaction) {
            NetworkState target = target();
            if (target == null || !mode.stores() || !filter.allows(key)) {
                return 0;
            }
            return target.nativeInsert(key, amount, transaction);
        }

        @Override
        public long extract(StackKey key, long amount, TransactionContext transaction) {
            NetworkState target = target();
            if (target == null || !filter.allows(key)) {
                return 0;
            }
            return target.nativeExtract(key, amount, transaction);
        }

        @Override
        public long amount(StackKey key) {
            NetworkState target = target();
            return target == null || !filter.allows(key) ? 0 : target.nativeAmount(key);
        }

        @Override
        public void forEachStored(Visitor visitor) {
            NetworkState target = target();
            if (target != null) {
                target.forEachNative((key, amount) -> {
                    if (filter.allows(key)) {
                        visitor.visit(key, amount);
                    }
                });
            }
        }

        @Override
        public void setListener(StorageProvider.@Nullable Listener listener) {
            this.listener = listener;
            NetworkState target = listener == null ? null : target();
            if (target != watching) {
                if (watching != null) {
                    watching.unlistenNative(forward);
                }
                if (target != null) {
                    target.listenNative(forward);
                }
                watching = target;
            }
        }

        @Override
        public int priority() {
            return priority;
        }
    }
}
