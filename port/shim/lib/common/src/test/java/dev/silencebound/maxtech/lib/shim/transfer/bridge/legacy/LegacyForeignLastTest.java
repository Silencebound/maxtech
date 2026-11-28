package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.shim.transfer.energy.EnergyHandlerUtil;
import dev.silencebound.maxtech.lib.shim.transfer.energy.SimpleEnergyHandler;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.junit.jupiter.api.Test;

/**
 * FE moves between Lib's handlers and 1.21.1 storages through {@code EnergyHandlerUtil.move}: at a root they run
 * foreign side last and count what the storage really moved, whatever its simulation said.
 */
class LegacyForeignLastTest {
    @Test
    void movesIntoAStorageAtOnceAndCountsWhatItTook() {
        var lib = new SimpleEnergyHandler(1000, 1000, 1000, 1000);
        var storage = new EnergyStorage(500, 1000, 1000, 0);
        assertEquals(500, EnergyHandlerUtil.move(lib, new LegacyEnergyHandler(storage), 800, null));
        assertEquals(500, lib.getAmountAsLong());
        assertEquals(500, storage.getEnergyStored());
    }

    @Test
    void aStorageThatTakesLessThanItSimulatedLosesNothing() {
        var lib = new SimpleEnergyHandler(1000, 1000, 1000, 1000);
        var storage = new Lying(new EnergyStorage(1000, 1000, 1000, 0), 300, 1000);
        int moved;
        try (var root = Transaction.openRoot()) {
            moved = EnergyHandlerUtil.move(lib, new LegacyEnergyHandler(storage), 800, root);
            root.commit();
        }
        // The deferred ledger would have taken 800 from Lib and lost 500 on commit.
        assertEquals(300, moved);
        assertEquals(300, storage.getEnergyStored());
        assertEquals(700, lib.getAmountAsLong());
    }

    @Test
    void aStorageThatGivesLessThanItSimulatedDuplicatesNothing() {
        var lib = new SimpleEnergyHandler(1000, 1000, 1000, 0);
        var storage = new Lying(new EnergyStorage(1000, 1000, 1000, 1000), 1000, 200);
        assertEquals(200, EnergyHandlerUtil.move(new LegacyEnergyHandler(storage), lib, 600, null));
        assertEquals(200, lib.getAmountAsLong());
        assertEquals(800, storage.getEnergyStored());
    }

    @Test
    void aNestedMoveStillGoesThroughTheLedger() {
        var lib = new SimpleEnergyHandler(1000, 1000, 1000, 1000);
        var storage = new EnergyStorage(1000, 1000, 1000, 0);
        try (var root = Transaction.openRoot()) {
            try (var inner = Transaction.open(root)) {
                assertEquals(400, EnergyHandlerUtil.move(lib, new LegacyEnergyHandler(storage), 400, inner));
                inner.commit();
            }
            // Deferred: an outer abort could still undo it.
            assertEquals(0, storage.getEnergyStored());
        }
        assertEquals(0, storage.getEnergyStored());
        assertEquals(1000, lib.getAmountAsLong());
    }

    @Test
    void aDirectMoveWaitsWhileTheLedgerHasAPendingMove() {
        var lib = new SimpleEnergyHandler(1000, 1000, 1000, 1000);
        var storage = new EnergyStorage(1000, 1000, 1000, 0);
        var handler = new LegacyEnergyHandler(storage);
        try (var root = Transaction.openRoot()) {
            assertEquals(100, handler.insert(100, root));
            assertEquals(0, EnergyHandlerUtil.move(lib, handler, 500, root));
            root.commit();
        }
        assertEquals(100, storage.getEnergyStored());
        assertEquals(1000, lib.getAmountAsLong());
    }

    /** A storage whose simulation promises more than it really moves. */
    private record Lying(IEnergyStorage real, int maxReceive, int maxExtract) implements IEnergyStorage {
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            return simulate ? real.receiveEnergy(toReceive, true)
                    : real.receiveEnergy(Math.min(toReceive, maxReceive), false);
        }

        @Override
        public int extractEnergy(int toExtract, boolean simulate) {
            return simulate ? real.extractEnergy(toExtract, true)
                    : real.extractEnergy(Math.min(toExtract, maxExtract), false);
        }

        @Override
        public int getEnergyStored() {
            return real.getEnergyStored();
        }

        @Override
        public int getMaxEnergyStored() {
            return real.getMaxEnergyStored();
        }

        @Override
        public boolean canExtract() {
            return real.canExtract();
        }

        @Override
        public boolean canReceive() {
            return real.canReceive();
        }
    }
}
