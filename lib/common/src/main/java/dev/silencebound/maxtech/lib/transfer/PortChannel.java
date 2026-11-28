package dev.silencebound.maxtech.lib.transfer;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortSettings;
import dev.silencebound.maxtech.lib.api.transfer.PortStatus;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * One resource kind of a {@link LibTransferPort}: its buffers, a neighbour cache per active face, the round-robin
 * cursor and the status. Server thread only.
 */
abstract class PortChannel {
    /** Faces in {@code get3DDataValue} order, the order of {@code PortFaces}. */
    static final Direction[] FACES = Direction.values();
    /** {@link #operate}: nothing to do until an event. */
    static final int NONE = 0;
    /** {@link #operate}: nothing moved, but a partner may change without telling the port, so poll with backoff. */
    static final int POLL = 1;
    /** {@link #operate}: something moved. */
    static final int MOVED = 2;

    final ResourceKind<?> kind;
    final Set<PortFaceMode> allowed;
    /** The owner's cap per operation ({@code Builder.rate}); {@code Long.MAX_VALUE} without one. */
    final long ownerCap;
    /** The face the next operation tries first; not saved. */
    int cursor;
    PortStatus status = PortStatus.OFF;

    PortChannel(ResourceKind<?> kind, Set<PortFaceMode> allowed, long ownerCap) {
        this.kind = kind;
        this.allowed = Set.copyOf(allowed);
        this.ownerCap = ownerCap;
    }

    /**
     * Makes neighbour caches for the faces active in {@code faces} (packed) that have none, and drops those of faces
     * that turned off. Called on start and on every settings change while started.
     */
    abstract void open(LibTransferPort port, ServerLevel level, BlockPos pos, int faces);

    /** Drops every neighbour cache; the port stopped. */
    abstract void close();

    /** Reads what the channel keeps from the settings (the filter). */
    abstract void settingsChanged(PortSettings settings);

    /** One operation over the faces active in {@code faces}, moving at most {@code amount}; {@link #NONE} etc. */
    abstract int operate(LibTransferPort port, int faces, long amount);

    /** The configured most one operation moves, before the owner's and the player's caps. */
    abstract long configPerOp();

    /** What one operation may move with the player's cap {@code playerCap} (0 for none). */
    final long perOp(long playerCap) {
        long amount = Math.min(configPerOp(), ownerCap);
        return playerCap > 0 ? Math.min(amount, playerCap) : amount;
    }
}
