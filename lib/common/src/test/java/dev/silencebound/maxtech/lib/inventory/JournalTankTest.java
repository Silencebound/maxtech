package dev.silencebound.maxtech.lib.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
// PLATFORM-LEAK: the machine's tank view moves inside NeoForge transactions; tests drive them directly.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.Test;

/** The machine's transactional tank view, with string keys (transactions run in plain JUnit). */
class JournalTankTest {
    private final AtomicInteger changes = new AtomicInteger();
    private final TankJournal<String> journal = new TankJournal<>(1000, key -> !key.equals("lava"),
            changes::incrementAndGet);
    private final JournalTank<String> tank = new JournalTank<>(journal);

    @Test
    void exactMovesAreAllOrNothing() {
        journal.set("water", 700);
        changes.set(0);
        try (Transaction tx = Transaction.openRoot()) {
            assertFalse(tank.insertExactly("water", 301, tx), "more than the room");
            assertEquals(700, journal.amount(), "a refused insert left some in");
            assertFalse(tank.extractExactly("water", 701, tx), "more than held");
            assertEquals(700, journal.amount(), "a refused extract took some");
            assertFalse(tank.insertExactly("lava", 10, tx), "filtered key");
            assertFalse(tank.insertExactly("milk", 10, tx), "another key");
            assertTrue(tank.insertExactly("water", 300, tx));
            assertTrue(tank.extractExactly("water", 1000, tx));
            assertTrue(tank.insertExactly("milk", 0, tx), "nothing to move");
            assertEquals(0, changes.get(), "reported before the root commits");
            tx.commit();
        }
        assertEquals(0, journal.amount());
        assertEquals(Optional.empty(), journal.resource());
        assertEquals(1, changes.get(), "one change per committed root");
    }

    @Test
    void rollsBackWithTheRoot() {
        journal.set("water", 400);
        changes.set(0);
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(400, tank.extract("water", 999, tx));
            try (Transaction inner = Transaction.open(tx)) {
                assertTrue(tank.insertExactly("milk", 1000, inner));
                inner.commit();
            }
            assertEquals(Optional.of("milk"), journal.resource());
        }
        assertEquals(Optional.of("water"), journal.resource(), "key restored");
        assertEquals(400, journal.amount(), "amount restored");
        assertEquals(0, changes.get(), "a rollback was reported");
    }

    @Test
    void takesNothingWhileBlockedOrOverfull() {
        journal.block(true);
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(0, tank.insert("water", 10, tx), "blocked tank took");
            assertFalse(tank.insertExactly("water", 10, tx), "blocked tank took exactly");
            tx.commit();
        }
        journal.block(false);
        journal.load("water", 1500);
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(0, tank.insert("water", 10, tx), "overfull tank took");
            assertTrue(tank.extractExactly("water", 600, tx), "overfull tank drains");
            assertTrue(tank.insertExactly("water", 100, tx), "room again below the capacity");
            tx.commit();
        }
        assertEquals(1000, journal.amount());
    }
}
