package dev.silencebound.maxtech.storage.bridge;

import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * The loaded bridges, to keep two bridges between the same pair of networks from both mounting (M7b, no loops): of the
 * bridges in one Storage network facing the same foreign network ({@link ForeignNetwork#identity}), only the one with
 * the lowest block position mounts it and exports into it; the others stand by and take over when it goes. Without this
 * the Storage network would count the foreign contents twice and the foreign network ours twice. Bridges are few, and
 * this is asked only when one attaches, detaches or changes network. Server thread only.
 */
final class BridgeLinks {
    private static final ObjectArrayList<BridgeBlockEntity> LOADED = new ObjectArrayList<>();

    private BridgeLinks() {
    }

    static void add(BridgeBlockEntity bridge) {
        if (!LOADED.contains(bridge)) {
            LOADED.add(bridge);
        }
        changed();
    }

    static void remove(BridgeBlockEntity bridge) {
        LOADED.remove(bridge);
        changed();
    }

    /** Something a bridge's turn depends on changed: every bridge looks again on its next pass. */
    static void changed() {
        for (int i = 0; i < LOADED.size(); i++) {
            LOADED.get(i).linksChanged();
        }
    }

    /**
     * Whether {@code bridge} is the one of its Storage network that mounts its foreign network: no other loaded bridge
     * in the same network faces the same foreign network from a lower position.
     */
    @SuppressWarnings("ReferenceEquality") // a network is one object
    static boolean leads(BridgeBlockEntity bridge) {
        ForeignNetwork foreign = bridge.found();
        NetworkState network = bridge.network();
        if (foreign == null || network == null) {
            return false;
        }
        Object identity = foreign.identity();
        long pos = bridge.getBlockPos().asLong();
        for (int i = 0; i < LOADED.size(); i++) {
            BridgeBlockEntity other = LOADED.get(i);
            ForeignNetwork theirs = other.found();
            if (other != bridge && !other.isRemoved() && other.network() == network && theirs != null
                    && theirs.connected() && theirs.identity().equals(identity) && other.getBlockPos().asLong() < pos) {
                return false;
            }
        }
        return true;
    }
}
