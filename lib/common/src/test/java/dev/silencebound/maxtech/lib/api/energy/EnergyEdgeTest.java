package dev.silencebound.maxtech.lib.api.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Edges in a network on a test clock, against a plain-Java FE neighbour: round trips never gain at any rate, export
 * switch and limits, priorities and brownout, sleeping without polling, and the neighbour coming and going.
 */
class EnergyEdgeTest {
    private final long[] clock = {100};
    private final EnergyScheduler scheduler = new EnergyScheduler(() -> clock[0]);
    private final EnergyNetwork network = scheduler.createNetwork();
    private EdgeSettings settings = EdgeSettings.DEFAULTS;

    /** An FE block of another mod: a store with per-call limits, and optionally its own generation or use per tick. */
    static final class Neighbour {
        long stored;
        final long capacity;
        final long maxIn;
        final long maxOut;
        long generates;
        long uses;
        int calls;

        Neighbour(long stored, long capacity, long maxIn, long maxOut) {
            this.stored = stored;
            this.capacity = capacity;
            this.maxIn = maxIn;
            this.maxOut = maxOut;
        }

        long insert(long max, boolean simulate) {
            calls++;
            long moved = Math.min(max, Math.min(maxIn, capacity - stored));
            if (!simulate) {
                stored += moved;
            }
            return moved;
        }

        long extract(long max, boolean simulate) {
            calls++;
            long moved = Math.min(max, Math.min(maxOut, stored));
            if (!simulate) {
                stored -= moved;
            }
            return moved;
        }

        /** The block's own tick, before the end-of-tick energy work. */
        void tick() {
            stored = Math.min(capacity, stored + generates);
            stored -= Math.min(stored, uses);
        }
    }

    final class TestEdge extends EnergyEdge {
        @Nullable
        Neighbour neighbour;

        TestEdge(@Nullable Neighbour neighbour) {
            super(scheduler);
            this.neighbour = neighbour;
        }

        @Override
        protected long simulateInsert(long max) {
            return neighbour == null ? 0 : neighbour.insert(max, true);
        }

        @Override
        protected long insert(long max) {
            return neighbour == null ? 0 : neighbour.insert(max, false);
        }

        @Override
        protected long simulateExtract(long max) {
            return neighbour == null ? 0 : neighbour.extract(max, true);
        }

        @Override
        protected long extract(long max) {
            return neighbour == null ? 0 : neighbour.extract(max, false);
        }

        @Override
        protected EdgeSettings settings() {
            return settings;
        }
    }

    private void run(int ticks, Neighbour... neighbours) {
        for (int t = 0; t < ticks; t++) {
            clock[0]++;
            for (Neighbour n : neighbours) {
                n.tick();
            }
            scheduler.tick();
        }
    }

    private TestEdge edge(Neighbour neighbour) {
        TestEdge edge = new TestEdge(neighbour);
        edge.join(network);
        return edge;
    }

