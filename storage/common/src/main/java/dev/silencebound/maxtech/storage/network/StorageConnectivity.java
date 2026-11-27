package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.conduit.ConduitData;
import dev.silencebound.maxtech.lib.api.network.NodeConnectivity;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * How storage nodes link: cables to cables by Lib's conduit rule (same network type, same colour or uncoloured, no
 * disabled face; the cable keeps that mask), cables to the devices they touch unless the cable's face is disabled, and
 * devices to devices they touch, each only on the sides the device links on ({@link StorageDeviceBlockEntity#linksOn}).
 * Controller cluster blocks ({@link ClusterNode}) link to each other; those that do not link outside
 * ({@link ClusterNode#linksOutside()}: every expansion but the channel array) link to nothing else. A cable face
 * covered by a part ({@link DeviceHost#cutsLink}) links to nothing; the part sits on the cable's node. A toggle bus
 * sits in the cable's line and keeps its face linked while it passes the link on. A side whose chunk is unloaded keeps
 * the link it had (the cable's block faces, the device's saved link mask), so the graph sees a cut edge there. A
 * {@link RemoteLinkNode} adds the far ends it links to. Asked only when a node is added or invalidated, never per tick.
 * Server thread only.
 */
record StorageConnectivity(ServerLevel level, Identifier networkId) implements NodeConnectivity {
    private static final Direction[] SIDES = Direction.values();

    @Override
    public void connections(long pos, LongList out) {
        if (!isLoaded(pos)) {
            return;
        }
        BlockEntity entity = level.getBlockEntity(BlockPos.of(pos));
        if (entity instanceof ConduitBlockEntity conduit) {
            ConduitData data = conduit.findNetwork(networkId);
            if (data != null) {
                cableLinks(pos, data, out);
            }
        } else if (entity instanceof StorageDeviceBlockEntity device && device.networkId().equals(networkId)) {
            deviceLinks(pos, device, out);
        }
    }

    private void cableLinks(long pos, ConduitData cable, LongList out) {
        int conduits = cable.connections();
        for (Direction side : SIDES) {
            long neighbour = BlockPos.offset(pos, side);
            if (cut(cable, side)) {
                // The part's disabled face keeps Lib from linking there; this keeps a wrench from undoing it.
                continue;
            }
            if (ConduitConnections.isConnected(conduits, side)) {
                if (cutNeighbour(neighbour, side.getOpposite())) {
                    continue;
                }
                out.add(neighbour);
            } else if (cable.faceMode(side) != ConduitFaceMode.DISABLED && (isLoaded(neighbour)
                    ? linksBack(neighbour, side.getOpposite())
                    : ConduitConnections.isConnected(cable.blockFaces(), side))) {
                out.add(neighbour);
            }
        }
    }

    private void deviceLinks(long pos, StorageDeviceBlockEntity device, LongList out) {
        boolean cluster = device instanceof ClusterNode;
        boolean closed = closed(device);
        int mask = device.linkMask();
        for (Direction side : SIDES) {
            long neighbour = BlockPos.offset(pos, side);
            int bit = ConduitConnections.bit(side);
            if (!device.linksOn(side)) {
                mask &= ~bit;
                continue;
            }
            if (!isLoaded(neighbour)) {
                if ((mask & bit) != 0) {
                    out.add(neighbour);
                }
                continue;
            }
            if (linksDevice(neighbour, side.getOpposite(), cluster, closed)) {
                out.add(neighbour);
                mask |= bit;
            } else {
                mask &= ~bit;
            }
        }
        device.setLinkMask(mask);
        if (device instanceof RemoteLinkNode remote) {
            remote.remoteLinks(out);
        }
    }

    /**
     * Whether the loaded node at {@code pos} links on its {@code side} to a device that is a cluster block
     * ({@code cluster}) and that may link only within its cluster ({@code closed}).
     */
    private boolean linksDevice(long pos, Direction side, boolean cluster, boolean closed) {
        BlockEntity entity = level.getBlockEntity(BlockPos.of(pos));
        if (entity instanceof ConduitBlockEntity conduit) {
            ConduitData data = conduit.findNetwork(networkId);
            return !closed && data != null && data.faceMode(side) != ConduitFaceMode.DISABLED && !cut(data, side);
        }
        if (!(entity instanceof StorageDeviceBlockEntity other) || !other.networkId().equals(networkId)
                || !other.linksOn(side)) {
            return false;
        }
        return (!closed || other instanceof ClusterNode) && (!closed(other) || cluster);
    }

    /** Whether the loaded position {@code pos} holds a device of this network that links to a cable on {@code side}. */
    private boolean linksBack(long pos, Direction side) {
        return level.getBlockEntity(BlockPos.of(pos)) instanceof StorageDeviceBlockEntity device
                && device.networkId().equals(networkId) && device.linksOn(side) && !closed(device);
    }

    /** A cluster block that links only to other cluster blocks. */
    private static boolean closed(BlockEntity entity) {
        return entity instanceof ClusterNode node && !node.linksOutside();
    }

    private static boolean cut(ConduitData cable, Direction side) {
        return cable.state() instanceof DeviceHost host && host.cutsLink(side);
    }

    /** Whether the loaded cable at {@code pos} has a part on its {@code side} that cuts the link there. */
    private boolean cutNeighbour(long pos, Direction side) {
        if (isLoaded(pos) && level.getBlockEntity(BlockPos.of(pos)) instanceof ConduitBlockEntity conduit) {
            ConduitData data = conduit.findNetwork(networkId);
            return data != null && cut(data, side);
        }
        return false;
    }

    @Override
    public boolean isLoaded(long pos) {
        return ConduitBlockEntity.isChunkPresent(level, BlockPos.getX(pos), BlockPos.getZ(pos));
    }
}
