package dev.silencebound.maxtech.farms.lab;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The lab fluid port block, placed beside a lab tube. Registers no ticker. An empty-hand click tells whether it feeds a
 * lab hub.
 */
public final class FluidPortBlock extends Block implements EntityBlock {
    private static final MapCodec<FluidPortBlock> CODEC = simpleCodec(FluidPortBlock::new);

    public FluidPortBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public FluidPortBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FluidPortBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof FluidPortBlockEntity port) {
            boolean feeds = port.lab().flatMap(LabNetwork::hub).isPresent();
            player.sendOverlayMessage(Component.translatable(
                    feeds ? "message.maxtech_farms.fluid_port.feeds" : "message.maxtech_farms.fluid_port.no_hub"));
        }
        return InteractionResult.SUCCESS;
    }
}
