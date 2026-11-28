package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.shim.transfer.transaction.SnapshotJournal;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.Test;

/**
 * Item-style moves between Lib's transactional side and a 1.21.1 handler ({@link LegacyForeignLast#into},
 * {@link LegacyForeignLast#from}), with strings as resources so no game bootstrap is needed: whatever the handler's
 * simulation says, the sum of both sides stays the same.
 */
class LegacyItemMovesTest {
    private static final String STONE = "stone";

    @Test
    void pushIntoAHandlerThatTakesLessThanItSimulatedKeepsTheRest() {
        var lib = new LibSlot(64, 64);
        var handler = new Handler(0, 64, 10, 64);
        int moved;
        try (var root = Transaction.openRoot()) {
            moved = LegacyForeignLast.into(lib, handler, STONE, 64, root);
            root.commit();
        }
        assertEquals(10, moved);
        assertEquals(10, handler.count);
        assertEquals(54, lib.count);
    }

    @Test
    void pullFromAHandlerThatGivesLessThanItSimulatedDuplicatesNothing() {
        var lib = new LibSlot(0, 64);
        var handler = new Handler(64, 64, 64, 10);
        int moved;
        try (var root = Transaction.openRoot()) {
            moved = LegacyForeignLast.from(handler, lib, STONE, 64, root);
            root.commit();
        }
        assertEquals(10, moved);
        assertEquals(10, lib.count);
        assertEquals(54, handler.count);
    }

    @Test
    void aShortRedoOnLibsSideIsTakenBackOutOfTheHandler() {
        var lib = new LibSlot(64, 64);
        lib.redoGivesAtMost = 4;
        var handler = new Handler(0, 64, 10, 64);
        int moved;
        try (var root = Transaction.openRoot()) {
            moved = LegacyForeignLast.into(lib, handler, STONE, 64, root);
            root.commit();
        }
        assertEquals(4, moved);
        assertEquals(4, handler.count);
        assertEquals(60, lib.count);
    }

    @Test
    void aShortRedoOnLibsSideIsPutBackIntoTheHandler() {
        var lib = new LibSlot(0, 64);
        lib.redoTakesAtMost = 4;
        var handler = new Handler(64, 64, 64, 10);
        int moved;
        try (var root = Transaction.openRoot()) {
            moved = LegacyForeignLast.from(handler, lib, STONE, 64, root);
            root.commit();
        }
        assertEquals(4, moved);
        assertEquals(4, lib.count);
        assertEquals(60, handler.count);
    }

    @Test
    void anHonestHandlerMovesWhatBothSidesAllow() {
        var lib = new LibSlot(30, 64);
        var handler = new Handler(50, 64, 64, 64);
        int moved;
        try (var root = Transaction.openRoot()) {
            moved = LegacyForeignLast.into(lib, handler, STONE, 30, root);
            root.commit();
        }
        assertEquals(14, moved);
        assertEquals(64, handler.count);
        assertEquals(16, lib.count);
    }

    /** One transactional slot on Lib's side. A redo (a move straight in the root) can be made to come up short. */
    private static final class LibSlot extends SnapshotJournal<Integer>
            implements LegacyForeignLast.LibSide<String> {
        int count;
        final int capacity;
        int redoGivesAtMost = Integer.MAX_VALUE;
        int redoTakesAtMost = Integer.MAX_VALUE;

        LibSlot(int count, int capacity) {
            this.count = count;
            this.capacity = capacity;
        }

        @Override
        public int insert(String resource, int amount, TransactionContext transaction) {
            int limit = transaction.depth() == 0 ? redoTakesAtMost : Integer.MAX_VALUE;
            int put = Math.min(Math.min(amount, limit), capacity - count);
            if (put > 0) {
                updateSnapshots(transaction);
                count += put;
            }
            return Math.max(0, put);
        }

        @Override
        public int extract(String resource, int amount, TransactionContext transaction) {
            int limit = transaction.depth() == 0 ? redoGivesAtMost : Integer.MAX_VALUE;
            int taken = Math.min(Math.min(amount, limit), count);
            if (taken > 0) {
                updateSnapshots(transaction);
                count -= taken;
            }
            return Math.max(0, taken);
        }

        @Override
        protected Integer createSnapshot() {
            return count;
        }

        @Override
        protected void revertToSnapshot(Integer snapshot) {
            count = snapshot;
        }
    }

    /** A 1.21.1 handler: one slot, no transactions; its simulation ignores how much it really moves per call. */
    private static final class Handler implements LegacyDirect<String> {
        int count;
        final int capacity;
        final int takesAtMost;
        final int givesAtMost;

        Handler(int count, int capacity, int takesAtMost, int givesAtMost) {
            this.count = count;
            this.capacity = capacity;
            this.takesAtMost = takesAtMost;
            this.givesAtMost = givesAtMost;
        }

        @Override
        public boolean direct() {
            return true;
        }

        @Override
        public int simulateInsertNow(String resource, int amount) {
            return Math.min(amount, capacity - count);
        }

        @Override
        public int simulateExtractNow(String resource, int amount) {
            return Math.min(amount, count);
        }

        @Override
        public int insertNow(String resource, int amount) {
            int put = Math.min(Math.min(amount, takesAtMost), capacity - count);
            count += put;
            return put;
        }

        @Override
        public int extractNow(String resource, int amount) {
            int taken = Math.min(Math.min(amount, givesAtMost), count);
            count -= taken;
            return taken;
        }
    }
}
