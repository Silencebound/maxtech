package dev.silencebound.maxtech.storage.cable;

import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CableParts;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The smart cables of one storage network and the load bucket each last showed (M6d). The network adds its smart cables
 * when it reads its nodes and clears them on a rebuild; every {@link SmartLoad#INTERVAL} ticks of the network's own
 * tick it reads each one's segment load from the channel counts ({@link NetworkState#segmentLoad}), and only a cable
 * whose bucket changed is looked up and sent to clients. When channel use did not change since the last read and every
 * cable already shows idle, the read is skipped. Server thread.
 */
public final class SmartCables {
    private static final byte UNKNOWN = -1;

    private final LongArrayList cables = new LongArrayList();
    private final Long2ByteOpenHashMap shown = new Long2ByteOpenHashMap();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private long lastVersion = -1;
    private boolean allIdle;

    public SmartCables() {
        shown.defaultReturnValue(UNKNOWN);
    }

    /** Forgets every cable; the next read looks each one up again. */
    public void clear() {
        cables.clear();
        shown.clear();
        lastVersion = -1;
    }

    /** Tracks the smart cable at {@code pos}; adding one twice keeps it once. */
    public void add(long pos) {
        if (!shown.containsKey(pos)) {
            cables.add(pos);
            shown.put(pos, UNKNOWN);
            lastVersion = -1;
        }
    }

    /** Stops tracking the cable at {@code pos} (removed); nothing happens if it was not tracked. */
    public void remove(long pos) {
        if (shown.containsKey(pos)) {
            shown.remove(pos);
            cables.rem(pos);
        }
    }

    /** How many smart cables the network has. */
    public int size() {
        return cables.size();
    }

    /** Whether {@code gameTime} is this network's turn to read its smart cables; {@code offset} staggers networks. */
    public static boolean due(long gameTime, int offset) {
        return (gameTime + offset) % SmartLoad.INTERVAL == 0;
    }

    /**
     * Reads every smart cable's load from {@code network} and pushes changed buckets to the cables. {@code version} is
     * the network's {@link NetworkState#channelVersion()}.
     */
    public void refresh(NetworkState network, ServerLevel level, long version) {
        if (cables.isEmpty() || (version == lastVersion && allIdle)) {
            return;
        }
        lastVersion = version;
        boolean idle = true;
        for (int i = 0; i < cables.size(); i++) {
            long pos = cables.getLong(i);
            int bucket = SmartLoad.bucket(network.segmentLoad(pos));
            idle &= bucket == SmartLoad.IDLE;
            if (shown.get(pos) == bucket) {
                continue;
            }
            cursor.set(pos);
            // An unloaded cable keeps its old bucket here, so it is looked up again once it is back.
            if (level.isLoaded(cursor) && level.getBlockEntity(cursor) instanceof ConduitBlockEntity cable
                    && cable.conduit().state() instanceof CableParts parts) {
                parts.setLoad(bucket);
                shown.put(pos, (byte) bucket);
            } else {
                idle = false;
            }
        }
        allIdle = idle;
    }
}
