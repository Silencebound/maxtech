package dev.silencebound.maxtech.storage.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.energy.EdgeSettings;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import java.util.Random;
// PLATFORM-LEAK: the view is a NeoForge transfer handler; tests drive it through its transactions.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.Test;

/** Storage's FE view converts at the configured rate, never creates energy, keeps fractions and follows export. */
class FeViewTest {
    private static final EdgeSettings ONE = EdgeSettings.DEFAULTS;

    private static void none() {
    }

    private static FeBuffer store(long capacity) {
        return new FeBuffer(capacity, Long.MAX_VALUE, Long.MAX_VALUE, FeViewTest::none);
    }

    private static EdgeSettings rate(int fe, int mu) {
        return new EdgeSettings(new FeRate(fe, mu), true, 0, 0, 20);
    }

    private static FeView view(FeBuffer store, EdgeSettings settings) {
        return FeView.withSettings(store, true, true, () -> null, () -> 0L, () -> settings);
    }

    private static int insert(FeView view, int fe) {
        try (Transaction tx = Transaction.openRoot()) {
            int moved = view.insert(fe, tx);
            tx.commit();
            return moved;
        }
    }

    private static int extract(FeView view, int fe) {
        try (Transaction tx = Transaction.openRoot()) {
            int moved = view.extract(fe, tx);
            tx.commit();
            return moved;
        }
    }

    @Test
    void oneToOneIsExact() {
        FeBuffer store = store(1000);
        FeView view = view(store, ONE);
        assertEquals(300, insert(view, 300));
        assertEquals(300, store.stored());
        assertEquals(120, extract(view, 120));
        assertEquals(180, store.stored());
        assertEquals(180, view.getAmountAsLong());
        assertEquals(1000, view.getCapacityAsLong());
        assertEquals(0, view.held(), "nothing held at 1:1");
    }

    @Test
    void smallInsertsAtAnUnevenRateAddUpLikeOneLargeOne() {
        // 7 FE are worth 3 MU: one FE alone is not a whole MU.
        FeBuffer small = store(10_000);
        FeView view = view(small, rate(7, 3));
        for (int i = 0; i < 700; i++) {
            assertEquals(1, insert(view, 1), "each FE is taken");
        }
        FeBuffer large = store(10_000);
        assertEquals(700, insert(view(large, rate(7, 3)), 700));
        assertEquals(large.stored(), small.stored(), "a stream equals one transfer");
        assertEquals(300, small.stored());
    }

    @Test
    void aFullStoreTakesOnlyTheFeThatPaysForWhatFits() {
        // 3 FE are worth 7 MU: 10 MU of room are worth 4 2/7 FE.
        FeBuffer store = store(10);
        FeView view = view(store, rate(3, 7));
        int taken = insert(view, 100);
        assertEquals(10, store.stored(), "filled");
        assertTrue(taken * 7L >= 10L * 3 && taken <= 5,
                taken + " FE for 10 MU: paid in full, not more than one FE over");
    }

    @Test
    void roundTripsNeverGain() {
        Random random = new Random(4);
        for (int run = 0; run < 300; run++) {
            int fe = 1 + random.nextInt(20);
            int mu = 1 + random.nextInt(20);
            FeBuffer store = store(1_000_000);
            FeView view = view(store, rate(fe, mu));
            long in = 0;
            long out = 0;
            for (int step = 0; step < 50; step++) {
                in += insert(view, random.nextInt(200));
                out += extract(view, random.nextInt(200));
            }
            // Whatever is left (in the store and held) is worth exactly what came in less what went out.
            long leftUnits = new FeRate(fe, mu).unitsOfMu(store.stored()) + view.held();
            assertTrue(out <= in, "never more out than in at " + fe + ":" + mu);
            assertEquals((in - out) * mu, leftUnits, "nothing created or lost at " + fe + ":" + mu);
        }
    }

    @Test
    void aRolledBackMoveChangesNothing() {
        FeBuffer store = store(1000);
        FeView view = view(store, rate(7, 3));
        insert(view, 5);
        long stored = store.stored();
        long held = view.held();
        try (Transaction tx = Transaction.openRoot()) {
            assertTrue(view.insert(40, tx) > 0);
            assertTrue(view.extract(3, tx) > 0);
        }
        assertEquals(stored, store.stored(), "store back");
        assertEquals(held, view.held(), "fraction back");
    }

    @Test
    void exportFollowsTheSettings() {
        FeBuffer store = store(1000);
        store.receive(1000);
        FeView off = view(store, new EdgeSettings(FeRate.ONE_TO_ONE, false, 0, 0, 20));
        assertEquals(0, extract(off, 100), "export off sends nothing out");
        assertEquals(50, insert(view(store(100), new EdgeSettings(FeRate.ONE_TO_ONE, false, 0, 0, 20)), 50),
                "energy coming in is never limited");
        long[] tick = {5};
        FeView limited = FeView.withSettings(store, true, true, () -> null, () -> tick[0],
                () -> new EdgeSettings(FeRate.ONE_TO_ONE, true, 30, 0, 20));
        assertEquals(30, extract(limited, 100), "per tick limit");
        assertEquals(0, extract(limited, 100), "used up this tick");
        tick[0]++;
        assertEquals(30, extract(limited, 100), "next tick");
        assertEquals(940, store.stored());
    }

    @Test
    void theHeldFractionSurvivesARateChangeInWholeFe() {
        FeBuffer store = store(1000);
        EdgeSettings[] settings = {rate(7, 3)};
        FeView view = FeView.withSettings(store, true, true, () -> null, () -> 0L, () -> settings[0]);
        insert(view, 2);
        assertEquals(0, store.stored(), "2 FE are not a whole MU at 7:3");
        settings[0] = ONE;
        assertEquals(1, insert(view, 1));
        assertEquals(3, store.stored(), "the 2 FE held came in with the next FE at 1:1");
    }
}
