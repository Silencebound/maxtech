package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.silencebound.maxtech.lib.shim.transfer.energy.EnergyHandler;
import dev.silencebound.maxtech.lib.shim.transfer.energy.SimpleEnergyHandler;
import dev.silencebound.maxtech.lib.shim.transfer.port.EnergyHandlerAdapter;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.junit.jupiter.api.Test;

/** The FE bridge over NeoForge 21.1's {@link EnergyStorage}, which needs no game bootstrap. */
class LegacyEnergyHandlerTest {
    @Test
    void rollbackLeavesTheStorageUntouched() {
        var storage = new EnergyStorage(1000, 1000, 1000, 500);
        var handler = new LegacyEnergyHandler(storage);
        try (var tx = Transaction.openRoot()) {
            assertEquals(300, handler.extract(300, tx));
            assertEquals(200, handler.getAmountAsLong());
        }
        assertEquals(500, storage.getEnergyStored());
        assertEquals(500, handler.getAmountAsLong());
    }

    @Test
    void rollbackOfAGeneratorLosesNothing() {
        // Extract-only, like a generator: putting energy back with receiveEnergy would fail.
        var storage = new EnergyStorage(1000, 0, 1000, 500);
        var handler = new LegacyEnergyHandler(storage);
        try (var tx = Transaction.openRoot()) {
            assertEquals(500, handler.extract(800, tx));
        }
        assertEquals(500, storage.getEnergyStored());
    }

    @Test
    void commitAppliesTheCombinedAmount() {
        var storage = new EnergyStorage(150, 1000, 1000, 0);
        var handler = new LegacyEnergyHandler(storage);
        try (var tx = Transaction.openRoot()) {
            assertEquals(100, handler.insert(100, tx));
            assertEquals(50, handler.insert(100, tx));
            assertEquals(0, storage.getEnergyStored());
            tx.commit();
        }
        assertEquals(150, storage.getEnergyStored());
    }

    @Test
    void nestedAbortKeepsTheOuterMoves() {
        var storage = new EnergyStorage(1000, 1000, 1000, 150);
        var handler = new LegacyEnergyHandler(storage);
        try (var tx = Transaction.openRoot()) {
            assertEquals(120, handler.extract(120, tx));
            try (var inner = Transaction.open(tx)) {
                assertEquals(30, handler.extract(100, inner));
            }
            assertEquals(30, handler.getAmountAsLong());
            tx.commit();
        }
        assertEquals(30, storage.getEnergyStored());
    }

    @Test
    void insertThenExtractNetsOut() {
        var storage = new EnergyStorage(1000, 1000, 1000, 100);
        var handler = new LegacyEnergyHandler(storage);
        try (var tx = Transaction.openRoot()) {
            assertEquals(200, handler.insert(200, tx));
            assertEquals(250, handler.extract(250, tx));
            tx.commit();
        }
        assertEquals(50, storage.getEnergyStored());
    }

    @Test
    void twoWrappersOverOneStorageShareTheirLedger() {
        var storage = new EnergyStorage(100, 1000, 1000, 0);
        int moved = 0;
        try (var tx = Transaction.openRoot()) {
            moved += new LegacyEnergyHandler(storage).insert(80, tx);
            moved += new LegacyEnergyHandler(storage).insert(80, tx);
            tx.commit();
        }
        assertEquals(100, moved);
        assertEquals(100, storage.getEnergyStored());
    }

    @Test
    void storageChangedBeforeCommitIsReported() {
        var storage = new EnergyStorage(100, 1000, 1000, 0);
        var handler = new LegacyEnergyHandler(storage);
        int before = LegacyLedger.MISMATCHES.get();
        try (var tx = Transaction.openRoot()) {
            assertEquals(100, handler.insert(100, tx));
            storage.receiveEnergy(60, false);
            tx.commit();
        }
        // 100 FE were taken from the other side, 40 fitted: 60 FE lost, and reported.
        assertEquals(100, storage.getEnergyStored());
        assertEquals(before + 1, LegacyLedger.MISMATCHES.get());
    }

    @Test
    void newStoragePerQueryOnOneBlockSharesTheLedger() {
        var backing = new EnergyStorage(100, 1000, 1000, 50);
        var block = new LegacyLedger.Location(new Object(), 42L);
        int extracted = 0;
        try (var tx = Transaction.openRoot()) {
            // Two cables on one block; the provider returns a new view of the same storage for each query.
            extracted += new LegacyEnergyHandler(new View(backing), block).extract(40, tx);
            extracted += new LegacyEnergyHandler(new View(backing), block).extract(40, tx);
            tx.commit();
        }
        assertEquals(50, extracted);
        assertEquals(0, backing.getEnergyStored());
    }

    /** A new object per capability query over one real storage, as many 1.21.1 providers return. */
    private record View(IEnergyStorage real) implements IEnergyStorage {
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            return real.receiveEnergy(toReceive, simulate);
        }

        @Override
        public int extractEnergy(int toExtract, boolean simulate) {
            return real.extractEnergy(toExtract, simulate);
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

    @Test
    void adapterSimulationLeavesTheLibHandlerUnchanged() {
        var lib = new SimpleEnergyHandler(1000, 1000, 1000, 100);
        IEnergyStorage adapter = new EnergyHandlerAdapter(lib);
        assertEquals(900, adapter.receiveEnergy(2000, true));
        assertEquals(100, adapter.extractEnergy(500, true));
        assertEquals(100, lib.getAmountAsLong());
        assertEquals(300, adapter.receiveEnergy(300, false));
        assertEquals(400, lib.getAmountAsLong());
    }

    @Test
    void bridgingTwiceUnwraps() {
        IEnergyStorage storage = new EnergyStorage(100);
        assertSame(storage, LegacyBridge.toLegacyEnergy(LegacyBridge.fromLegacyEnergy(storage, null, null)));
        EnergyHandler lib = new SimpleEnergyHandler(100);
        assertSame(lib, LegacyBridge.fromLegacyEnergy(LegacyBridge.toLegacyEnergy(lib), null, null));
    }
}
