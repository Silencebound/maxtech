package dev.silencebound.maxtech.storage.logic.controller;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The Logic Controller block: a storage device that holds logic graphs. Redstone into any of its faces is an input
 * graphs can watch; its faces give no signal. Its screen needs the logic access level. Registers no ticker; its graphs
 * run on the network's scheduler when an event asks.
 */
public final class LogicControllerBlock extends StorageDeviceBlock {
    public static final MapCodec<LogicControllerBlock> CODEC = simpleCodec(LogicControllerBlock::new);

    public LogicControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof LogicControllerBlockEntity entity) {
            entity.worker().redstoneChanged();
        }
    }

    /** Opens the screen for players with logic access to the network. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof LogicControllerBlockEntity entity && canEdit(player, entity)) {
            LogicControllerMenu.open(serverPlayer, entity, pos, getName());
        }
        return InteractionResult.SUCCESS;
    }

    /** Whether {@code player} may open the controller and change its graphs: logic access or more. */
    public static boolean canEdit(Player player, LogicControllerBlockEntity entity) {
        return NetworkAccess.level(player, entity).atLeast(AccessLevel.LOGIC);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LogicControllerBlockEntity(pos, state);
    }
}
