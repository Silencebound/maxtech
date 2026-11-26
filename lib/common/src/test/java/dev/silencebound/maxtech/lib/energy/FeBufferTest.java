package dev.silencebound.maxtech.lib.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
// PLATFORM-LEAK: the buffer is a NeoForge transfer handler; tests drive it through its transactions.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.Test;

/** Transactions run in plain JUnit (see SKILL.md, Transfer API). */
class FeBufferTest {
    private final AtomicInteger changes = new AtomicInteger();

    private FeBuffer buffer(long capacity, long maxIn, long maxOut) {
        return new FeBuffer(capacity, maxIn, maxOut, changes::incrementAndGet);
    }

    @Test
    void limitsRoomAndStockBoundEachOperation() {
        FeBuffer buffer = buffer(1000, 300, 200);
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(300, buffer.insert(500, tx), "insert limit");
            assertEquals(300, buffer.insert(500, tx));
            assertEquals(300, buffer.insert(500, tx));
            assertEquals(100, buffer.insert(500, tx), "room");
            assertEquals(0, buffer.insert(500, tx), "full");
            tx.commit();
        }
        assertEquals(1000, buffer.stored());
        assertEquals(1, changes.get(), "one change event per committed root transaction");
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(200, buffer.extract(1000, tx), "extract limit");
            tx.commit();
        }
        assertEquals(800, buffer.stored());
    }

    @Test
    void rollbackRestoresAndFiresNothing() {
        FeBuffer buffer = buffer(1000, 1000, 1000);
        buffer.load(500);
        try (Transaction tx = Transaction.openRoot()) {
            buffer.insert(300, tx);
            assertTrue(buffer.tryConsume(700, tx));
            assertEquals(100, buffer.stored());
        }
        assertEquals(500, buffer.stored(), "closing without commit rolls back");
        assertEquals(0, changes.get());
    }

    @Test
    void paysPerOperationAllOrNothing() {
        FeBuffer buffer = buffer(1000, 0, 0);
        buffer.load(100);
        try (Transaction tx = Transaction.openRoot()) {
            assertFalse(buffer.tryConsume(101, tx), "not enough stored");
            assertTrue(buffer.tryConsume(100, tx), "the extract limit does not apply to paying");
            tx.commit();
        }
        assertEquals(0, buffer.stored());
        assertTrue(buffer.tryConsume(0));
        assertFalse(buffer.tryConsume(1));
        assertEquals(1, changes.get());
        assertEquals(400, buffer.receive(400), "receive ignores the insert limit of 0");
        assertEquals(2, changes.get());
        assertTrue(buffer.tryConsume(150));
        assertEquals(250, buffer.stored());
        assertEquals(3, changes.get());
    }

    @Test
    void viewsShareTheBufferButOneDirectionEach() {
        FeBuffer buffer = buffer(1000, 1000, 1000);
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(0, buffer.outputOnly().insert(10, tx));
            assertEquals(10, buffer.inputOnly().insert(10, tx));
            assertEquals(0, buffer.inputOnly().extract(10, tx));
            assertEquals(4, buffer.outputOnly().extract(4, tx));
            tx.commit();
        }
        assertEquals(6, buffer.stored());
        assertEquals(6, buffer.outputOnly().getAmountAsLong());
        assertEquals(1000, buffer.inputOnly().getCapacityAsLong());
    }

    @Test
    void loweredCapacityOnlyDrains() {
        FeBuffer buffer = buffer(1000, 1000, 1000);
        buffer.load(800);
        buffer.setCapacity(500);
        assertEquals(800, buffer.stored(), "the excess is kept");
        assertEquals(0, buffer.room());
        try (Transaction tx = Transaction.openRoot()) {
            assertEquals(0, buffer.insert(10, tx));
            assertEquals(100, buffer.extract(100, tx));
            tx.commit();
        }
        assertEquals(700, buffer.stored());
    }

    @Test
    void movesBetweenHandlers() {
        FeBuffer from = buffer(1000, 1000, 50);
        FeBuffer to = buffer(1000, 30, 1000);
        from.load(500);
        assertEquals(30, FeTransfer.move(from, to, 100), "the smaller limit wins");
        assertEquals(470, from.stored());
        assertEquals(30, to.stored());
        assertEquals(0, FeTransfer.move(from, to, 0));
        assertEquals(0, FeTransfer.move(from, to, -5));
    }
}
