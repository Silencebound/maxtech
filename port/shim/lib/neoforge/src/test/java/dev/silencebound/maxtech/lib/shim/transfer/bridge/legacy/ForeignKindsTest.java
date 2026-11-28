package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindHandler;
import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.resource.Resource;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link ForeignKinds} over fake handlers: which handlers become non-transactional, and where their moves go. */
class ForeignKindsTest {
    @Test
    void aHandlerThatWantsDirectMovesBecomesNonTransactional() {
        var handler = new Fake(true, 100);
        var wrapped = new Reads();
        KindHandler<String> kind = ForeignKinds.wrap(handler, wrapped, Name::new);
        assertFalse(kind.transactional());
        try (var root = Transaction.openRoot()) {
            assertEquals(40, kind.simulateInsert("water", 40, root));
            assertEquals(0, handler.count, "a simulation changes nothing");
            assertEquals(40, kind.insert("water", 40, root));
            // Not undone by the abort below: the caller commits at once (KindMoves).
        }
        assertEquals(40, handler.count);
        assertEquals("water", handler.last);
        assertEquals(7, kind.amountAt(0), "reads go to the bridge's own wrapper");
    }

    @Test
    void anyOtherHandlerKeepsTheBridgesWrapper() {
        var wrapped = new Reads();
        assertSame(wrapped, ForeignKinds.wrap(new Fake(false, 100), wrapped, Name::new));
        assertSame(wrapped, ForeignKinds.wrap(new Plain(), wrapped, Name::new));
    }

    record Name(String id) implements Resource {
        @Override
        public boolean isEmpty() {
            return id.isEmpty();
        }
    }

    /** The bridge's own (transactional) wrapper; only its reads matter here. */
    private static final class Reads implements KindHandler<String> {
        @Override
        public int size() {
            return 1;
        }

        @Override
        public Optional<String> resourceAt(int index) {
            return Optional.of("water");
        }

        @Override
        public long amountAt(int index) {
            return 7;
        }

        @Override
        public long insert(String key, long amount, TransactionContext transaction) {
            throw new AssertionError("moves must not reach the transactional wrapper");
        }

        @Override
        public long extract(String key, long amount, TransactionContext transaction) {
            throw new AssertionError("moves must not reach the transactional wrapper");
        }

        @Override
        public boolean transactional() {
            return true;
        }
    }

    /** A handler with no direct moves at all. */
    private static class Plain implements ResourceHandler<Name> {
        @Override
        public int size() {
            return 1;
        }

        @Override
        public Name getResource(int index) {
            return new Name("");
        }

        @Override
        public long getAmountAsLong(int index) {
            return 0;
        }

        @Override
        public long getCapacityAsLong(int index, Name resource) {
            return 100;
        }

        @Override
        public boolean isValid(int index, Name resource) {
            return true;
        }

        @Override
        public int insert(int index, Name resource, int amount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public int extract(int index, Name resource, int amount, TransactionContext transaction) {
            return 0;
        }
    }

    /** A 1.21.1 wrapper stand-in: one tank, direct moves when {@code direct}. */
    private static final class Fake extends Plain implements LegacyDirect<Name> {
        private final boolean direct;
        private final int capacity;
        int count;
        String last = "";

        Fake(boolean direct, int capacity) {
            this.direct = direct;
            this.capacity = capacity;
        }

        @Override
        public boolean direct() {
            return direct;
        }

        @Override
        public int simulateInsertNow(Name resource, int amount) {
            return Math.min(amount, capacity - count);
        }

        @Override
        public int simulateExtractNow(Name resource, int amount) {
            return Math.min(amount, count);
        }

        @Override
        public int insertNow(Name resource, int amount) {
            int put = simulateInsertNow(resource, amount);
            count += put;
            last = resource.id();
            return put;
        }

        @Override
        public int extractNow(Name resource, int amount) {
            int taken = simulateExtractNow(resource, amount);
            count -= taken;
            return taken;
        }
    }

    @Test
    void extractAndItsSimulationGoToTheHandler() {
        var handler = new Fake(true, 100);
        handler.count = 30;
        KindHandler<String> kind = ForeignKinds.wrap(handler, new Reads(), Name::new);
        try (var root = Transaction.openRoot()) {
            assertEquals(30, kind.simulateExtract("water", 50, root));
            assertEquals(30, handler.count);
            assertEquals(20, kind.extract("water", 20, root));
            root.commit();
        }
        assertEquals(10, handler.count);
        assertTrue(kind.size() == 1);
    }
}
