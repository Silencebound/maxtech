package dev.silencebound.maxtech.lib.neoforge.api.transfer;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * How transfer ports reach one resource kind in the world: the kind's capability, wrapped as {@link KindHandler}s. Lib
 * registers bridges for items and fluids ({@link KindBridges}); a compat module registers its kind's (Mekanism
 * chemicals, say). Energy moves through Lib's FE edges instead and has no bridge. Implementations are immutable and
 * safe from any thread; the caches they make are server thread only.
 *
 * @param <K>
 *            the key type of the kind
 */
@ApiStatus.Experimental
public interface KindBridge<K> {
    /**
     * A cache of the handler the block at {@code pos} offers on {@code side} (its face towards the port), built on
     * NeoForge's {@code BlockCapabilityCache}: {@code onInvalidate} runs when it may have changed, while
     * {@code isValid} holds. The listener must not read the cache or touch the level.
     */
    Cache<K> cache(ServerLevel level, BlockPos pos, Direction side, BooleanSupplier isValid, Runnable onInvalidate);

    /** A cached handler lookup. */
    @ApiStatus.NonExtendable
    interface Cache<K> {
        /**
         * The handler there now, or empty. Returns the same instance while the block's capability does not change, so a
         * port can compare by identity; allocates only when it changes.
         */
        Optional<KindHandler<K>> handler();
    }
}
