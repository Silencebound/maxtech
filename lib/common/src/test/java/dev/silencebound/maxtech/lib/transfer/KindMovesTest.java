package dev.silencebound.maxtech.lib.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.platform.KindStorage;
// PLATFORM-LEAK: the moves under test run in NeoForge transfer transactions (ADR-0002).
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@link KindMoves} against stores of one key: with and without transactions, and stores that move less than asked.
 * Totals must never change, whether the root commits or not.
 */
class KindMovesTest {
    private static final String KEY = "glow";

    /** Moves in a root transaction that commits when anything moved. */
    private static long move(KindStorage<String> source, KindStorage<String> target, long amount) {
        try (Transaction root = Transaction.openRoot()) {
            long moved = KindMoves.move(source, target, KEY, amount, root);
            if (moved > 0) {
                root.commit();
            }
            return moved;
        }
    }

    @Test
    void movesBetweenTransactionalStores() {
        Journaled source = new Journaled(500, 1000, 1000);
        Journaled target = new Journaled(0, 300, 1000);
        assertEquals(300, move(source, target, 400));
        assertEquals(200, source.amount);
        assertEquals(300, target.amount);
    }

    @Test
    void stingyTransactionalSourceMovesWhatItGives() {
        Journaled source = new Journaled(500, 120, 1000);
        Journaled target = new Journaled(0, 1000, 1000);
        assertEquals(120, move(source, target, 400));
        assertEquals(380, source.amount);
        assertEquals(120, target.amount);
    }

    @Test
    void foreignTargetExecutingLessKeepsTotals() {
        Journaled source = new Journaled(1000, 1000, 1000);
        Foreign target = new Foreign(0, 10_000);
        assertEquals(500, move(source, target, 1000));
        assertEquals(500, source.amount);
        assertEquals(500, target.amount);
        assertEquals(1, target.executes);
    }

    @Test
    void foreignSourceExecutingLessKeepsTotals() {
        Foreign source = new Foreign(1000, 10_000);
        Journaled target = new Journaled(0, 1000, 4000);
        assertEquals(500, move(source, target, 1000));
        assertEquals(500, source.amount);
        assertEquals(500, target.amount);
    }

    @Test
    void foreignSideTouchedOnlyWhenTheOtherSideMoves() {
        Foreign source = new Foreign(1000, 10_000);
        Journaled full = new Journaled(4000, 1000, 4000);
        assertEquals(0, move(source, full, 1000));
        assertEquals(0, source.executes);
        assertEquals(1000, source.amount);
    }

    @Test
    void twoStoresWithoutTransactionsAreRefused() {
        Foreign source = new Foreign(1000, 10_000);
        Foreign target = new Foreign(0, 10_000);
        assertEquals(0, move(source, target, 1000));
        assertEquals(1000, source.amount);
        assertEquals(0, target.amount);
        assertEquals(0, source.executes + target.executes);
    }

    @Test
    void rolledBackRootUndoesTheTransactionalSide() {
        Journaled source = new Journaled(1000, 1000, 1000);
        Journaled target = new Journaled(0, 1000, 1000);
        try (Transaction root = Transaction.openRoot()) {
            assertEquals(1000, KindMoves.move(source, target, KEY, 1000, root));
        }
        assertEquals(1000, source.amount);
        assertEquals(0, target.amount);
    }

    @Test
    void overReportingForeignTargetGetsNoMoreThanAsked() {
        Journaled source = new Journaled(1000, 1000, 1000);
        OverReporting target = new OverReporting(0, 10_000);
        assertEquals(400, move(source, target, 400));
        assertEquals(600, source.amount);
        assertEquals(400, target.amount);
        assertEquals(1, target.executes);
    }

    @Test
    void overReportingForeignSourceGivesNoMoreThanAsked() {
        OverReporting source = new OverReporting(1000, 10_000);
        Journaled target = new Journaled(0, 1000, 4000);
        assertEquals(400, move(source, target, 400));
        assertEquals(600, source.amount);
        assertEquals(400, target.amount);
        assertEquals(1, source.executes);
    }