    @Test
    void foreignGeneratorFeedsConsumers() {
        Neighbour generator = new Neighbour(0, 10_000, 0, 500);
        generator.generates = 300;
        edge(generator);
        EnergyEndpoint machine = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);
        machine.setDemand(200);
        network.add(machine);
        run(10, generator);
        assertEquals(200, machine.received(), "the machine gets its demand from the generator");
        run(200, generator);
        assertEquals(200, machine.received());
        assertTrue(network.isAsleep(), "a steady import lets the network sleep");
    }

    /**
     * Source and sink behind two edges, a MaxTech buffer in between, at random rates including uneven ones: what
     * arrives (counted in exact units) never exceeds what left, and less than one FE per edge is lost.
     */
    @Test
    void roundTripsNeverGain() {
        Random random = new Random(3_7L);
        for (int run = 0; run < 300; run++) {
            long[] runClock = {0};
            EnergyScheduler runScheduler = new EnergyScheduler(() -> runClock[0]);
            EnergyNetwork runNetwork = runScheduler.createNetwork();
            FeRate rate = run == 0 ? new FeRate(3, 7) : new FeRate(1 + random.nextInt(9), 1 + random.nextInt(9));
            EdgeSettings runSettings = new EdgeSettings(rate, true, 0, 0, 1 + random.nextInt(5));
            long start = 1_000L + random.nextInt(50_000);
            Neighbour source = new Neighbour(start, start, 0, 1 + random.nextInt(400));
            Neighbour sink = new Neighbour(0, 1_000_000, 1 + random.nextInt(300), 0);
            sink.uses = random.nextInt(50);
            EnergyEdge in = runEdge(runScheduler, source, runSettings);
            EnergyEdge out = runEdge(runScheduler, sink, runSettings);
            in.join(runNetwork);
            out.join(runNetwork);
            EnergyEndpoint buffer = EnergyEndpoint.buffer(random.nextInt(5_000), random.nextInt(200),
                    random.nextInt(200), random.nextBoolean() ? EnergyPriority.LEFTOVERS : 1);
            runNetwork.add(buffer);
            long used = 0;
            int ticks = 50 + random.nextInt(300);
            for (int t = 0; t < ticks; t++) {
                runClock[0]++;
                long before = sink.stored;
                sink.tick();
                used += before - sink.stored;
                runScheduler.tick();
            }
            long heldIn = in.close();
            long heldOut = out.close();
            long arrivedUnits = rate.unitsOfFe(sink.stored + used + heldIn + heldOut + source.stored)
                    + rate.unitsOfMu(buffer.stored());
            long leftUnits = rate.unitsOfFe(start);
            String what = rate + " run " + run;
            assertTrue(arrivedUnits <= leftUnits, what + ": gained " + (arrivedUnits - leftUnits) + " units");
            assertTrue(leftUnits - arrivedUnits < 2L * rate.mu(),
                    what + ": lost " + (leftUnits - arrivedUnits) + " units, more than a fraction of one FE per edge");
            if (run == 0) {
                assertTrue(sink.stored + used > 0, "energy reached the sink at 3:7");
            }
        }
    }

    /**
     * A source that runs dry, at 1:1 and at 3:7: everything that left it arrives (1:1 exactly), nothing more, and the
     * network stops drawing once the source and the edge's reserve are empty.
     */
    @Test
    void drainedSourceGivesExactlyWhatItHad() {
        for (FeRate rate : new FeRate[]{FeRate.ONE_TO_ONE, new FeRate(3, 7), new FeRate(7, 3)}) {
            long[] runClock = {0};
            EnergyScheduler runScheduler = new EnergyScheduler(() -> runClock[0]);
            EnergyNetwork runNetwork = runScheduler.createNetwork();
            EdgeSettings runSettings = new EdgeSettings(rate, true, 0, 0, 20);
            Neighbour source = new Neighbour(1_000, 1_000, 0, 70);
            Neighbour sink = new Neighbour(0, 1_000_000, 45, 0);
            EnergyEdge in = runEdge(runScheduler, source, runSettings);
            EnergyEdge out = runEdge(runScheduler, sink, runSettings);
            in.join(runNetwork);
            out.join(runNetwork);
            EnergyEndpoint machine = EnergyEndpoint.consumer(EnergyPriority.LEFTOVERS);
            machine.setDemand(3);
            runNetwork.add(machine);
            long machineGot = 0;
            for (int t = 0; t < 200; t++) {
                runClock[0]++;
                machineGot += machine.received(); // the flow of this tick, set by the last pass
                runScheduler.tick();
            }
            assertEquals(0, source.stored, rate + ": the source ran dry");
            assertEquals(0, in.importing(), rate + ": nothing is drawn from an empty source");
            long heldIn = in.close();
            long heldOut = out.close();
            long arrived = rate.unitsOfFe(sink.stored + heldIn + heldOut) + rate.unitsOfMu(machineGot);
            long left = rate.unitsOfFe(1_000);
            assertTrue(arrived <= left, rate + ": gained " + (arrived - left) + " units");
            assertTrue(left - arrived < 2L * rate.mu(), rate + ": lost " + (left - arrived) + " units");
            if (rate.isOneToOne()) {
                assertEquals(left, arrived, "1:1 is exact");
            }
        }
    }

    private static EnergyEdge runEdge(EnergyScheduler runScheduler, Neighbour neighbour, EdgeSettings runSettings) {
        return new EnergyEdge(runScheduler) {
            @Override
            protected long simulateInsert(long max) {
                return neighbour.insert(max, true);
            }

            @Override
            protected long insert(long max) {
                return neighbour.insert(max, false);
            }

            @Override
            protected long simulateExtract(long max) {
                return neighbour.extract(max, true);
            }

            @Override
            protected long extract(long max) {
                return neighbour.extract(max, false);
            }

            @Override
            protected EdgeSettings settings() {
                return runSettings;
            }
        };
    }

    @Test
    void exportOffSendsNothingOut() {
        settings = new EdgeSettings(FeRate.ONE_TO_ONE, false, 0, 0, 20);
        Neighbour sink = new Neighbour(0, 100_000, 1_000, 0);
        TestEdge out = edge(sink);
        Neighbour source = new Neighbour(5_000, 5_000, 0, 100);
        edge(source);
        EnergyEndpoint generator = EnergyEndpoint.producer();
        generator.setSupply(1_000);
        network.add(generator);
        EnergyEndpoint battery = EnergyEndpoint.buffer(100_000, 1_000, 1_000, EnergyPriority.DEFAULT);
        network.add(battery);
        run(40, sink, source);
        assertEquals(0, sink.stored, "export off: nothing reaches another mod's block");
        assertEquals(0, out.exporting());
        assertTrue(source.stored < 5_000, "import still works with export off");
        assertTrue(battery.stored() > 0, "the battery charges from the generator and the import");
    }

    @Test
    void exportLimitsPerEdgeAndPerNetwork() {
        settings = new EdgeSettings(FeRate.ONE_TO_ONE, true, 100, 150, 20);
        Neighbour a = new Neighbour(0, 1_000_000, 1_000, 0);
        Neighbour b = new Neighbour(0, 1_000_000, 1_000, 0);
        TestEdge edgeA = edge(a);
        TestEdge edgeB = edge(b);
        EnergyEndpoint generator = EnergyEndpoint.producer();
        generator.setSupply(10_000);
        network.add(generator);
        run(5, a, b);
        assertTrue(edgeA.exporting() <= 100 && edgeB.exporting() <= 100, "per-edge limit");
        assertEquals(150, edgeA.exporting() + edgeB.exporting(), "the per-network limit is shared out");
        assertEquals(150, network.edgeExport());
        long before = a.stored + b.stored;
        run(20, a, b);
        assertEquals(150L * 20, a.stored + b.stored - before, "delivered exactly the limit per tick");
        edgeB.leave();
        run(2, a, b);
        assertEquals(100, edgeA.exporting(), "alone, the edge gets its own limit");
    }

    @Test
    void foreignBlocksTakePartInPriorities() {
        Neighbour source = new Neighbour(1_000_000, 1_000_000, 0, 1_000);
        edge(source);
        EnergyEndpoint important = EnergyEndpoint.consumer(1);
        important.setDemand(600);
        EnergyEndpoint normal = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);
        normal.setDemand(800);
        network.add(important);
        network.add(normal);
        Neighbour sink = new Neighbour(0, 1_000_000, 1_000, 0);
        TestEdge leftovers = edge(sink);
        leftovers.setExportPriority(EnergyPriority.LEFTOVERS);
        run(5, source, sink);
        assertEquals(600, important.received());
        assertEquals(400, normal.received(), "brownout: the normal machine gets half");
        assertEquals(0.5, normal.suppliedFraction(), 1e-9);
        assertEquals(0, leftovers.exporting(), "a leftovers-only foreign block gets nothing while machines are short");
        normal.setDemand(100);
        run(3, source, sink);
        assertEquals(300, leftovers.exporting(), "it takes what is left");
        long before = sink.stored;
        run(10, source, sink);
        assertEquals(3_000, sink.stored - before);
    }

    @Test
    void idleEdgesPollSlowlyAndWakeOnNeighbourChange() {
        settings = new EdgeSettings(FeRate.ONE_TO_ONE, true, 0, 0, 50);
        Neighbour empty = new Neighbour(0, 1_000, 0, 100);
        TestEdge edge = edge(empty);
        run(2, empty);
        assertFalse(edge.active, "nothing flows");
        int calls = empty.calls;
        run(48, empty);
        assertEquals(calls, empty.calls, "an idle edge leaves its neighbour alone between polls");
        run(1, empty);
        assertTrue(empty.calls > calls, "and looks again after pollTicks");
        assertEquals(0, scheduler.activeEdgeCount());

        EnergyEndpoint machine = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);
        machine.setDemand(50);
        network.add(machine);
        Neighbour full = new Neighbour(1_000, 1_000, 0, 100);
        edge.neighbour = full; // replaced; the cache invalidation calls neighbourChanged
        edge.neighbourChanged();
        run(2, full);
        assertEquals(50, machine.received(), "the new neighbour is used at once, not at the next poll");
        assertEquals(1, scheduler.activeEdgeCount());

        edge.neighbour = null;
        edge.neighbourChanged();
        run(5);
        assertEquals(0, machine.received(), "the held reserve runs out once the neighbour is gone");
        assertEquals(0, edge.heldFe());
        assertEquals(0, scheduler.activeEdgeCount(), "with its flows back at 0 the edge sleeps again");
    }

    @Test
    void closeGivesBackWhatIsHeld() {
        Neighbour battery = new Neighbour(1_000, 10_000, 1_000, 300);
        TestEdge edge = edge(battery);
        edge.setExportPriority(EnergyPriority.LEFTOVERS);
        EnergyEndpoint store = EnergyEndpoint.buffer(1_000_000, 200, 0, EnergyPriority.DEFAULT);
        network.add(store);
        run(4, battery);
        assertEquals(200, edge.importing());
        assertEquals(200, edge.heldFe(), "what the network places in one pass is held, not a whole call's 300");
        assertEquals(1_000, battery.stored + store.stored() + edge.heldFe(), "nothing made or lost");
        assertEquals(0, edge.close(), "everything went back");
        assertEquals(1_000, battery.stored + store.stored());
        assertTrue(edge.isClosed());
        assertEquals(0, scheduler.edgeCount());
        assertThrows(IllegalStateException.class, () -> edge.join(network));
    }

    @Test
    void twoWayNeighbourIsNotOfferedItsOwnDemand() {
        Neighbour battery = new Neighbour(1_000, 10_000, 1_000, 300);
        TestEdge edge = edge(battery);
        run(5, battery);
        assertEquals(0, edge.heldFe(), "alone in a network, a battery has nobody to give to");
        assertEquals(0, edge.importing());
        assertEquals(1_000, battery.stored);
        assertEquals(0, scheduler.activeEdgeCount());
    }

    @Test
    void savedHeldEnergyComesBack() {
        Neighbour generator = new Neighbour(0, 1_000, 0, 0);
        TestEdge edge = edge(generator);
        edge.restoreHeldFe(250);
        EnergyEndpoint machine = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);
        machine.setDemand(100);
        network.add(machine);
        run(1, generator);
        assertEquals(100, machine.received(), "restored energy is offered");
        run(3, generator);
        assertTrue(edge.heldFe() < 250, "it is drawn down");
    }

    @Test
    void disposedNetworkLetsTheEdgeGo() {
        Neighbour battery = new Neighbour(1_000, 10_000, 1_000, 300);
        TestEdge edge = edge(battery);
        run(2, battery);
        network.dispose();
        run(25, battery);
        assertTrue(edge.network().isEmpty(), "the edge left the disposed network");
        assertEquals(0, scheduler.activeEdgeCount());
    }
}
