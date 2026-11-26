package dev.silencebound.maxtech.storage.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyScheduler;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
// PLATFORM-LEAK: the stores are NeoForge transfer handlers; tests drive them through their transactions.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.Test;

/** The pooled totals stay exact through every kind of change, and fills and drains follow the water level. */
class EnergyCellsTest {
    private static void none() {
    }

    private static EnergyCells.Store cell(long capacity, long stored) {
        return cell(capacity, stored, 100);
    }

    private static EnergyCells.Store cell(long capacity, long stored, long limit) {
        EnergyCells.Store store = new EnergyCells.Store(capacity, limit, limit, EnergyCellsTest::none);
        store.load(stored);
        return store;
    }

    @Test
    void totalsFollowJoinsLeavesAndOutsideChanges() {
        EnergyCells pool = new EnergyCells();
        EnergyCells.Store a = cell(1000, 300);
        EnergyCells.Store b = cell(500, 0);
        pool.add(a);
        pool.add(b);
        pool.add(a);
        assertEquals(2, pool.size());
        assertEquals(300, pool.stored());
        assertEquals(1500, pool.capacity());
        // A neighbour charging a cell through its capability, committed and rolled back.
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(100, b.insert(250, tx), "the cell's transfer limit");
            assertEquals(400, pool.stored(), "counted inside the transaction");
        }
        assertEquals(300, pool.stored(), "the rollback is counted too");
        try (Transaction tx = Transaction.openRoot()) {
            b.insert(100, tx);
            tx.commit();
        }
        assertEquals(400, pool.stored());
        b.receive(50);
        a.tryConsume(100);
        a.setCapacity(2000);
        assertEquals(350, pool.stored());
        assertEquals(2500, pool.capacity());
        pool.remove(a);
        assertEquals(150, pool.stored());
        assertEquals(500, pool.capacity());
        a.receive(10);
        assertEquals(150, pool.stored(), "a cell that left no longer counts");
        // Joining another pool takes the cell from this one.
        EnergyCells other = new EnergyCells();
        other.add(b);
        assertEquals(0, pool.stored());
        assertEquals(150, other.stored());
    }

    @Test
    void paysFromThePrimaryFirstThenTheCells() {
        EnergyCells pool = new EnergyCells();
        EnergyCells.Store a = cell(1000, 1000);
        EnergyCells.Store b = cell(1000, 1000);
        pool.add(a);
        pool.add(b);
        FeBuffer primary = new FeBuffer(100, 1000, 0, EnergyCellsTest::none);
        primary.receive(60);
        FeBuffer combined = pool.over(primary);
        assertEquals(2060, combined.stored());
        assertEquals(2100, combined.capacity());
        try (Transaction tx = Transaction.openRoot()) {
            assertTrue(combined.tryConsume(50, tx));
            tx.commit();
        }
        assertEquals(10, primary.stored(), "the primary pays alone while it can");
        assertEquals(2000, pool.stored());
        try (Transaction tx = Transaction.openRoot()) {
            assertTrue(combined.tryConsume(1210, tx));
            assertEquals(0, primary.stored());
            assertEquals(800, pool.stored());
            // A rolled-back operation gives every cell its FE back.
        }
        assertEquals(10, primary.stored());
        assertEquals(2000, pool.stored());
        try (Transaction tx = Transaction.openRoot()) {
            assertTrue(combined.tryConsume(1210, tx));
            tx.commit();
        }
        // One cell at a time: the first one drained empties before the next is touched.
        assertEquals(800, pool.stored());
        assertTrue((a.stored() == 800 && b.stored() == 0) || (b.stored() == 800 && a.stored() == 0),
                "one cell emptied before the other: " + a.stored() + ", " + b.stored());
        try (Transaction tx = Transaction.openRoot()) {
            assertFalse(combined.tryConsume(801, tx), "more than all of it");
            assertEquals(800, pool.stored(), "a refused cost takes nothing");
        }
    }

    @Test
    void fillsThePrimaryFirstThenOneCellAtATime() {
        EnergyCells pool = new EnergyCells();
        EnergyCells.Store a = cell(150, 0, 1000);
        EnergyCells.Store b = cell(150, 0, 1000);
        pool.add(a);
        pool.add(b);
        FeBuffer primary = new FeBuffer(100, 1000, 0, EnergyCellsTest::none);
        FeBuffer combined = pool.over(primary);
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(250, combined.inputOnly().insert(250, tx), "100 primary, 100 + 50 into the cells");
            tx.commit();
        }
        assertEquals(100, primary.stored());
        assertEquals(150, a.stored(), "the first cell fills completely before the second");
        assertEquals(0, b.stored());
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(150, combined.inputOnly().insert(1000, tx));
            assertEquals(0, combined.inputOnly().insert(1000, tx), "full");
            assertEquals(0, combined.inputOnly().extract(1000, tx), "the network's input gives nothing");
            tx.commit();
        }
        assertEquals(300, pool.stored());
        assertEquals(400, combined.inputOnly().getAmountAsLong());
    }

    @Test
    void aCellsTransferLimitSpillsIntoTheNext() {
        EnergyCells pool = new EnergyCells();
        EnergyCells.Store a = cell(150, 0);
        EnergyCells.Store b = cell(150, 0);
        pool.add(a);
        pool.add(b);
        FeBuffer combined = pool.over(new FeBuffer(0, 0, 0, EnergyCellsTest::none));
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(150, combined.inputOnly().insert(150, tx));
            tx.commit();
        }
        assertEquals(100, a.stored(), "100 per transfer into one cell");
        assertEquals(50, b.stored());
    }

    @Test
    void flowsFromTheEnergyNetworkKeepTheTotalsExact() {
        long[] now = {100};
        EnergyScheduler scheduler = new EnergyScheduler(() -> now[0]);
        EnergyNetwork network = scheduler.createNetwork();
        EnergyCells pool = new EnergyCells();
        pool.bind(scheduler::now);
        EnergyCells.Store a = cell(1000, 0, 7);
        EnergyCells.Store b = cell(500, 100, 3);
        EnergyCells.Store c = cell(800, 50, 4);
        pool.add(a);
        pool.add(b);
        network.add(a.endpoint());
        network.add(b.endpoint());
        EnergyEndpoint generator = EnergyEndpoint.producer();
        network.add(generator);
        generator.setSupply(6);
        for (int tick = 0; tick < 400; tick++) {
            scheduler.tick();
            now[0]++;
            if (tick % 17 == 0) {
                a.tryConsume(Math.min(3, a.stored()));
            }
            if (tick % 23 == 0) {
                b.receive(2);
            }
            if (tick == 150) {
                // A cell joining with energy, and one leaving, mid-flow.
                pool.add(c);
                network.add(c.endpoint());
            }
            if (tick == 300) {
                pool.remove(b);
                network.remove(b.endpoint());
            }
            long sum = a.stored() + (tick < 300 ? b.stored() : 0) + (tick >= 150 ? c.stored() : 0);
            assertEquals(sum, pool.stored(), "pool total at tick " + tick);
        }
        assertTrue(pool.charged() > 0, "the generator charged the cells");
        assertEquals(1000 + 800, pool.capacity());
        assertTrue(pool.stored() <= pool.capacity());
    }
}
