package dev.silencebound.maxtech.storage.material;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AmethystBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * A budding crystal of Storage's geodes: on a random tick (vanilla's, no ticker) it grows a small bud on a free face,
 * or moves a bud it faces one stage on, small to medium to large to a cluster, as budding amethyst does. How often, and
 * whether at all, comes from config; the stages are the subclass's. It drops nothing and cannot be moved, so a farm
 * keeps it in place and harvests the clusters (the breaker port's blacklist keeps it from breaking the block itself).
 */
public abstract class BuddingCrystalBlock extends AmethystBlock {
    private static final Direction[] DIRECTIONS = Direction.values();

    private final Supplier<List<AmethystClusterBlock>> stages;
    private final IntSupplier growthChance;
    private final BooleanSupplier enabled;

    /**
     * A budding block growing {@code stages} (smallest bud first, the cluster last) on one random tick in
     * {@code growthChance}, only while {@code enabled}.
     */
    protected BuddingCrystalBlock(Properties properties, Supplier<List<AmethystClusterBlock>> stages,
            IntSupplier growthChance, BooleanSupplier enabled) {
        super(properties);
        this.stages = stages;
        this.growthChance = growthChance;
        this.enabled = enabled;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!enabled.getAsBoolean() || random.nextInt(growthChance.getAsInt()) != 0) {
            return;
        }
        growOn(level, pos, DIRECTIONS[random.nextInt(DIRECTIONS.length)]);
    }

    /**
     * Grows the bud on {@code direction}'s face one stage, or starts one there; nothing when the face holds anything
     * else. Server thread.
     */
    public void growOn(ServerLevel level, BlockPos pos, Direction direction) {
        BlockPos target = pos.relative(direction);
        BlockState there = level.getBlockState(target);
        Block next = nextStage(there, direction);
        if (next != null) {
            level.setBlockAndUpdate(target, next.defaultBlockState().setValue(AmethystClusterBlock.FACING, direction)
                    .setValue(AmethystClusterBlock.WATERLOGGED, there.getFluidState().is(Fluids.WATER)));
        }
    }

    private @Nullable Block nextStage(BlockState there, Direction direction) {
        List<AmethystClusterBlock> growth = stages.get();
        if (BuddingAmethystBlock.canClusterGrowAtState(there)) {
            return growth.getFirst();
        }
        if (!there.hasProperty(AmethystClusterBlock.FACING)
                || there.getValue(AmethystClusterBlock.FACING) != direction) {
            return null;
        }
        // A handful of stages, on a random tick: no hot path.
        for (int i = 0; i < growth.size() - 1; i++) {
            if (there.is(growth.get(i))) {
                return growth.get(i + 1);
            }
        }
        return null;
    }
}
