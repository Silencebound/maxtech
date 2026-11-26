package dev.silencebound.maxtech.storage.analyzer;

import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * The route from a node to its network's root, segment by segment, with each segment's kind, channels and load (PRD
 * 7.2: the analyzer lights a device's route). Read from the network's routes on request. Server thread.
 *
 * @param segments
 *            nearest the node first
 * @param truncated
 *            whether the route went on past the last segment listed
 */
public record RouteTrace(List<Segment> segments, boolean truncated) {
    /** Most segments a trace lists; a bound against a broken tree, far above any real route. */
    public static final int MAX_SEGMENTS = 4096;

    /** What carries a segment. */
    public enum Kind {
        /** A storage cable towards its parent. */
        CABLE,
        /** A device towards its parent. */
        DEVICE,
        /** An antenna link: the parent is not a neighbour. */
        ANTENNA,
        /** A root link into the controller's cluster. */
        ROOT_LINK
    }

    /**
     * One segment, from {@code pos} to {@code parent}.
     *
     * @param tier
     *            the cable tier at {@code pos}, 0 for a device
     * @param rate
     *            channels the segment carries
     * @param load
     *            share of them in use, 0 to 100
     */
    public record Segment(long pos, long parent, Kind kind, int tier, long rate, int load) {
    }

    /** The trace of {@code pos} in {@code network}; empty for the root or a node without a route. */
    public static RouteTrace of(NetworkState network, long pos) {
        List<Segment> segments = new ArrayList<>();
        long node = pos;
        while (segments.size() < MAX_SEGMENTS) {
            long parent = network.routeParent(node);
            if (parent == Long.MIN_VALUE) {
                return new RouteTrace(segments, false);
            }
            segments.add(new Segment(node, parent, kind(network, node, parent), Math.max(0, network.nodeTier(node)),
                    network.segmentCapacity(node), network.segmentLoad(node)));
            node = parent;
        }
        return new RouteTrace(segments, network.routeParent(node) != Long.MIN_VALUE);
    }

    /** What carries the segment from {@code node} to {@code parent}. */
    public static Kind kind(NetworkState network, long node, long parent) {
        if (!network.isAdHoc() && network.isRouteRoot(parent)) {
            return Kind.ROOT_LINK;
        }
        if (BlockPos.of(node).distManhattan(BlockPos.of(parent)) != 1) {
            return Kind.ANTENNA;
        }
        return network.nodeTier(node) > 0 ? Kind.CABLE : Kind.DEVICE;
    }

    /** The busiest segment, nearest the node among equals; null for an empty trace. */
    public @Nullable Segment busiest() {
        Segment best = null;
        for (Segment segment : segments) {
            if (best == null || segment.load() > best.load()) {
                best = segment;
            }
        }
        return best;
    }

    /** The slowest segment, which limits the route; nearest the node among equals; null for an empty trace. */
    public @Nullable Segment narrowest() {
        Segment best = null;
        for (Segment segment : segments) {
            if (best == null || segment.rate() < best.rate()) {
                best = segment;
            }
        }
        return best;
    }

    /** How many segments are antenna links. */
    public int antennaHops() {
        int hops = 0;
        for (Segment segment : segments) {
            if (segment.kind() == Kind.ANTENNA) {
                hops++;
            }
        }
        return hops;
    }
}
