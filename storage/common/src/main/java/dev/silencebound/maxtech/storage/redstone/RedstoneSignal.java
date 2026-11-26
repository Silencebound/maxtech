package dev.silencebound.maxtech.storage.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads whether a block gets a redstone signal the way vanilla's {@code Level.hasNeighborSignal} does (a neighbour's
 * weak signal, or a strong signal into a conducting neighbour), but never loads a chunk: a position in a chunk that is
 * not loaded counts as unknown. Called on neighbour updates and once when a device starts; server thread.
 */
public final class RedstoneSignal {
    /** {@link #read}: some neighbour gives a signal. */
    public static final int POWERED = 1;
    /** {@link #read}: every neighbour was read and none gives a signal. */
    public static final int UNPOWERED = 0;
    /** {@link #read}: no loaded neighbour gives a signal, but some neighbour was not loaded. */
    public static final int UNKNOWN = -1;

    private static final Direction[] DIRECTIONS = Direction.values();

    private RedstoneSignal() {
    }

    /** {@link #POWERED}, {@link #UNPOWERED} or {@link #UNKNOWN} for the block at {@code pos}. */
    public static int read(Level level, BlockPos pos) {
        boolean unknown = false;
        for (Direction direction : DIRECTIONS) {
            BlockPos neighbour = pos.relative(direction);
            if (!level.isLoaded(neighbour)) {
                unknown = true;
                continue;
            }
            BlockState state = level.getBlockState(neighbour);
            if (state.getSignal(level, neighbour, direction) > 0) {
                return POWERED;
            }
            if (state.isRedstoneConductor(level, neighbour)) {
                for (Direction into : DIRECTIONS) {
                    BlockPos source = neighbour.relative(into);
                    if (!level.isLoaded(source)) {
                        unknown = true;
                    } else if (level.getDirectSignal(source, into) > 0) {
                        return POWERED;
                    }
                }
            }
        }
        return unknown ? UNKNOWN : UNPOWERED;
    }
}
