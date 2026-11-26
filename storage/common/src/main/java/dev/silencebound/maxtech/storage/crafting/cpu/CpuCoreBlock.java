package dev.silencebound.maxtech.storage.crafting.cpu;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * A crafting CPU core of one tier (M7 C3, C4): a storage device whose block entity runs jobs. Placing or breaking it
 * makes the other cores it touches count again and gives the blocks around it their cluster connections again
 * ({@link CpuConnections}, B09 item 28); its own connections follow on a one-off scheduled tick, so the state does not
 * change under the block entity being created. No ticker.
 */
public final class CpuCoreBlock extends StorageDeviceBlock {
    private final CpuTier tier;

    public CpuCoreBlock(CpuTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(CpuConnections.withMask(stateDefinition.any(), 0));
    }

    public CpuTier tier() {
        return tier;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(properties -> new CpuCoreBlock(tier, properties));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CpuConnections.properties());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CpuCoreBlockEntity(pos, state);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel server && !oldState.is(this)) {
            // Other cores the new one touches count again (a conflict) now; its own block entity counts on load.
            CpuConnections.refresh(server, pos, StorageServerConfig.CPU_MAX_PARTS.getAsInt(), false);
            server.scheduleTick(pos, this, 1);
        }
    }

    /**
     * The one-off tick a placement or a load (from {@link CpuCoreBlockEntity#onLoad}) schedules: the whole cluster's
     * connections, so clusters saved before they existed connect too.
     */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        CpuConnections.refresh(level, pos, StorageServerConfig.CPU_MAX_PARTS.getAsInt(), true);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
            boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        // The cores that shared parts with this one count again (their conflict ends).
        CpuConnections.refresh(level, pos, StorageServerConfig.CPU_MAX_PARTS.getAsInt(), true);
    }
}
