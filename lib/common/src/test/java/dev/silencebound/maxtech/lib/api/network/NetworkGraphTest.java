package dev.silencebound.maxtech.lib.api.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class NetworkGraphTest {
    private static final int BUDGET = 1_000_000;

    /** Blocks on a grid connect to their six neighbours; chunks listed in {@code unloadedChunks} are unloaded. */
    private static final class Grid implements NodeConnectivity {
        final LongOpenHashSet blocks = new LongOpenHashSet();
        final LongOpenHashSet unloadedChunks = new LongOpenHashSet();
        /** Two neighbouring blocks that do not connect, like conduits of different colours. */
        long cutA;
        long cutB;

        @Override
        public void connections(long pos, LongList out) {
            int x = BlockPos.getX(pos);
            int y = BlockPos.getY(pos);
            int z = BlockPos.getZ(pos);
            offer(out, pos, BlockPos.asLong(x + 1, y, z));
            offer(out, pos, BlockPos.asLong(x - 1, y, z));
            offer(out, pos, BlockPos.asLong(x, y + 1, z));
            offer(out, pos, BlockPos.asLong(x, y - 1, z));
            offer(out, pos, BlockPos.asLong(x, y, z + 1));
            offer(out, pos, BlockPos.asLong(x, y, z - 1));
        }

        private void offer(LongList out, long from, long pos) {
            boolean cut = (from == cutA && pos == cutB) || (from == cutB && pos == cutA);
            if (blocks.contains(pos) && !cut) {
                out.add(pos);
            }
        }

        @Override
        public boolean isLoaded(long pos) {
            return !unloadedChunks.contains(Network.chunkOf(pos));
        }
    }

    private static final class Recorder implements NetworkLogic {
        final List<String> events = new ArrayList<>();
        final Set<Network> ticked = new HashSet<>();
        final List<Network> rebuilt = new ArrayList<>();
        final List<Long> invalidated = new ArrayList<>();

        @Override
        public void onNetworkCreated(Network network) {
            events.add("created " + network.id());
        }

        @Override
        public void onNetworkRemoved(Network network) {
            events.add("removed " + network.id());
        }

        @Override
        public void onMerged(Network survivor, Network absorbed) {
            events.add("merged " + absorbed.id() + " into " + survivor.id());
        }

        @Override
        public void onSplit(Network original, Network part) {
            events.add("split " + part.id() + " from " + original.id());
        }

        @Override
        public void onNodeInvalidated(Network network, long pos) {
            invalidated.add(pos);
        }

        @Override
        public void onRebuilt(Network network) {
            rebuilt.add(network);
        }

        @Override
        public void tick(Network network) {
            ticked.add(network);
        }

        void reset() {
            events.clear();
            ticked.clear();
            rebuilt.clear();
            invalidated.clear();
        }
    }

    private final Grid grid = new Grid();
    private final Recorder recorder = new Recorder();
    private final NetworkGraph graph = new NetworkGraph(grid, recorder);

    private static long pos(int x) {
        return BlockPos.asLong(x, 64, 0);
    }

    private void place(int x) {
        grid.blocks.add(pos(x));
        graph.add(pos(x));
    }

    private void breakAt(int x) {
        grid.blocks.remove(pos(x));
        graph.remove(pos(x));
    }

    private void line(int from, int to) {
        for (int x = from; x <= to; x++) {
            place(x);
        }
    }

    private void tick() {
        tick(UnloadedChunkMode.TRUNCATE, BUDGET);
    }

    private void tick(UnloadedChunkMode mode, int budget) {
        recorder.reset();
        graph.tick(mode, true, budget);
    }

    private Network net(int x) {
        Network network = graph.network(pos(x));
        assertNotNull(network, "no node at x=" + x);
        return network;
    }

    @Test
    void lineFormsOneNetwork() {
        line(0, 9);
        tick();
        assertEquals(1, graph.networks().size());
        assertEquals(10, net(0).size());
        assertSame(net(0), net(9));
        assertTrue(recorder.ticked.contains(net(0)));
    }

    /** Switching profiling forgets recorded tick times, so a stale one never passes for a new sample. */
    @Test
    void switchingProfilingResetsTickTimes() {
        line(0, 3);
        try {
            NetworkGraph.setProfiling(true);
            assertEquals(-1, graph.lastTickNanos(), "graph time before any profiled tick");
            tick();
            assertTrue(graph.lastTickNanos() >= 0, "the profiled graph tick was not timed");
            assertTrue(net(0).lastTickNanos() >= 0, "the profiled network tick was not timed");
            NetworkGraph.setProfiling(false);
            assertEquals(-1, graph.lastTickNanos(), "graph time once profiling is off");
            assertEquals(-1, net(0).lastTickNanos(), "network time once profiling is off");
            tick();
            assertEquals(-1, graph.lastTickNanos(), "graph time after an unprofiled tick");
        } finally {
            NetworkGraph.setProfiling(false);
        }
    }

    @Test
    void bridgeMergesSmallerIntoLarger() {
        line(0, 5);
        line(7, 8);
        tick();
        Network large = net(0);
        Network small = net(7);
        assertNotSame(large, small);

        place(6);
        tick();
        assertSame(large, net(8), "the larger network should survive");
        assertEquals(9, large.size());
        assertFalse(small.isAlive());
        assertEquals(List.of("merged " + small.id() + " into " + large.id()), recorder.events);
        assertEquals(List.of(large), recorder.rebuilt);
    }

    @Test
    void removingTheMiddleSplits() {
        line(0, 8);
        tick();
        Network original = net(0);

        breakAt(4);
        tick();
        assertEquals(2, graph.networks().size());
        assertEquals(4, net(0).size());
        assertEquals(4, net(8).size());
        assertNotSame(net(0), net(8));
        assertTrue(recorder.events
                .contains("split " + (net(0) == original ? net(8) : net(0)).id() + " from " + original.id()));
        assertEquals(2, recorder.ticked.size());
    }

    @Test
    void removingALeafNeedsNoSplitCheck() {
        line(0, 4);
        tick();
        breakAt(4);
        graph.tick(UnloadedChunkMode.TRUNCATE, true, 0);
        assertFalse(net(0).isSettling());
        assertEquals(4, net(0).size());
    }

    @Test
    void removingTheLastNodeRemovesTheNetwork() {
        place(0);
        tick();
        int id = net(0).id();
        breakAt(0);
        tick();
        assertNull(graph.network(pos(0)));
        assertTrue(graph.networks().isEmpty());
        assertEquals(List.of("removed " + id), recorder.events);
    }

    @Test
    void ringStaysWholeWhenOneNodeGoes() {
        // A 3x3 ring in the xz plane.
        long[] ring = {BlockPos.asLong(0, 64, 0), BlockPos.asLong(1, 64, 0), BlockPos.asLong(2, 64, 0),
                BlockPos.asLong(2, 64, 1), BlockPos.asLong(2, 64, 2), BlockPos.asLong(1, 64, 2),
                BlockPos.asLong(0, 64, 2), BlockPos.asLong(0, 64, 1)};
        for (long p : ring) {
            grid.blocks.add(p);
            graph.add(p);
        }
        tick();
        grid.blocks.remove(ring[1]);
        graph.remove(ring[1]);
        tick();
        assertEquals(1, graph.networks().size());
        assertEquals(7, graph.networks().get(0).size());
        assertTrue(recorder.events.isEmpty(), recorder.events.toString());
    }

    @Test
    void removingAHubSplitsIntoEveryArm() {
        long center = BlockPos.asLong(0, 64, 0);
        grid.blocks.add(center);
        graph.add(center);
        for (int i = 1; i <= 3; i++) {
            long[] arm = {BlockPos.asLong(i, 64, 0), BlockPos.asLong(-i, 64, 0), BlockPos.asLong(0, 64, i),
                    BlockPos.asLong(0, 64, -i), BlockPos.asLong(0, 64 + i, 0), BlockPos.asLong(0, 64 - i, 0)};
            for (long p : arm) {
                grid.blocks.add(p);
                graph.add(p);
            }
        }
        tick();
        assertEquals(1, graph.networks().size());
        grid.blocks.remove(center);
        graph.remove(center);
        tick();
        assertEquals(6, graph.networks().size());
        for (Network network : graph.networks()) {
            assertEquals(3, network.size());
        }
    }

    @Test
    void splitWorkIsBoundedByTheBudgetAndResumes() {
        line(0, 199);
        tick();
        breakAt(100);
        tick(UnloadedChunkMode.TRUNCATE, 10);
        Network settling = net(0);
        assertTrue(settling.isSettling());
        assertSame(settling, net(199), "not split yet");
        assertFalse(recorder.ticked.contains(settling), "a settling network must not tick");

        int ticks = 1;
        while (net(0) == net(199)) {
            tick(UnloadedChunkMode.TRUNCATE, 10);
            ticks++;
            assertTrue(ticks < 100, "split never finished");
        }
        assertEquals(100, net(0).size());
        assertEquals(99, net(199).size());
        assertFalse(net(0).isSettling());
        assertFalse(net(199).isSettling());
    }

    @Test
    void splitCostFollowsTheSmallerPart() {
        line(0, 9_999);
        tick();
        breakAt(3);
        // The fills from both sides run in lockstep, so the 3-node side drains after about six steps.
        tick(UnloadedChunkMode.TRUNCATE, 8);
        assertNotSame(net(0), net(9_999));
        assertEquals(3, net(0).size());
        assertEquals(9_996, net(9_999).size());
    }

    @Test
    void changeDuringASplitRestartsIt() {
        line(0, 40);
        tick();
        breakAt(20);
        tick(UnloadedChunkMode.TRUNCATE, 4);
        assertTrue(net(0).isSettling());

        // Reconnect both halves around the gap before the check finishes.
        long above19 = BlockPos.asLong(19, 65, 0);
        long above20 = BlockPos.asLong(20, 65, 0);
        long above21 = BlockPos.asLong(21, 65, 0);
        for (long p : new long[]{above19, above20, above21}) {
            grid.blocks.add(p);
            graph.add(p);
        }
        for (int i = 0; i < 50 && net(0).isSettling(); i++) {
            tick(UnloadedChunkMode.TRUNCATE, 4);
        }
        assertFalse(net(0).isSettling());
        assertSame(net(0), net(40));
        assertEquals(43, net(0).size());
    }

    @Test
    void aSeedDroppedFromAPendingSplitCanBeASeedAgain() {
        line(0, 40);
        tick();
        breakAt(20);
        tick(UnloadedChunkMode.TRUNCATE, 1);
        // The seed at 19 goes; the restarted check drops it from its seeds.
        breakAt(19);
        tick(UnloadedChunkMode.TRUNCATE, 1);
        place(19);
        tick(UnloadedChunkMode.TRUNCATE, 1);
        // 19 is a seed again, standing for the one-node part it is left as.
        breakAt(18);
        for (int i = 0; i < 100 && net(0).isSettling(); i++) {
            tick(UnloadedChunkMode.TRUNCATE, 1);
        }
        assertEquals(3, graph.networks().size());
        assertEquals(18, net(0).size());
        assertEquals(1, net(19).size());
        assertEquals(20, net(40).size());
    }

    @Test
    void removalsInOneTickAcrossAPendingSeedStillSplit() {
        line(0, 10);
        tick();
        breakAt(5);
        breakAt(6);
        tick();
        assertEquals(5, net(0).size());
        assertEquals(4, net(10).size());
        assertNotSame(net(0), net(10));
    }

    @Test
    void invalidateWithoutChangeKeepsTheNetworkClean() {
        line(0, 5);
        tick();
        graph.invalidate(pos(2));
        graph.add(pos(3)); // re-adding an existing node is a re-read too
        tick();
        assertTrue(recorder.rebuilt.isEmpty(), "nothing changed, so nothing should be rebuilt");
        assertEquals(List.of(pos(2), pos(3)), recorder.invalidated);
    }

    /** Regression: an unchanged neighbour update used to restart a pending split check every tick. */
    @Test
    void invalidateWithoutChangeDoesNotRestartASplit() {
        line(0, 199);
        tick();
        breakAt(100);
        int ticks = 0;
        while (net(0) == net(199)) {
            graph.invalidate(pos(150));
            tick(UnloadedChunkMode.TRUNCATE, 10);
            assertTrue(++ticks < 100, "split never finished");
        }
        assertEquals(100, net(0).size());
        assertEquals(99, net(199).size());
    }

    @Test
    void invalidateCutsAndRestoresAConnection() {
        line(0, 5);
        tick();
        // The blocks stay; 2 and 3 stop connecting, as after a colour change.
        grid.cutA = pos(2);
        grid.cutB = pos(3);
        graph.invalidate(pos(2));
        graph.invalidate(pos(3));
        tick();
        assertNotSame(net(0), net(5));
        assertEquals(3, net(0).size());
        assertEquals(3, net(5).size());

        grid.cutA = 0;
        grid.cutB = 0;
        graph.invalidate(pos(2));
        graph.invalidate(pos(3));
        tick();
        assertSame(net(0), net(5));
        assertEquals(6, net(0).size());
    }

    @Test
    void truncateKeepsTickingPauseStops() {
        // Nodes at x = 10..21 span chunk 0 (x < 16) and chunk 1.
        line(10, 21);
        tick();
        assertEquals(1, graph.networks().size());

        grid.unloadedChunks.add(Network.chunkOf(pos(16)));
        for (int x = 16; x <= 21; x++) {
            graph.remove(pos(x)); // the chunk unloads; blocks stay in the world
        }
        tick(UnloadedChunkMode.TRUNCATE, BUDGET);
        Network remaining = net(10);
        assertEquals(6, remaining.size());
        assertEquals(Set.of(Network.chunkOf(pos(16))), new HashSet<>(remaining.unloadedChunks()));
        assertFalse(remaining.isPaused());
        assertTrue(recorder.ticked.contains(remaining));

        tick(UnloadedChunkMode.PAUSE, BUDGET);
        assertTrue(remaining.isPaused());
        assertFalse(recorder.ticked.contains(remaining));

        grid.unloadedChunks.clear();
        for (int x = 16; x <= 21; x++) {
            graph.add(pos(x));
        }
        tick(UnloadedChunkMode.PAUSE, BUDGET);
        assertEquals(12, net(10).size());
        assertTrue(net(10).unloadedChunks().isEmpty());
        assertFalse(net(10).isPaused());
        assertTrue(recorder.ticked.contains(net(10)));
    }

    @Test
    void anchorKeepsAPausedNetworkRunningOnlyWhenAllowed() {
        line(10, 17);
        tick();
        grid.unloadedChunks.add(Network.chunkOf(pos(16)));
        graph.remove(pos(16));
        graph.remove(pos(17));
        graph.setAnchored(pos(10), true);
        recorder.reset();
        graph.tick(UnloadedChunkMode.PAUSE, true, BUDGET);
        assertTrue(net(10).isAnchored());
        assertFalse(net(10).isPaused());
        assertTrue(recorder.ticked.contains(net(10)));

        recorder.reset();
        graph.tick(UnloadedChunkMode.PAUSE, false, BUDGET);
        assertTrue(net(10).isPaused());

        graph.setAnchored(pos(10), false);
        recorder.reset();
        graph.tick(UnloadedChunkMode.PAUSE, true, BUDGET);
        assertFalse(net(10).isAnchored());
        assertTrue(net(10).isPaused());
    }

    @Test
    void cutEdgeToANodeThatNeverReturnsIsDropped() {
        line(10, 17);
        tick();
        grid.unloadedChunks.add(Network.chunkOf(pos(16)));
        graph.remove(pos(16));
        graph.remove(pos(17));
        tick(UnloadedChunkMode.PAUSE, BUDGET);
        assertTrue(net(10).isPaused());

        // The chunk comes back without the node (edited while unloaded).
        grid.blocks.remove(pos(16));
        grid.blocks.remove(pos(17));
        grid.unloadedChunks.clear();
        for (int i = 0; i < NetworkGraph.STALE_EDGE_INTERVAL; i++) {
            tick(UnloadedChunkMode.PAUSE, BUDGET);
        }
        assertTrue(net(10).unloadedChunks().isEmpty());
        assertFalse(net(10).isPaused());
    }

    @Test
    void chunksTrackNodePositions() {
        line(10, 21);
        tick();
        assertEquals(Set.of(Network.chunkOf(pos(10)), Network.chunkOf(pos(21))), new HashSet<>(net(10).chunks()));
        for (int x = 16; x <= 21; x++) {
            breakAt(x);
        }
        tick();
        assertEquals(Set.of(Network.chunkOf(pos(10))), new HashSet<>(net(10).chunks()));
        assertTrue(net(10).unloadedChunks().isEmpty(), "a broken node leaves no cut edge");
    }

    @Test
    void addingAnExistingNodeOnlyRereadsIt() {
        line(0, 2);
        tick();
        Network network = net(0);
        graph.add(pos(1));
        tick();
        assertSame(network, net(0));
        assertEquals(3, network.size());
        assertTrue(recorder.events.isEmpty());
    }
}
