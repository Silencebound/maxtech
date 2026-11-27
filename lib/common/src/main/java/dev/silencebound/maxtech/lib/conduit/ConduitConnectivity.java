package dev.silencebound.maxtech.lib.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.network.NodeConnectivity;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/**
 * Connectivity of one conduit network type, read from the connection masks stored in the conduit block entities. A side
 * keeps its bit while the neighbour's chunk is unloaded, so the graph sees a cut edge there instead of a missing one.
 */
public record ConduitConnectivity(ServerLevel level, Identifier networkType) implements NodeConnectivity {
    private static final Direction[] SIDES = Direction.values();

    @Override
    public void connections(long pos, LongList out) {
        if (!isLoaded(pos) || !(level.getBlockEntity(BlockPos.of(pos)) instanceof ConduitBlockEntity entity)) {
            return;
        }
        ConduitData conduit = entity.findNetwork(networkType);
        if (conduit == null) {
            return;
        }
        int mask = conduit.connections();
        for (Direction side : SIDES) {
            if (ConduitConnections.isConnected(mask, side)) {
                out.add(BlockPos.offset(pos, side));
            }
        }
    }

    @Override
    public boolean isLoaded(long pos) {
        return ConduitBlockEntity.isChunkPresent(level, BlockPos.getX(pos), BlockPos.getZ(pos));
    }
}
