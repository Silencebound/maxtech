package dev.silencebound.maxtech.storage.crafting.cpu;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * A crafting CPU storage, lane or cache block (M7 C3): a plain block with no block entity that counts for every core
 * its cluster touches. Placing or breaking one makes those cores count their cluster again and the cluster's blocks
 * take their connections again ({@link CpuConnections}, B09 item 28); after a placement the new block takes its own on
 * a one-off scheduled tick, so its state does not change while it is being placed. No ticker.
 */
public final class CpuPartBlock extends Block {
    private final CpuPart part;

    public CpuPartBlock(CpuPart part, Properties properties) {
        super(properties);
        this.part = part;
        registerDefaultState(CpuConnections.withMask(stateDefinition.any(), 0));
    }

    public CpuPart part() {
        return part;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(properties -> new CpuPartBlock(part, properties));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CpuConnections.properties());
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel server && !oldState.is(this)) {
            CpuConnections.refresh(server, pos, StorageServerConfig.CPU_MAX_PARTS.getAsInt(), false);
            server.scheduleTick(pos, this, 1);
        }
    }

    /** The one-off tick a placement schedules: the new block's own connections. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        CpuConnections.settle(level, pos);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
            boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        CpuConnections.refresh(level, pos, StorageServerConfig.CPU_MAX_PARTS.getAsInt(), true);
    }
}
