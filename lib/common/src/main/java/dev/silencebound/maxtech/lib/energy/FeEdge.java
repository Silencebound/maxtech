package dev.silencebound.maxtech.lib.energy;

import dev.silencebound.maxtech.lib.api.energy.EdgeSettings;
import dev.silencebound.maxtech.lib.api.energy.EnergyEdge;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
// PLATFORM-LEAK: FE is NeoForge's energy transfer type; edges reach other mods' blocks through it (MLE).
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Another mod's FE block as an {@link EnergyEdge} of a MaxTech energy network: the neighbour's FE capability on one
 * face, found through a capability cache ({@link FeLookups#BLOCK}) whose invalidation (the block placed, removed or
 * replaced, its chunk loaded or unloaded) makes the edge look again at the end of the tick. Every move runs in its own
 * root transaction and counts only what the neighbour reports as moved. The host makes one per neighbour face it
 * touches, {@link #join joins} it to its network, and {@link #close closes} it when the neighbour or the host goes.
 * Settings come from Lib's server config ({@code energy.*}). Server thread only.
 */
public final class FeEdge extends EnergyEdge {
    private final ServerLevel level;
    private final BlockPos pos;
    private final Direction side;
    private @Nullable CachedBlockLookup<EnergyHandler> cache;

    private FeEdge(ServerLevel level, BlockPos pos, Direction side) {
        super(EnergyNetworks.of(level));
        this.level = level;
        this.pos = pos.immutable();
        this.side = side;
    }

    /**
     * An edge to the FE capability of the block at {@code neighbour} on its face {@code side} (the face that touches
     * the host, so the direction from the neighbour to the host), run by the level's energy scheduler; in no network
     * yet.
     */
    public static FeEdge create(ServerLevel level, BlockPos neighbour, Direction side) {
        return new FeEdge(level, neighbour, side);
    }

    /** The neighbour's position. */
    public BlockPos neighbour() {
        return pos;
    }

    /** The neighbour's face this edge reaches. */
    public Direction side() {
        return side;
    }

    @Override
    protected long simulateInsert(long max) {
        EnergyHandler handler = handler();
        if (handler == null || !canOpenRoot()) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            return handler.insert((int) Math.min(max, MAX_FE_PER_CALL), tx);
        }
    }

    @Override
    protected long insert(long max) {
        EnergyHandler handler = handler();
        if (handler == null || !canOpenRoot()) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int moved = handler.insert((int) Math.min(max, MAX_FE_PER_CALL), tx);
            tx.commit();
            return moved;
        }
    }

    @Override
    protected long simulateExtract(long max) {
        EnergyHandler handler = handler();
        if (handler == null || !canOpenRoot()) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            return handler.extract((int) Math.min(max, MAX_FE_PER_CALL), tx);
        }
    }

    @Override
    protected long extract(long max) {
        EnergyHandler handler = handler();
        if (handler == null || !canOpenRoot()) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int moved = handler.extract((int) Math.min(max, MAX_FE_PER_CALL), tx);
            tx.commit();
            return moved;
        }
    }

    @Override
    protected EdgeSettings settings() {
        return ServerConfig.edgeSettings();
    }

    /** The neighbour's handler, or null when it has none or its chunk is not loaded (the cache never loads it). */
    private @Nullable EnergyHandler handler() {
        if (isClosed()) {
            return null;
        }
        CachedBlockLookup<EnergyHandler> current = cache;
        if (current == null) {
            current = FeLookups.BLOCK.cache(level, pos, side, this::isOpen, this::neighbourChanged);
            cache = current;
        }
        return current.find();
    }

    private boolean isOpen() {
        return !isClosed();
    }

    /**
     * Whether a root transaction may be opened now: not while a caller's transaction is open, such as when a host
     * closes the edge from inside one; the edge then moves nothing.
     */
    private static boolean canOpenRoot() {
        Transaction.Lifecycle lifecycle = Transaction.getLifecycle();
        return lifecycle == Transaction.Lifecycle.NONE || lifecycle == Transaction.Lifecycle.ROOT_CLOSING;
    }
}
