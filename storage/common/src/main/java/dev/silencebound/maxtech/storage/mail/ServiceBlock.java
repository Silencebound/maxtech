package dev.silencebound.maxtech.storage.mail;

import dev.silencebound.maxtech.storage.network.LinkLook;
import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.board.BoardMenu;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The network service blocks of M6e area G, the mail port and the request board: storage devices facing their placer
 * that open their screen for players with view access to their network. They hold nothing themselves (mail and requests
 * live in server-wide saved data) and never tick.
 */
public final class ServiceBlock extends StorageDeviceBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final String DENIED_KEY = "message." + MaxtechStorage.MOD_ID + ".service.denied";
    public static final MapCodec<ServiceBlock> MAIL_PORT_CODEC = simpleCodec(
            properties -> new ServiceBlock(Service.MAIL_PORT, properties));
    public static final MapCodec<ServiceBlock> REQUEST_BOARD_CODEC = simpleCodec(
            properties -> new ServiceBlock(Service.REQUEST_BOARD, properties));

    /** Which service a block is. */
    public enum Service {
        MAIL_PORT, REQUEST_BOARD
    }

    private final Service service;

    public ServiceBlock(Service service, Properties properties) {
        super(properties);
        this.service = service;
        registerDefaultState(
                stateDefinition.any().setValue(LinkLook.PROPERTY, LinkLook.OFFLINE).setValue(FACING, Direction.NORTH));
    }

    public Service service() {
        return service;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return switch (service) {
            case MAIL_PORT -> MAIL_PORT_CODEC;
            case REQUEST_BOARD -> REQUEST_BOARD_CODEC;
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LinkLook.PROPERTY);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof ServiceBlockEntity entity) {
            if (!NetworkAccess.level(serverPlayer, entity).atLeast(AccessLevel.VIEW)) {
                if (serverPlayer.connection != null) {
                    serverPlayer.sendOverlayMessage(Component.translatable(DENIED_KEY));
                }
                return InteractionResult.SUCCESS;
            }
            MailService.remember(serverPlayer);
            switch (service) {
                case MAIL_PORT -> MailMenu.open(serverPlayer, entity, getName());
                case REQUEST_BOARD -> BoardMenu.open(serverPlayer, entity, getName());
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public ServiceBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ServiceBlockEntity(pos, state);
    }
}
