package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.ChannelFree;
import dev.silencebound.maxtech.storage.network.NetworkController;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.ServiceDevice;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.signal.AntennaMountBlockEntity;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessCoverage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The block entity of a {@link ServiceDeviceBlock} (M7R item 4): a device like any other (role other; one channel, none
 * for a security module or network anchor) that the network lists under its {@link NetworkService} while it is a
 * member. The service itself is read from the network where it applies: the controller applies its security rules and
 * keeps chunks loaded while the network has a security module or anchor, wireless coverage is worked out from the
 * wireless access mounts' own positions, and a dimension bridge drains FE ({@link BridgeDrain}) while it is on a
 * network with a working controller. The settings stay where they were (the security rules with the controller, the
 * antenna pairing with the antenna and mount). Never ticks; the look ({@link ExpansionBlock#LOAD}) changes only when
 * the device's network or that network's controller does.
 */
public class ServiceDeviceBlockEntity extends StorageDeviceBlockEntity implements ServiceDevice, ChannelFree {
    // Final: channelFree() reads it, and NetworkState assumes that answer never changes while the device is a member.
    private final NetworkService service;
    private final @Nullable BridgeDrain bridge;

    protected ServiceDeviceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        service = state.getBlock() instanceof ServiceDeviceBlock block ? block.service() : NetworkService.SECURITY;
        bridge = service == NetworkService.DIMENSION_BRIDGE ? new BridgeDrain(this) : null;
    }

    /**
     * The block entity for the service device {@code state}: an antenna mount's carries its pairing. Also made for a
     * block of an old world whose data still names the expansion block entity type (see
     * {@link ExpansionContent#EXPANSION_BLOCK_ENTITY}).
     */
    public static ServiceDeviceBlockEntity create(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof ServiceDeviceBlock block && block.service() == NetworkService.ANTENNA_MOUNT) {
            return new AntennaMountBlockEntity(pos, state);
        }
        return new ServiceDeviceBlockEntity(ExpansionContent.SERVICE_DEVICE_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public NetworkService service() {
        return service;
    }

    /** A security module and a network anchor hold no channel (owner 2026-10-17); the other services hold one. */
    @Override
    public boolean channelFree() {
        return service == NetworkService.SECURITY || service == NetworkService.ANCHOR;
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    /** Whether the device is on a network with a working controller, so its service applies. Server side. */
    public boolean active() {
        NetworkState current = network();
        return current != null && current.controllerDevice() instanceof NetworkController;
    }

    private void serviceInfo(NetworkState current, BlockInfoSink sink) {
        switch (service) {
            case WIRELESS_ACCESS -> sink.line(Component.translatable(SignalContent.WIRELESS_RANGE_KEY,
                    InfoFormat.number(WirelessCoverage.range(current.serviceCount(NetworkService.WIRELESS_ACCESS)))));
            case ANCHOR -> {
                if (current.controllerDevice() instanceof ControllerBlockEntity controller) {
                    int held = controller.anchoredChunks();
                    int wanted = controller.anchorWantedChunks();
                    sink.line(Component.translatable(
                            held < wanted ? ExpansionContent.ANCHOR_CAPPED_KEY : ExpansionContent.ANCHOR_KEY,
                            InfoFormat.number(held), InfoFormat.number(wanted), InfoFormat.number(AnchorChunks.cap())));
                }
            }
            case SECURITY -> {
                if (current.controllerDevice() instanceof ControllerBlockEntity controller) {
                    SecuritySettings rules = controller.security();
                    sink.line(Component.translatable(ExpansionContent.SECURITY_KEY,
                            InfoFormat.number(rules.players().size() + rules.teams().size())));
                }
            }
            case ANTENNA_MOUNT, DIMENSION_BRIDGE -> {
                // The antenna mount shows its pairing, the bridge its drain.
            }
        }
    }

    /** FE a dimension bridge drained since it loaded; 0 for every other device. For tests. */
    public long bridgeDrained() {
        BridgeDrain drain = bridge;
        return drain == null ? 0 : drain.drained();
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onLoad() {
        super.onLoad();
        BridgeDrain drain = bridge;
        if (drain != null && level != null && !level.isClientSide()) {
            drain.start();
        }
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        lookAgain();
    }

    @Override
    public void controllerChanged() {
        lookAgain();
    }

    private void lookAgain() {
        BridgeDrain drain = bridge;
        if (drain != null) {
            drain.wake();
        }
        Level current = level;
        if (current != null && !current.isClientSide() && !isRemoved()) {
            showActive(current, worldPosition, active());
        }
    }

    /** Shows {@code active} at {@code pos} if a service device there shows the other; clients only, no neighbours. */
    private static void showActive(Level level, BlockPos pos, boolean active) {
        BlockState state = level.getBlockState(pos);
        int band = active ? 1 : 0;
        if (state.getBlock() instanceof ServiceDeviceBlock && state.getValue(ExpansionBlock.LOAD) != band) {
            level.setBlock(pos, state.setValue(ExpansionBlock.LOAD, band), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Whether the device is active, then what its service does on the network (M7R item 8): the wireless range, the
     * anchor's chunks, the security rules, a bridge's drain. Jade, goggles and the analyzer show these lines; whether
     * it is active at a glance, the rest with the details.
     */
    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        sink.line(Component
                .translatable(active() ? ExpansionContent.DEVICE_ACTIVE_KEY : ExpansionContent.DEVICE_IDLE_KEY));
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        boolean active = active();
        NetworkState current = network();
        if (active && current != null) {
            serviceInfo(current, sink);
        }
        BridgeDrain drain = bridge;
        if (drain != null) {
            sink.line(Component.translatable(DimensionBridge.INFO_KEY,
                    InfoFormat.number(DimensionBridge.drainPerTick()), InfoFormat.number(DimensionBridge.surcharge())));
            if (drain.unpaid() && active()) {
                sink.line(Component.translatable(DimensionBridge.UNPAID_KEY));
            }
        }
    }
}
