package dev.silencebound.maxtech.storage.creative;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
 * The creative energy source: a storage device that powers its network without limit; using it with an empty hand
 * toggles pushing FE into adjacent blocks. No ticker.
 */
public final class CreativeEnergyBlock extends StorageDeviceBlock {
    public static final MapCodec<CreativeEnergyBlock> CODEC = simpleCodec(CreativeEnergyBlock::new);

    public CreativeEnergyBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CreativeEnergyBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CreativeEnergyBlockEntity source)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            if (!NetworkAccess.canConfigure(player, source)) {
                return InteractionResult.FAIL;
            }
            source.setPushing(!source.pushing());
            player.sendOverlayMessage(Component.translatable(
                    source.pushing() ? CreativeEnergyContent.PUSH_ON_KEY : CreativeEnergyContent.PUSH_OFF_KEY));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof CreativeEnergyBlockEntity source) {
            source.neighbourChanged();
        }
    }
}
