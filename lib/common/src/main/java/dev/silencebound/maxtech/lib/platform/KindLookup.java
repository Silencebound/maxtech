package dev.silencebound.maxtech.lib.platform;

import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Cached lookups of one resource kind's storage beside a block ({@link TransferHelper#kind}); on NeoForge the kind's
 * {@code KindBridge}.
 *
 * @param <K>
 *            the key type of the kind
 */
public interface KindLookup<K> {
    /**
     * A cache of the storage at {@code pos} on {@code side}, with the rules of
     * {@link BlockLookup#cache(ServerLevel, BlockPos, Direction, BooleanSupplier, Runnable)}. {@code find} returns the
     * same instance while the block's capability does not change.
     */
    CachedBlockLookup<KindStorage<K>> cache(ServerLevel level, BlockPos pos, Direction side, BooleanSupplier isValid,
            Runnable onInvalidate);
}
