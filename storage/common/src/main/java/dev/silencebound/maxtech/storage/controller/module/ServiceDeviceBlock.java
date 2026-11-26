package dev.silencebound.maxtech.storage.controller.module;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import dev.silencebound.maxtech.storage.p2p.P2PLinkerItem;
import dev.silencebound.maxtech.storage.signal.AntennaLinking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A standalone network device that gives its whole network a {@link NetworkService} (PRD 7.2, Controller; M7R item 4):
 * the security module, network anchor, antenna mount, wireless access mount and dimension bridge. Each was a controller
 * expansion before; now it is placed anywhere on the network, joins through cables or a device it touches like every
 * device, and uses one channel (the security module and network anchor none). The block keeps its old id and
 * {@link ExpansionBlock#LOAD} state, so placed blocks of old worlds load as this device; {@code LOAD} is 1 while the
 * device is on a network with a working controller and 0 otherwise. No block entity renderer, no ticker.
 */
public final class ServiceDeviceBlock extends StorageDeviceBlock {
    public static final MapCodec<ServiceDeviceBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(ModuleKind.CODEC.fieldOf("module").forGetter(ServiceDeviceBlock::kind), propertiesCodec())
            .apply(instance, ServiceDeviceBlock::new));

    private final ModuleKind kind;
    private final NetworkService service;

    public ServiceDeviceBlock(ModuleKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        this.service = service(kind);
        registerDefaultState(stateDefinition.any().setValue(ExpansionBlock.LOAD, 0));
    }

    /** The module kind this device was as an expansion; its research gate and saved index. */
    public ModuleKind kind() {
        return kind;
    }

    /** The service this device gives its network. */
    public NetworkService service() {
        return service;
    }

    /** The service of a {@link ModuleKind#device()} kind. */
    public static NetworkService service(ModuleKind kind) {
        return switch (kind) {
            case SECURITY_MODULE -> NetworkService.SECURITY;
            case NETWORK_ANCHOR -> NetworkService.ANCHOR;
            case WIRELESS_ACCESS_MOUNT -> NetworkService.WIRELESS_ACCESS;
            case ANTENNA_MOUNT -> NetworkService.ANTENNA_MOUNT;
            case DIMENSION_BRIDGE -> NetworkService.DIMENSION_BRIDGE;
            default -> throw new IllegalArgumentException("not a network device: " + kind);
        };
    }

    /** The linking tool pairs an antenna mount with an antenna, as it pairs two antennas. */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (service == NetworkService.ANTENNA_MOUNT && stack.getItem() instanceof P2PLinkerItem) {
            return AntennaLinking.use(level, pos, player, stack);
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    /**
     * Using a security module opens the security screen of its network's controller for the network's owner (and
     * players the owner trusts, and game masters); others get a message. The other devices have no screen.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (service != NetworkService.SECURITY) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof ServiceDeviceBlockEntity device) {
            NetworkState network = device.network();
            ControllerBlockEntity core = network != null
                    && network.controllerDevice() instanceof ControllerBlockEntity controller ? controller : null;
            if (core == null) {
                tell(serverPlayer, ExpansionContent.SECURITY_DARK_KEY);
            } else if (!SecuritySettings.canEdit(serverPlayer, core.ownerRecord())) {
                tell(serverPlayer, ExpansionContent.SECURITY_EDIT_KEY);
            } else {
                SecurityMenu.open(serverPlayer, core, pos, getName());
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static void tell(ServerPlayer player, String key) {
        // A GameTest player has no connection to send the message to.
        if (player.connection != null) {
            player.sendOverlayMessage(Component.translatable(key));
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ExpansionBlock.LOAD);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ServiceDeviceBlockEntity.create(pos, state);
    }
}
