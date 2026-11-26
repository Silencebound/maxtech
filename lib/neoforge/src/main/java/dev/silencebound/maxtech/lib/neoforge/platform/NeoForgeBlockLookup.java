package dev.silencebound.maxtech.lib.neoforge.platform;

import dev.silencebound.maxtech.lib.platform.BlockLookup;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import org.jspecify.annotations.Nullable;

/**
 * The NeoForge side of a {@link BlockLookup}. Public so {@code neoforge} glue in any suite mod can unwrap
 * {@link #of(BlockLookup)} and register providers in {@code RegisterCapabilitiesEvent}.
 */
public record NeoForgeBlockLookup<T>(BlockCapability<T, @Nullable Direction> capability) implements BlockLookup<T> {
    @Override
    public @Nullable T find(Level level, BlockPos pos, @Nullable Direction side) {
        return level.getCapability(capability, pos, side);
    }

    @Override
    public CachedBlockLookup<T> cache(ServerLevel level, BlockPos pos, @Nullable Direction side) {
        BlockCapabilityCache<T, @Nullable Direction> cache = BlockCapabilityCache.create(capability, level, pos, side);
        return cache::getCapability;
    }

    @Override
    public CachedBlockLookup<T> cache(ServerLevel level, BlockPos pos, @Nullable Direction side,
            BooleanSupplier isValid, Runnable onInvalidate) {
        BlockCapabilityCache<T, @Nullable Direction> cache = BlockCapabilityCache.create(capability, level, pos, side,
                isValid, onInvalidate);
        return cache::getCapability;
    }

    /** Throws if {@code lookup} was not created by this platform. */
    public static <T> NeoForgeBlockLookup<T> of(BlockLookup<T> lookup) {
        if (lookup instanceof NeoForgeBlockLookup<T> neoForge) {
            return neoForge;
        }
        throw new IllegalArgumentException("Lookup was not created by the NeoForge platform: " + lookup);
    }
}
