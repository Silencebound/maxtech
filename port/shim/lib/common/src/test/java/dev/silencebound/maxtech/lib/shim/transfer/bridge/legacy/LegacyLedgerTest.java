package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import java.lang.ref.WeakReference;
import org.junit.jupiter.api.Test;

/** The shared transaction ledger of the 1.21.1 bridge, over a fake non-transactional inventory. */
class LegacyLedgerTest {
    private static final String STONE = "stone";
    private static final String DIRT = "dirt";

    private final LegacyLedger.Registry<String> registry = new LegacyLedger.Registry<>();

    private static int insert(LegacyLedger<String> ledger, LegacyTarget<String> via, int slot, String what, int amount,
            Transaction tx) {
        return ledger.insert(via, slot, slot, what, amount, tx);
    }

    private static int extract(LegacyLedger<String> ledger, LegacyTarget<String> via, int slot, String what,
            int amount, Transaction tx) {
        return ledger.extract(via, slot, slot, what, amount, tx);
    }

    @Test
    void rollbackLeavesInventoryAsBefore() {
        var inv = new FakeInventory(2).with(0, STONE, 40).with(1, DIRT, 10);
        var view = inv.view();
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(20, insert(ledger, view, 0, STONE, 20, tx));
            assertEquals(10, extract(ledger, view, 1, DIRT, 30, tx));
        }
        assertEquals(40, inv.count[0]);
        assertEquals(10, inv.count[1]);
        assertEquals(DIRT, inv.resource[1]);
        assertEquals(0, inv.overflow);
    }

    @Test
    void rollbackRestoresSlotThatRefusesInserts() {
        var inv = new FakeInventory(1).with(0, STONE, 30);
        inv.refusesInsert[0] = true;
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(30, extract(ledger, inv.view(), 0, STONE, 30, tx));
        }
        assertEquals(30, inv.count[0]);
        assertEquals(0, inv.overflow);
    }

    @Test
    void commitAppliesExactlyWhatWasCounted() {
        var inv = new FakeInventory(3).with(0, STONE, 60);
        var view = inv.view();
        var ledger = registry.of(inv);
        int moved = 0;
        try (var tx = Transaction.openRoot()) {
            // One operation over several slots, as the owner allowed (2026-11-28).
            for (int slot = 0; slot < 3; slot++) {
                moved += insert(ledger, view, slot, STONE, 100 - moved, tx);
            }
            tx.commit();
        }
        assertEquals(100, moved);
        assertEquals(160, inv.total(STONE));
        assertEquals(0, inv.overflow);
    }

    @Test
    void repeatedInsertsIntoOneSlotAreSimulatedTogether() {
        var inv = new FakeInventory(1).with(0, STONE, 40);
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(20, insert(ledger, inv.view(), 0, STONE, 20, tx));
            assertEquals(4, insert(ledger, inv.view(), 0, STONE, 20, tx));
            tx.commit();
        }
        assertEquals(64, inv.count[0]);
        assertEquals(0, inv.overflow);
    }

    @Test
    void nestedAbortKeepsWhatTheOuterTransactionCounted() {
        var inv = new FakeInventory(1).with(0, STONE, 10);
        var view = inv.view();
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(5, extract(ledger, view, 0, STONE, 5, tx));
            try (var inner = Transaction.open(tx)) {
                assertEquals(5, extract(ledger, view, 0, STONE, 5, inner));
                assertEquals(30, insert(ledger, view, 0, STONE, 30, inner));
            }
            assertEquals(5, inv.count[0]);
            tx.commit();
        }
        assertEquals(5, inv.count[0]);
        assertEquals(0, inv.overflow);
    }

    @Test
    void insertThenExtractOfTheSameSlotNetsOutWithoutTouchingTheInventory() {
        var inv = new FakeInventory(1);
        var view = inv.view();
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(20, insert(ledger, view, 0, STONE, 20, tx));
            assertEquals(20, extract(ledger, view, 0, STONE, 20, tx));
            tx.commit();
        }
        assertEquals(0, inv.count[0]);
        assertEquals(0, inv.realCalls);
    }

    @Test
    void aSecondResourceInOneSlotIsRefused() {
        var inv = new FakeInventory(1);
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(20, insert(ledger, inv.view(), 0, STONE, 20, tx));
            assertEquals(0, insert(ledger, inv.view(), 0, DIRT, 20, tx));
            tx.commit();
        }
        assertEquals(STONE, inv.resource[0]);
        assertEquals(20, inv.count[0]);
    }

    @Test
    void twoWrappersOverOneInventoryShareTheirLedger() {
        var inv = new FakeInventory(1).with(0, STONE, 40);
        var left = inv.view();
        var right = inv.view();
        int moved = 0;
        try (var tx = Transaction.openRoot()) {
            moved += insert(registry.of(inv), left, 0, STONE, 24, tx);
            moved += insert(registry.of(inv), right, 0, STONE, 24, tx);
            tx.commit();
        }
        assertEquals(24, moved);
        assertEquals(64, inv.count[0]);
        assertEquals(0, inv.overflow);
    }

    @Test
    void slotFilledWithSomethingElseBeforeCommitSendsTheItemsToAnotherSlot() {
        var inv = new FakeInventory(2);
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(30, insert(ledger, inv.view(), 0, STONE, 30, tx));
            inv.with(0, DIRT, 5);
            tx.commit();
        }
        assertEquals(30, inv.count[1]);
        assertEquals(30, inv.total(STONE));
        assertEquals(0, inv.overflow);
    }

    @Test
    void noRoomLeftOnCommitGoesToOverflow() {
        var inv = new FakeInventory(1);
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(30, insert(ledger, inv.view(), 0, STONE, 30, tx));
            inv.with(0, STONE, 50);
            tx.commit();
        }
        assertEquals(64, inv.count[0]);
        assertEquals(16, inv.overflow);
        assertEquals(50 + 30, inv.total(STONE));
    }

    @Test
    void slotEmptiedBeforeCommitStillGetsTheInsert() {
        var inv = new FakeInventory(1).with(0, STONE, 50);
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(14, insert(ledger, inv.view(), 0, STONE, 30, tx));
            inv.with(0, null, 0);
            tx.commit();
        }
        assertEquals(14, inv.count[0]);
        assertEquals(0, inv.overflow);
    }

    @Test
    void itemSwappedBeforeRollbackPutsTheExtractedItemsElsewhere() {
        var inv = new FakeInventory(2).with(0, STONE, 20);
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(20, extract(ledger, inv.view(), 0, STONE, 20, tx));
            inv.with(0, DIRT, 64);
        }
        assertEquals(STONE, inv.resource[1]);
        assertEquals(20, inv.total(STONE));
    }

    @Test
    void deferredExtractionsAreSimulatedTogetherAndAppliedOnCommit() {
        var inv = new FakeInventory(1).with(0, STONE, 40);
        inv.extractsImmediately = false;
        var view = inv.view();
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(30, extract(ledger, view, 0, STONE, 30, tx));
            assertEquals(10, extract(ledger, view, 0, STONE, 30, tx));
            assertEquals(40, inv.count[0]);
            tx.commit();
        }
        assertEquals(0, inv.count[0]);
    }

    @Test
    void deferredExtractionThatComesUpShortOnCommitIsReported() {
        var inv = new FakeInventory(1).with(0, STONE, 40);
        inv.extractsImmediately = false;
        var ledger = registry.of(inv);
        int before = LegacyLedger.MISMATCHES.get();
        try (var tx = Transaction.openRoot()) {
            assertEquals(40, extract(ledger, inv.view(), 0, STONE, 40, tx));
            inv.with(0, STONE, 15);
            tx.commit();
        }
        // 40 were handed on, 15 could be taken: 25 duplicated, and reported.
        assertEquals(0, inv.count[0]);
        assertEquals(before + 1, LegacyLedger.MISMATCHES.get());
    }

    @Test
    void eachPartIsAppliedThroughTheSideThatCountedIt() {
        var inv = new FakeInventory(1);
        var narrowSide = inv.view(5);
        var wideSide = inv.view();
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(5, insert(ledger, narrowSide, 0, STONE, 20, tx));
            assertEquals(20, insert(ledger, wideSide, 0, STONE, 20, tx));
            tx.commit();
        }
        assertEquals(25, inv.count[0]);
        assertEquals(0, inv.overflow);
    }

    @Test
    void cancellingThisSidesPendingInsertLeavesNothingToApply() {
        var inv = new FakeInventory(1);
        inv.extractsImmediately = false;
        var side = inv.view();
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(20, insert(ledger, side, 0, STONE, 20, tx));
            assertEquals(20, extract(ledger, side, 0, STONE, 20, tx));
            tx.commit();
        }
        assertEquals(0, inv.count[0]);
        assertEquals(0, inv.realCalls);
    }

    @Test
    void anotherSidesPendingInsertIsNotCreditedToAnExtract() {
        var inv = new FakeInventory(1);
        inv.extractsImmediately = false;
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(20, insert(ledger, inv.view(), 0, STONE, 20, tx));
            // The other side may reach another tank: nothing is there to extract yet.
            assertEquals(0, extract(ledger, inv.view(), 0, STONE, 20, tx));
            tx.commit();
        }
        assertEquals(20, inv.count[0]);
    }

    @Test
    void anotherSidesDeferredExtractIsNotCreditedToAnInsert() {
        var inv = new FakeInventory(1).with(0, STONE, 64);
        inv.extractsImmediately = false;
        var outputSide = inv.view();
        var closedSide = inv.view(0);
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(30, extract(ledger, outputSide, 0, STONE, 30, tx));
            // A side that takes nothing must not take "the put-back" of another side's extract.
            assertEquals(0, insert(ledger, closedSide, 0, STONE, 30, tx));
            tx.commit();
        }
        assertEquals(34, inv.count[0]);
        assertEquals(0, inv.overflow);
    }

    @Test
    void partsOfBothSignsFromTwoSidesAreAppliedThroughTheirSides() {
        var inv = new FakeInventory(1).with(0, STONE, 40);
        inv.extractsImmediately = false;
        var ledger = registry.of(inv);
        try (var tx = Transaction.openRoot()) {
            assertEquals(30, extract(ledger, inv.view(), 0, STONE, 30, tx));
            // Simulated without the other side's extract: 24 fit on top of the 40.
            assertEquals(24, insert(ledger, inv.view(), 0, STONE, 30, tx));
            tx.commit();
        }
        assertEquals(40 - 30 + 24, inv.count[0]);
        assertEquals(0, inv.overflow);
    }

    @Test
    void locationsWithTheSameLevelAndPositionShareALedger() {
        Object level = new Object();
        var a = registry.of(new LegacyLedger.Location(level, 7L));
        assertSame(a, registry.of(new LegacyLedger.Location(level, 7L)));
        assertNotSame(a, registry.of(new LegacyLedger.Location(level, 8L)));
        assertNotSame(a, registry.of(new LegacyLedger.Location(new Object(), 7L)));
    }

    @Test
    void aLedgerKeyDoesNotKeepItsLevelAlive() throws InterruptedException {
        Object level = new Object();
        var gone = new WeakReference<>(level);
        var ledger = registry.of(new LegacyLedger.Location(level, 7L));
        level = null;
        for (int i = 0; i < 50 && gone.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(gone.get(), "the level is still reachable through the ledger registry");
        assertNotNull(ledger);
    }

    @Test
    void unloadingALevelDropsItsKeys() {
        Object level = new Object();
        Object other = new Object();
        var held = registry.of(new LegacyLedger.Location(level, 7L));
        registry.of(new LegacyLedger.Location(other, 7L));
        registry.forget(level);
        assertEquals(1, registry.locations());
        assertNotSame(held, registry.of(new LegacyLedger.Location(level, 7L)));
    }
}