    /** A transactional store of {@link #KEY} that moves at most {@code perCall} per insert or extract. */
    private static final class Journaled extends SnapshotJournal<Long> implements KindStorage<String> {
        long amount;
        private final long perCall;
        private final long capacity;

        Journaled(long amount, long perCall, long capacity) {
            this.amount = amount;
            this.perCall = perCall;
            this.capacity = capacity;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public @Nullable String resourceAt(int index) {
            return amount > 0 ? KEY : null;
        }

        @Override
        public long amountAt(int index) {
            return amount;
        }

        @Override
        public long insert(String key, long wanted, TransactionContext transaction) {
            long moved = Math.min(Math.min(wanted, perCall), capacity - amount);
            if (moved > 0) {
                updateSnapshots(transaction);
                amount += moved;
            }
            return moved;
        }

        @Override
        public long extract(String key, long wanted, TransactionContext transaction) {
            long moved = Math.min(Math.min(wanted, perCall), amount);
            if (moved > 0) {
                updateSnapshots(transaction);
                amount -= moved;
            }
            return moved;
        }

        @Override
        public boolean transactional() {
            return true;
        }

        @Override
        public long simulateInsert(String key, long wanted, TransactionContext transaction) {
            try (Transaction nested = Transaction.open(transaction)) {
                return insert(key, wanted, nested);
            }
        }

        @Override
        public long simulateExtract(String key, long wanted, TransactionContext transaction) {
            try (Transaction nested = Transaction.open(transaction)) {
                return extract(key, wanted, nested);
            }
        }

        @Override
        protected Long createSnapshot() {
            return amount;
        }

        @Override
        protected void revertToSnapshot(Long snapshot) {
            amount = snapshot;
        }
    }

    /** A store without transactions whose simulations report the full amount and whose executes move half. */
    private static final class Foreign implements KindStorage<String> {
        long amount;
        private final long capacity;
        int executes;

        Foreign(long amount, long capacity) {
            this.amount = amount;
            this.capacity = capacity;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public @Nullable String resourceAt(int index) {
            return amount > 0 ? KEY : null;
        }

        @Override
        public long amountAt(int index) {
            return amount;
        }

        @Override
        public long insert(String key, long wanted, TransactionContext transaction) {
            long moved = (simulateInsert(key, wanted, transaction) + 1) / 2;
            amount += moved;
            executes++;
            return moved;
        }

        @Override
        public long extract(String key, long wanted, TransactionContext transaction) {
            long moved = (simulateExtract(key, wanted, transaction) + 1) / 2;
            amount -= moved;
            executes++;
            return moved;
        }

        @Override
        public boolean transactional() {
            return false;
        }

        @Override
        public long simulateInsert(String key, long wanted, TransactionContext transaction) {
            return Math.max(0, Math.min(wanted, capacity - amount));
        }

        @Override
        public long simulateExtract(String key, long wanted, TransactionContext transaction) {
            return Math.min(wanted, amount);
        }
    }

    /**
     * A store without transactions whose executes move what was asked (as far as it fits) but report 100 more, more
     * than asked and more than simulated.
     */
    private static final class OverReporting implements KindStorage<String> {
        long amount;
        private final long capacity;
        int executes;

        OverReporting(long amount, long capacity) {
            this.amount = amount;
            this.capacity = capacity;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public @Nullable String resourceAt(int index) {
            return amount > 0 ? KEY : null;
        }

        @Override
        public long amountAt(int index) {
            return amount;
        }

        @Override
        public long insert(String key, long wanted, TransactionContext transaction) {
            amount += simulateInsert(key, wanted, transaction);
            executes++;
            return wanted + 100;
        }

        @Override
        public long extract(String key, long wanted, TransactionContext transaction) {
            amount -= simulateExtract(key, wanted, transaction);
            executes++;
            return wanted + 100;
        }

        @Override
        public boolean transactional() {
            return false;
        }

        @Override
        public long simulateInsert(String key, long wanted, TransactionContext transaction) {
            return Math.max(0, Math.min(wanted, capacity - amount));
        }

        @Override
        public long simulateExtract(String key, long wanted, TransactionContext transaction) {
            return Math.min(wanted, amount);
        }
    }
}
