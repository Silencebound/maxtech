package dev.silencebound.maxtech.lib.platform;

import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * A sided block capability. {@link #find} is an uncached lookup; per-tick code must hold a {@link #cache} instead
 * (CLAUDE.md, Performance).
 */
public interface BlockLookup<T> {
    /** Returns the capability exposed at {@code pos} on {@code side}, or {@code null} if there is none. */
    @Nullable
    T find(Level level, BlockPos pos, @Nullable Direction side);

    /** A cache for one position and side; cheap to query every tick. */
    CachedBlockLookup<T> cache(ServerLevel level, BlockPos pos, @Nullable Direction side);

    /**
     * Like {@link #cache(ServerLevel, BlockPos, Direction)}, and runs {@code onInvalidate} whenever the result may have
     * changed, as long as {@code isValid} is true. The listener must not query the cache or touch the level; re-query
     * later, e.g. on the next network tick. It fires only after the cache has been queried at least once since the last
     * invalidation. {@code isValid} is only checked when the position is invalidated; if it returns false then, the
     * listener is dropped for good, and if the cache had been queried since the previous invalidation,
     * {@link CachedBlockLookup#find()} throws from then on. Create a new cache rather than reusing one after
     * {@code isValid} turned false.
     */
    CachedBlockLookup<T> cache(ServerLevel level, BlockPos pos, @Nullable Direction side, BooleanSupplier isValid,
            Runnable onInvalidate);
}
