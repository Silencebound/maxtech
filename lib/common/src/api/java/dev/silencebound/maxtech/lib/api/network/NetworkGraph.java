package dev.silencebound.maxtech.lib.api.network;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * The topology of one network type in one level. Changes are queued by {@link NetworkManager} and applied in
 * {@link #tick}: adding or removing a node touches only its neighbours, joining networks moves the smaller one, and
 * possible splits are resolved by a {@link SplitJob} under a shared per-tick node budget. Nothing is persisted; node
 * owners re-add their nodes when they load. Server thread only.
 * <p>
 * Experimental (Lib 0.14.0) until Farms, its first addon user, settles it.
 */
@ApiStatus.Experimental
public final class NetworkGraph implements RouteTree.Links {
    private static final byte ADD = 0;
    private static final byte REMOVE = 1;
    private static final byte INVALIDATE = 2;
    private static final byte ANCHOR = 3;
    private static final byte UNANCHOR = 4;
    /** Cut edges whose target loaded without a node coming back are dropped on this cadence. */
    static final int STALE_EDGE_INTERVAL = 20;
    /** Whether ticks record their cost; see {@link #setProfiling}. */
    private static boolean profiling;
    /**
     * Bumped by every {@link #setProfiling} call; a tick time recorded under an older value reads as -1, so no graph or
     * network needs to be visited to forget its times.
     */
    private static int profilingEpoch;

    private final NodeConnectivity connectivity;
    private final NetworkLogic logic;
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final ObjectArrayList<Network> networks = new ObjectArrayList<>();
    private final ObjectArrayList<Network> splitting = new ObjectArrayList<>();
    private final ObjectArrayList<Network> dirty = new ObjectArrayList<>();
    /** Target of a cut edge -> nodes holding that edge, so a loading node can find who was waiting for it. */
    private final Long2ObjectOpenHashMap<LongArrayList> unloadedTargets = new Long2ObjectOpenHashMap<>();
    private final LongArrayList opPos = new LongArrayList();
    private final ByteArrayList opKind = new ByteArrayList();
    private final LongArrayList scratch = new LongArrayList();
    private int nextId;
    private long ticks;
    private long lastTickNanos = -1;
    private int lastTickEpoch;

    /**
     * A graph asking {@code connectivity} for links and reporting to {@code logic}. For Lib and tests; addons get
     * graphs from {@link NetworkManager#graph}.
     */
    @ApiStatus.Internal
    public NetworkGraph(NodeConnectivity connectivity, NetworkLogic logic) {
        this.connectivity = connectivity;
        this.logic = logic;
    }

    /**
     * Turns tick timing on or off for every graph: while on, {@link #lastTickNanos()} and
     * {@link Network#lastTickNanos()} hold the cost of the latest tick. Off by default, where it costs one static read
     * per graph and network per tick. Either way every recorded time goes back to -1, so a time from an earlier
     * profiling run is never read as a new one. For perf tests and profiling; server thread only.
     */
    @ApiStatus.Internal
    public static void setProfiling(boolean enabled) {
        profiling = enabled;
        profilingEpoch++;
    }

    /** Nanoseconds the latest profiled {@link #tick} took; -1 while profiling is off and until it has timed one. */
    public long lastTickNanos() {
        return lastTickEpoch == profilingEpoch ? lastTickNanos : -1;
    }

    /** What {@link Network#lastTickNanos()} reports for a time recorded under {@code epoch}. */
    static long profiledNanos(long nanos, int epoch) {
        return epoch == profilingEpoch ? nanos : -1;
    }

    /** The transport semantics this graph reports to. */
    public NetworkLogic logic() {
        return logic;
    }

    /** How this graph sees the world, e.g. for a logic that must tell an unloaded neighbour from a missing one. */
    public NodeConnectivity connectivity() {
        return connectivity;
    }

    /** Queues a node at {@code pos}; if one is already there this re-reads its connections. */
    public void add(long pos) {
        queue(pos, ADD);
    }

    /** Queues removal of the node at {@code pos}, for a broken block or an unloading chunk alike. */
    public void remove(long pos) {
        queue(pos, REMOVE);
    }

    /** Queues a re-read of the connections of the node at {@code pos}. */
    public void invalidate(long pos) {
        queue(pos, INVALIDATE);
    }

    /**
     * Queues setting or clearing the anchor flag of the node at {@code pos}; see {@link NetworkManager#setAnchored}.
     */
    public void setAnchored(long pos, boolean anchored) {
        queue(pos, anchored ? ANCHOR : UNANCHOR);
    }

    /** The network the node at {@code pos} belongs to, or {@code null} if no node is there (yet). */
    public @Nullable Network network(long pos) {
        Node node = nodes.get(pos);
        return node == null ? null : node.network;
    }

    /**
     * Appends the packed positions of the nodes linked to the node at {@code pos}; appends nothing if there is none.
     */
    @Override
    public void links(long pos, LongList out) {
        Node node = nodes.get(pos);
        if (node != null) {
            out.addAll(node.links);
        }
    }

    /**
     * The widest-path tree of {@code network} from its node at {@code root}, over edges as wide as {@code width} says;
     * call it from {@link NetworkLogic#onRebuilt}, when the node set is final. Throws {@link IllegalArgumentException}
     * when {@code root} is not one of the network's nodes.
     */
    public RouteTree routeTree(Network network, long root, RouteTree.EdgeWidth width) {
        if (!network.contains(root)) {
            throw new IllegalArgumentException("Root " + root + " is not in " + network);
        }
        return RouteTree.build(root, this, width, network.size());
    }

    /** Whether the nodes at {@code a} and {@code b} both exist and share an edge. O(1). */
    public boolean isLinked(long a, long b) {
        Node node = nodes.get(a);
        return node != null && node.links.contains(b);
    }

    /** Nodes in the graph, across all its networks. */
    public int nodeCount() {
        return nodes.size();
    }

    /** Live networks; a view that changes during {@link #tick}. */
    public ObjectArrayList<Network> networks() {
        return networks;
    }

    private void queue(long pos, byte kind) {
        opPos.add(pos);
        opKind.add(kind);
    }

    /**
     * Applies queued changes, spends up to {@code splitBudget} nodes on pending split checks, reports rebuilt networks
     * and ticks the active ones. Returns the split budget left.
     */
    @ApiStatus.Internal
    public int tick(UnloadedChunkMode mode, boolean allowAnchors, int splitBudget) {
        if (!profiling) {
            return tickNow(mode, allowAnchors, splitBudget);
        }
        long start = System.nanoTime();
        int left = tickNow(mode, allowAnchors, splitBudget);
        lastTickNanos = System.nanoTime() - start;
        lastTickEpoch = profilingEpoch;
        return left;
    }

    private int tickNow(UnloadedChunkMode mode, boolean allowAnchors, int splitBudget) {
        applyQueued();
        if (++ticks % STALE_EDGE_INTERVAL == 0 && !unloadedTargets.isEmpty()) {
            dropStaleUnloadedEdges();
        }
        int left = runSplits(splitBudget);
        fireRebuilt();
        tickNetworks(mode, allowAnchors);
        return left;
    }

    private void applyQueued() {
        // Size is re-read so changes queued by callbacks during this pass land in the same tick.
        for (int i = 0; i < opPos.size(); i++) {
            long pos = opPos.getLong(i);
            switch (opKind.getByte(i)) {
                case ADD -> addNow(pos);
                case REMOVE -> removeNow(pos);
                case INVALIDATE -> {
                    Node node = nodes.get(pos);
                    if (node != null) {
                        refresh(node);
                    }
                }
                case ANCHOR -> setAnchorNow(pos, true);
                case UNANCHOR -> setAnchorNow(pos, false);
                default -> throw new IllegalStateException("Unknown op " + opKind.getByte(i));
            }
        }
        opPos.clear();
        opKind.clear();
    }

    private void addNow(long pos) {
        Node existing = nodes.get(pos);
        if (existing != null) {
            refresh(existing);
            return;
        }
        LongArrayList waiting = unloadedTargets.remove(pos);
        if (waiting != null) {
            // Those edges are re-created as real links below if the connection still exists.
            for (int i = 0; i < waiting.size(); i++) {
                Node source = nodes.get(waiting.getLong(i));
                if (source != null) {
                    dropUnloadedLink(source, pos, false);
                    touch(source.network);
                }
            }
        }
        scratch.clear();
        connectivity.connections(pos, scratch);
        Network target = null;
        for (int i = 0; i < scratch.size(); i++) {
            Node other = nodes.get(scratch.getLong(i));
            if (other != null && (target == null || other.network.size() > target.size())) {
                target = other.network;
            }
        }
        boolean created = target == null;
        if (target == null) {
            target = newNetwork();
        }
        Node node = new Node(pos, target);
        nodes.put(pos, node);
        target.addNode(node);
        if (created) {
            logic.onNetworkCreated(target);
        }
        for (int i = 0; i < scratch.size(); i++) {
            connect(node, scratch.getLong(i));
        }
        logic.onNodeAdded(node.network, pos);
        touch(node.network);
    }

    private void removeNow(long pos) {
        Node node = nodes.remove(pos);
        if (node == null) {
            return;
        }
        Network network = node.network;
        network.removeNode(node);
        clearUnloadedLinks(node);
        // A node removed because its chunk unloaded leaves its neighbours with an edge into that chunk.
        boolean unloaded = !connectivity.isLoaded(pos);
        LongArrayList links = node.links;
        for (int i = 0; i < links.size(); i++) {
            Node other = nodes.get(links.getLong(i));
            if (other != null) {
                other.links.rem(pos);
                if (unloaded) {
                    addUnloadedLink(other, pos);
                }
            }
        }
        logic.onNodeRemoved(network, pos);
        if (network.size() == 0) {
            discard(network);
            logic.onNetworkRemoved(network);
            return;
        }
        touch(network);
        // With a check pending, the removed node may have been the seed standing for its part, so its neighbours
        // take over even when it was a leaf.
        if (links.size() > 1 || network.split != null) {
            for (int i = 0; i < links.size(); i++) {
                addSplitSeed(network, links.getLong(i));
            }
        }
    }

    /**
     * Re-reads the connections of {@code node}. The network is only marked changed when a link or cut edge actually
     * changed: owners invalidate on every neighbour update, and a false change would rebuild caches and restart pending
     * split checks for nothing.
     */
    private void refresh(Node node) {
        scratch.clear();
        connectivity.connections(node.pos, scratch);
        boolean changed = false;
        LongArrayList links = node.links;
        for (int i = links.size() - 1; i >= 0; i--) {
            long linked = links.getLong(i);
            if (!scratch.contains(linked)) {
                links.removeLong(i);
                changed = true;
                Node other = nodes.get(linked);
                if (other != null) {
                    other.links.rem(node.pos);
                    addSplitSeed(node.network, node.pos);
                    addSplitSeed(node.network, linked);
                }
            }
        }
        LongArrayList unloaded = node.unloaded;
        if (unloaded != null) {
            for (int i = unloaded.size() - 1; i >= 0; i--) {
                long target = unloaded.getLong(i);
                if (!scratch.contains(target) || connectivity.isLoaded(target)) {
                    dropUnloadedLink(node, target, true);
                    changed = true;
                }
            }
        }
        for (int i = 0; i < scratch.size(); i++) {
            changed |= connect(node, scratch.getLong(i));
        }
        if (changed) {
            touch(node.network);
        }
        logic.onNodeInvalidated(node.network, node.pos);
    }

    /** Returns whether a link, a merge or a cut edge was added. */
    private boolean connect(Node node, long other) {
        if (other == node.pos) {
            return false;
        }
        Node neighbour = nodes.get(other);
        boolean changed = false;
        if (neighbour != null) {
            if (!node.links.contains(other)) {
                node.links.add(other);
                neighbour.links.add(node.pos);
                changed = true;
            }
            if (neighbour.network != node.network) {
                merge(node.network, neighbour.network);
                changed = true;
            }
        } else if (!node.hasUnloaded(other) && !connectivity.isLoaded(other)) {
            addUnloadedLink(node, other);
            changed = true;
        }
        return changed;
    }

    private void setAnchorNow(long pos, boolean anchored) {
        Node node = nodes.get(pos);
        if (node == null || node.anchor == anchored) {
            return;
        }
        node.anchor = anchored;
        node.network.anchors += anchored ? 1 : -1;
    }

    /** Moves the smaller network into the larger one; the cost is linear in the smaller side. */
    private void merge(Network a, Network b) {
        Network survivor = a.size() >= b.size() ? a : b;
        Network absorbed = survivor == a ? b : a;
        LongIterator it = absorbed.nodes.iterator();
        while (it.hasNext()) {
            Node node = nodes.get(it.nextLong());
            if (node != null) {
                node.network = survivor;
                survivor.addNode(node);
            }
        }
        absorbed.nodes.clear();
        absorbed.nodeChunks.clear();
        absorbed.unloadedChunks.clear();
        absorbed.anchors = 0;
        SplitJob job = absorbed.split;
        if (job != null) {
            for (int i = 0; i < job.seeds.size(); i++) {
                addSplitSeed(survivor, job.seeds.getLong(i));
            }
        }
        discard(absorbed);
        logic.onMerged(survivor, absorbed);
        touch(survivor);
    }

    private int runSplits(int budget) {
        int left = budget;
        int i = 0;
        while (i < splitting.size() && left > 0) {
            Network network = splitting.get(i);
            SplitJob job = network.split;
            if (job == null) {
                splitting.remove(i);
                continue;
            }
            left -= job.run(network, nodes, left);
            if (job.finished()) {
                network.split = null;
                splitting.remove(i);
                applySplit(network, job);
            } else {
                i++;
            }
        }
        if (left <= 0 && splitting.size() > 1) {
            // Rotate so one huge split cannot starve the others of budget.
            splitting.add(splitting.remove(0));
        }
        return left;
    }

    private void applySplit(Network original, SplitJob job) {
        IntArrayList roots = job.completed;
        if (roots.isEmpty()) {
            return;
        }
        Network[] parts = new Network[job.seeds.size()];
        for (int i = 0; i < roots.size(); i++) {
            parts[roots.getInt(i)] = newNetwork();
        }
        ObjectIterator<Long2IntMap.Entry> it = Long2IntMaps.fastIterator(job.owner);
        while (it.hasNext()) {
            Long2IntMap.Entry entry = it.next();
            Network part = parts[job.root(entry.getIntValue())];
            if (part == null) {
                // Visited by a fill of the part that keeps the original network.
                continue;
            }
            Node node = nodes.get(entry.getLongKey());
            if (node != null) {
                original.removeNode(node);
                node.network = part;
                part.addNode(node);
            }
        }
        job.owner.clear();
        for (int i = 0; i < roots.size(); i++) {
            Network part = parts[roots.getInt(i)];
            logic.onSplit(original, part);
            touch(part);
        }
        touch(original);
    }

    private void fireRebuilt() {
        int kept = 0;
        for (int i = 0; i < dirty.size(); i++) {
            Network network = dirty.get(i);
            if (!network.alive) {
                network.dirty = false;
            } else if (network.split != null) {
                dirty.set(kept++, network);
            } else {
                network.dirty = false;
                logic.onRebuilt(network);
            }
        }
        dirty.size(kept);
    }

    private void tickNetworks(UnloadedChunkMode mode, boolean allowAnchors) {
        boolean pauseMode = mode == UnloadedChunkMode.PAUSE;
        for (int i = 0; i < networks.size(); i++) {
            Network network = networks.get(i);
            boolean paused = pauseMode && !network.unloadedChunks.isEmpty() && !(allowAnchors && network.anchors > 0);
            if (paused != network.paused) {
                network.paused = paused;
                logic.onPausedChanged(network, paused);
            }
            if (paused || network.split != null) {
                continue;
            }
            if (profiling) {
                long start = System.nanoTime();
                logic.tick(network);
                network.lastTickNanos = System.nanoTime() - start;
                network.lastTickEpoch = profilingEpoch;
            } else {
                logic.tick(network);
            }
        }
    }

    /**
     * A cut edge normally heals when the target node loads again; if the chunk loaded but the node is gone (edited
     * while unloaded), nothing would ever clear the edge and a {@code pause} network would stay paused.
     */
    private void dropStaleUnloadedEdges() {
        ObjectIterator<Long2ObjectMap.Entry<LongArrayList>> it = Long2ObjectMaps.fastIterator(unloadedTargets);
        while (it.hasNext()) {
            Long2ObjectMap.Entry<LongArrayList> entry = it.next();
            long target = entry.getLongKey();
            if (!connectivity.isLoaded(target) || nodes.containsKey(target)) {
                continue;
            }
            // Read before remove(): the fast iterator reuses its entry object.
            LongArrayList sources = entry.getValue();
            it.remove();
            for (int i = 0; i < sources.size(); i++) {
                Node source = nodes.get(sources.getLong(i));
                if (source != null) {
                    dropUnloadedLink(source, target, false);
                    touch(source.network);
                }
            }
        }
    }

    private void addUnloadedLink(Node node, long target) {
        LongArrayList list = node.unloaded;
        if (list == null) {
            list = new LongArrayList(2);
            node.unloaded = list;
        } else if (list.contains(target)) {
            return;
        }
        list.add(target);
        node.network.unloadedChunks.addTo(Network.chunkOf(target), 1);
        LongArrayList sources = unloadedTargets.get(target);
        if (sources == null) {
            sources = new LongArrayList(2);
            unloadedTargets.put(target, sources);
        }
        sources.add(node.pos);
    }

    /** {@code unindex} is false when the caller already removed or is iterating the target's index entry. */
    private void dropUnloadedLink(Node node, long target, boolean unindex) {
        LongArrayList list = node.unloaded;
        if (list == null || !list.rem(target)) {
            return;
        }
        if (list.isEmpty()) {
            node.unloaded = null;
        }
        Network.decrement(node.network.unloadedChunks, Network.chunkOf(target));
        if (unindex) {
            LongArrayList sources = unloadedTargets.get(target);
            if (sources != null) {
                sources.rem(node.pos);
                if (sources.isEmpty()) {
                    unloadedTargets.remove(target);
                }
            }
        }
    }

    /** For a node already detached from its network's counts. */
    private void clearUnloadedLinks(Node node) {
        LongArrayList list = node.unloaded;
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            long target = list.getLong(i);
            LongArrayList sources = unloadedTargets.get(target);
            if (sources != null) {
                sources.rem(node.pos);
                if (sources.isEmpty()) {
                    unloadedTargets.remove(target);
                }
            }
        }
        node.unloaded = null;
    }

    private void addSplitSeed(Network network, long pos) {
        SplitJob job = network.split;
        if (job == null) {
            job = new SplitJob();
            network.split = job;
            splitting.add(network);
        }
        job.addSeed(pos);
    }

    private void touch(Network network) {
        if (!network.dirty) {
            network.dirty = true;
            dirty.add(network);
        }
        SplitJob job = network.split;
        if (job != null) {
            job.restart();
        }
    }

    private Network newNetwork() {
        Network network = new Network(nextId++);
        network.index = networks.size();
        networks.add(network);
        return network;
    }

    private void discard(Network network) {
        network.alive = false;
        int index = network.index;
        Network last = networks.pop();
        if (last != network) {
            networks.set(index, last);
            last.index = index;
        }
        network.index = -1;
        if (network.split != null) {
            network.split = null;
            splitting.remove(network);
        }
    }
}
