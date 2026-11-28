package dev.silencebound.maxtech.lib.shim.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertPosException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Port only (1.21.1): 26.1 GameTestHelper methods 1.21.1 lacks, as static calls (port/rules/saving.py rewrites
 * {@code helper.m(args)} to {@code GameTestHelpers.m(helper, args)}).
 */
public final class GameTestHelpers {
    private GameTestHelpers() {
    }

    /** 26.1's {@code getBlockEntity(pos, type)}: the block entity at {@code pos}, which must be a {@code type}. */
    public static <T extends BlockEntity> T getBlockEntity(GameTestHelper helper, BlockPos pos, Class<T> type) {
        BlockPos absolute = helper.absolutePos(pos);
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(absolute);
        if (blockEntity == null) {
            throw new GameTestAssertPosException("Missing block entity, expected " + type.getSimpleName(), absolute,
                    pos, helper.getTick());
        }
        if (!type.isInstance(blockEntity)) {
            throw new GameTestAssertPosException("Expected block entity " + type.getSimpleName() + ", got "
                    + blockEntity.getClass().getSimpleName(), absolute, pos, helper.getTick());
        }
        return type.cast(blockEntity);
    }
}
