package dev.silencebound.maxtech.storage.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.energy.FeBuffer;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.Test;

/** A subnet's store backed by its parent ({@link BackedBuffer}): all-or-nothing payments, loops and outputs. */
class BackedBufferTest {
    private static FeBuffer buffer(long stored) {
        FeBuffer buffer = new FeBuffer(10_000, 10_000, 10_000, () -> {
        });
        buffer.load(stored);
        return buffer;
    }

    /** A parent store that says it holds FE but refuses every payment, as a store changed behind the check would. */
    private static FeBuffer refusing(long stored) {
        return new FeBuffer(10_000, 10_000, 10_000, () -> {
        }) {
            {
                load(stored);
            }

            @Override
            public boolean tryConsume(long cost, TransactionContext transaction) {
                return false;
            }

            @Override
            public boolean tryConsume(long cost) {
                return false;
            }
        };
    }

    @Test
    void paysOwnFirstThenParent() {
        FeBuffer own = buffer(5);
        FeBuffer parent = buffer(20);
        BackedBuffer backed = new BackedBuffer().bind(own, () -> parent);
        assertEquals(25, backed.stored());
        try (Transaction transaction = Transaction.openRoot()) {
            assertTrue(backed.tryConsume(8, transaction));
            transaction.commit();
        }
        assertEquals(0, own.stored());
        assertEquals(17, parent.stored());
        assertTrue(backed.tryConsume(3));
        assertEquals(14, parent.stored());
    }

    @Test
    void failedParentShareKeepsOwnShare() {
        FeBuffer own = buffer(5);
        BackedBuffer backed = new BackedBuffer().bind(own, () -> refusing(20));
        try (Transaction transaction = Transaction.openRoot()) {
            assertFalse(backed.tryConsume(8, transaction));
            // The caller commits anyway: the nested transaction already undid the own share.
            transaction.commit();
        }
        assertEquals(5, own.stored(), "own FE after a failed transactional payment");
        assertFalse(backed.tryConsume(8));
        assertEquals(5, own.stored(), "own FE after a failed plain payment");
    }

    @Test
    void tooLittleEverywhereChangesNothing() {
        FeBuffer own = buffer(1);
        FeBuffer parent = buffer(1);
        BackedBuffer backed = new BackedBuffer().bind(own, () -> parent);
        assertFalse(backed.tryConsume(3));
        try (Transaction transaction = Transaction.openRoot()) {
            assertFalse(backed.tryConsume(3, transaction));
            transaction.commit();
        }
        assertEquals(1, own.stored());
        assertEquals(1, parent.stored());
    }

    @Test
    void loopCountsEachStoreOnce() {
        FeBuffer ownA = buffer(10);
        FeBuffer ownB = buffer(0);
        BackedBuffer[] stores = new BackedBuffer[2];
        stores[0] = new BackedBuffer().bind(ownA, () -> stores[1]);
        stores[1] = new BackedBuffer().bind(ownB, () -> stores[0]);
        assertEquals(10, stores[0].stored(), "A sees its own FE once");
        assertEquals(10, stores[1].stored(), "B sees A's FE once");
        assertFalse(stores[1].tryConsume(15), "B pays more than the loop holds");
        assertEquals(10, ownA.stored());
        assertTrue(stores[1].tryConsume(10));
        assertEquals(0, ownA.stored());
    }

    @Test
    void outputGivesNothing() {
        FeBuffer own = new FeBuffer(100, 100, 0, () -> {
        });
        own.load(50);
        BackedBuffer backed = new BackedBuffer().bind(own, () -> buffer(20));
        try (Transaction transaction = Transaction.openRoot()) {
            assertEquals(0, backed.outputOnly().extract(10, transaction), "output takes nothing out");
            assertEquals(0, backed.outputOnly().insert(10, transaction), "output takes nothing in");
            transaction.commit();
        }
        assertEquals(50, own.stored());
    }
}
