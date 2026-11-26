package dev.silencebound.maxtech.storage.terminal;

import dev.silencebound.maxtech.storage.network.LinkLook;
import com.mojang.serialization.MapCodec;
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
 * The item terminal block: a full block whose screen faces the player who placed it; it opens the terminal GUI, unless
 * the network's owner settings keep the player out, and links to the network on every side but the screen (see
 * {@code TerminalBlockEntity#linksOn}). The terminal part is the same terminal on a cable's face. No ticker.
 */
public final class TerminalBlock extends StorageDeviceBlock {
    public static final MapCodec<TerminalBlock> CODEC = simpleCodec(TerminalBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public TerminalBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                stateDefinition.any().setValue(LinkLook.PROPERTY, LinkLook.OFFLINE).setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
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
    public TerminalBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TerminalBlockEntity(pos, state);
    }

    /** Opens the GUI, or says why not; both sides report success so the client swings the arm. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof TerminalBlockEntity terminal) {
            if (terminal.canAccess(serverPlayer)) {
                TerminalMenu.open(serverPlayer, terminal, pos, getName());
            } else {
                serverPlayer.sendOverlayMessage(Component.translatable(TerminalContent.DENIED_KEY));
            }
        }
        return InteractionResult.SUCCESS;
    }
}
