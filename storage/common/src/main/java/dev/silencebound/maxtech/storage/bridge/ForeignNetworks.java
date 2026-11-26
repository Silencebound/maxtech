package dev.silencebound.maxtech.storage.bridge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Where bridges find the foreign network in front of them (M7b): compat code registers a {@link Finder} for its mod
 * during mod construction, behind its {@code ModList.isLoaded} check, and a bridge asks the finders in turn when it
 * loads or its neighbour changes. With no finder registered (Storage on Lib alone) a bridge finds nothing.
 */
public final class ForeignNetworks {
    private static final List<Finder> FINDERS = new CopyOnWriteArrayList<>();

    private ForeignNetworks() {
    }

    /** Finds a foreign network at a block, for one mod. */
    @FunctionalInterface
    public interface Finder {
        /**
         * The foreign network the block at {@code pos} belongs to, reached through its {@code side} (the face touching
         * the bridge), or null when the block is not one this finder knows. Called on the server thread with the
         * block's chunk loaded; must not load chunks. Each call returns an object for the asking bridge alone, since
         * the bridge mounts its export through it; objects for the same network share an identity.
         */
        @Nullable
        ForeignNetwork find(ServerLevel level, BlockPos pos, Direction side);
    }

    /** Adds {@code finder}; call during mod construction. Finders are asked in the order they were added. */
    public static void register(Finder finder) {
        FINDERS.add(finder);
    }

    /** Removes {@code finder}; for tests that add their own. */
    public static void unregister(Finder finder) {
        FINDERS.remove(finder);
    }

    /**
     * The foreign network at {@code pos} as seen through {@code side}, from the first finder that knows the block, or
     * null. Returns null without asking while the block's chunk is not loaded.
     */
    public static @Nullable ForeignNetwork find(ServerLevel level, BlockPos pos, Direction side) {
        if (FINDERS.isEmpty() || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return null;
        }
        for (Finder finder : FINDERS) {
            ForeignNetwork found = finder.find(level, pos, side);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
