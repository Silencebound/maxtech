package dev.silencebound.maxtech.lib.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
// PLATFORM-LEAK: the tank is NeoForge transaction state; tests drive it through its transactions.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.Test;

/** Tank contents as transaction state, with string keys (transactions run in plain JUnit). */
class TankJournalTest {
    private final AtomicInteger changes = new AtomicInteger();

    private TankJournal<String> tank(long capacity) {
        return new TankJournal<>(capacity, key -> !key.equals("lava"), changes::incrementAndGet);
    }

    @Test
    void movesWithinCapacityAndOneKey() {
        TankJournal<String> tank = tank(1000);
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(600, tank.insert("water", 600, tx));
            assertEquals(400, tank.insert("water", 600, tx), "room");
            assertEquals(0, tank.insert("water", 1, tx), "full");
            assertEquals(250, tank.extract("water", 250, tx));
            assertEquals(0, tank.insert("milk", 100, tx), "another key while not empty");
            assertEquals(0, tank.extract("milk", 100, tx), "a key the tank does not hold");
            tx.commit();
        }
        assertEquals(750, tank.amount());
        assertEquals(Optional.of("water"), tank.resource());
        assertEquals(1, changes.get(), "one change per committed root");
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(750, tank.extract("water", 5000, tx));
            assertEquals(Optional.empty(), tank.resource(), "empty after the last of it");
            assertEquals(100, tank.insert("milk", 100, tx), "empty takes another key");
            tx.commit();
        }
        assertEquals(Optional.of("milk"), tank.resource());
    }

    @Test
    void filterAndBlockRefuse() {
        TankJournal<String> tank = tank(1000);
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(0, tank.insert("lava", 100, tx), "filter");
            tank.block(true);
            assertEquals(0, tank.insert("water", 100, tx), "blocked");
            tank.block(false);
            assertEquals(0, tank.insert("water", 0, tx), "nothing wanted");
            tx.commit();
        }
        assertEquals(0, tank.amount());
        assertEquals(0, changes.get(), "nothing changed");
    }

    @Test
    void abortRestoresExactly() {
        TankJournal<String> tank = tank(1000);
        tank.load("water", 300);
        Optional<String> before = tank.resource();
        try (Transaction tx = Transaction.openRoot()) {
            tank.extract("water", 300, tx);
            tank.insert("milk", 900, tx);
            assertEquals(Optional.of("milk"), tank.resource());
        }
        assertEquals(300, tank.amount());
        assertEquals("water", tank.key());
        assertEquals(before, tank.resource());
        assertEquals(0, changes.get(), "a rollback reports nothing");
    }

    @Test
    void innerAbortRevertsOnlyItsPart() {
        TankJournal<String> tank = tank(1000);
        try (Transaction root = Transaction.openRoot()) {
            tank.insert("water", 200, root);
            try (Transaction inner = Transaction.open(root)) {
                tank.insert("water", 500, inner);
            }
            assertEquals(200, tank.amount(), "inner part reverted");
            try (Transaction inner = Transaction.open(root)) {
                tank.insert("water", 100, inner);
                inner.commit();
            }
            root.commit();
        }
        assertEquals(300, tank.amount());
        assertEquals(1, changes.get());
    }

    @Test
    void resourceKeepsItsInstanceAndSetReportsAtOnce() {
        TankJournal<String> tank = tank(1000);
        tank.set("water", 100);
        assertEquals(1, changes.get(), "set reports at once");
        Optional<String> kept = tank.resource();
        try (Transaction tx = Transaction.openRoot()) {
            tank.insert("water", 100, tx);
            tx.commit();
        }
        assertSame(kept, tank.resource(), "same Optional while the key stays");
        try (Transaction tx = Transaction.openRoot()) {
            tank.insert("water", 100, tx);
            tank.extract("water", 100, tx);
            tx.commit();
        }
        assertEquals(2, changes.get(), "a root that ends where it began reports nothing");
        tank.set("water", 0);
        assertEquals(Optional.empty(), tank.resource(), "amount 0 empties");
        assertEquals(null, tank.key());
    }
}
