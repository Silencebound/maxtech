package dev.silencebound.maxtech.lib.network;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Decides whether a network fell apart after edges were cut, spread over as many ticks as the node budget needs.
 * <p>
 * Every component left after a cut contains at least one endpoint of a cut edge (a seed), so one flood fill per seed
 * runs, interleaved one node at a time. Fills that meet are unioned; a group of fills that runs dry is a complete
 * component and gets split off. The job ends when one group is left, which keeps the remainder: the cost is about
 * (number of seeds) x (size of the smaller parts), never a walk over the largest part.
 * <p>
 * Any change to the network restarts the job from its seeds, because a node added behind an already expanded fill could
 * reconnect two groups unseen. A huge network that changes every tick could therefore settle late; the trade-off buys a
 * fill that never works on stale adjacency.
 */
final class SplitJob {
    final LongArrayList seeds = new LongArrayList();
    /** The same positions as {@link #seeds}: a mass removal adds thousands, and a list scan per add was quadratic. */
    private final LongOpenHashSet seedSet = new LongOpenHashSet();
    /** Visited node -> index of the fill that reached it first. */
    final Long2IntOpenHashMap owner = new Long2IntOpenHashMap();
    /** Roots of fill groups found to be complete components, in discovery order. */
    final IntArrayList completed = new IntArrayList();
    private LongArrayFIFOQueue[] frontiers = new LongArrayFIFOQueue[0];
    private int[] parent = new int[0];
    /** Per group root: fills of the group whose frontier is not empty yet. */
    private int[] active = new int[0];
    private int fills;
    private int openGroups;
    private boolean started;

    SplitJob() {
        owner.defaultReturnValue(-1);
    }

    void addSeed(long pos) {
        if (seedSet.add(pos)) {
            seeds.add(pos);
        }
        started = false;
    }

    void restart() {
        started = false;
    }

    /** Advances the fills by at most {@code budget} nodes and returns how many it expanded. */
    int run(Network network, Long2ObjectOpenHashMap<Node> nodes, int budget) {
        if (!started) {
            start(network);
        }
        int spent = 0;
        while (openGroups > 1 && spent < budget) {
            int before = spent;
            for (int i = 0; i < fills && spent < budget && openGroups > 1; i++) {
                LongArrayFIFOQueue frontier = frontiers[i];
                if (frontier.isEmpty()) {
                    continue;
                }
                spent++;
                Node node = nodes.get(frontier.dequeueLong());
                if (node != null) {
                    LongArrayList links = node.links;
                    for (int l = 0; l < links.size(); l++) {
                        long next = links.getLong(l);
                        int other = owner.get(next);
                        if (other < 0) {
                            owner.put(next, i);
                            frontier.enqueue(next);
                        } else {
                            union(i, other);
                        }
                    }
                }
                // A frontier only grows from its own expansion, so once empty it stays empty.
                if (frontier.isEmpty()) {
                    int r = root(i);
                    if (--active[r] == 0 && openGroups > 1) {
                        completed.add(r);
                        openGroups--;
                    }
                }
            }
            if (spent == before) {
                // Unreachable while the invariants hold (a drained group always closes); guards against a spin.
                openGroups = 1;
            }
        }
        return spent;
    }

    boolean finished() {
        return started && openGroups <= 1;
    }

    int root(int fill) {
        int r = fill;
        while (parent[r] != r) {
            r = parent[r];
        }
        return r;
    }

    private void start(Network network) {
        for (int i = seeds.size() - 1; i >= 0; i--) {
            long seed = seeds.getLong(i);
            if (!network.contains(seed)) {
                seeds.removeLong(i);
                seedSet.remove(seed);
            }
        }
        fills = seeds.size();
        if (frontiers.length < fills) {
            LongArrayFIFOQueue[] grown = new LongArrayFIFOQueue[fills];
            System.arraycopy(frontiers, 0, grown, 0, frontiers.length);
            for (int i = frontiers.length; i < fills; i++) {
                grown[i] = new LongArrayFIFOQueue();
            }
            frontiers = grown;
            parent = new int[fills];
            active = new int[fills];
        }
        owner.clear();
        completed.clear();
        for (int i = 0; i < fills; i++) {
            frontiers[i].clear();
            parent[i] = i;
            active[i] = 1;
            long seed = seeds.getLong(i);
            owner.put(seed, i);
            frontiers[i].enqueue(seed);
        }
        openGroups = fills;
        started = true;
    }

    private void union(int a, int b) {
        int ra = root(a);
        int rb = root(b);
        if (ra == rb) {
            return;
        }
        int keep = Math.min(ra, rb);
        int drop = Math.max(ra, rb);
        parent[drop] = keep;
        active[keep] += active[drop];
        openGroups--;
    }
}
