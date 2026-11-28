package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.shim.transfer.TransferPreconditions;
import dev.silencebound.maxtech.lib.shim.transfer.energy.EnergyHandler;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.slf4j.Logger;

/**
 * An {@link EnergyHandler} over a 1.21.1 {@link IEnergyStorage}, which has no transactions. The transaction state
 * lives in a {@link LegacyLedger} shared by all wrappers on the same block: both directions are simulated
 * (combined) and applied on the root commit, so a rollback never touches the storage. If the storage changed between
 * simulation and commit, the difference is logged.
 *
 * <p>Server or client thread only, like the wrapped storage.
 */
public final class LegacyEnergyHandler implements EnergyHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final LegacyLedger.Registry<Fe> LEDGERS = new LegacyLedger.Registry<>();
    private static final int KEY = 0;

    /** The one resource of an energy ledger. */
    enum Fe {
        FE
    }

    private final IEnergyStorage storage;
    private final LegacyLedger<Fe> ledger;
    private final Real real = new Real();

    /**
     * A wrapper for a storage found at a block: every wrapper and query on that block (any side) shares one ledger,
     * since 1.21.1 providers often return a new storage per query.
     */
    public LegacyEnergyHandler(IEnergyStorage storage, Level level, BlockPos pos) {
        this(storage, new LegacyLedger.Location(level, pos.asLong()));
    }

    /** A wrapper for a storage with no block; it shares its ledger only with wrappers of the same object. */
    public LegacyEnergyHandler(IEnergyStorage storage) {
        this(storage, storage);
    }

    LegacyEnergyHandler(IEnergyStorage storage, Object ledgerKey) {
        this.storage = storage;
        this.ledger = LEDGERS.of(ledgerKey);
    }

    /** {@return the wrapped 1.21.1 storage} */
    public IEnergyStorage storage() {
        return storage;
    }

    @Override
    public long getAmountAsLong() {
        return Math.max(0, (long) storage.getEnergyStored() + ledger.pendingDelta(KEY, Fe.FE));
    }

    @Override
    public long getCapacityAsLong() {
        return storage.getMaxEnergyStored();
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        return ledger.insert(real, KEY, KEY, Fe.FE, amount, transaction);
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        return ledger.extract(real, KEY, KEY, Fe.FE, amount, transaction);
    }

    @Override
    public String toString() {
        return "LegacyEnergyHandler{" + storage + "}";
    }

    /** Real, non-transactional moves on the storage, called by the ledger. */
    private final class Real implements LegacyTarget<Fe> {
        @Override
        public Object handler() {
            return storage;
        }

        @Override
        public boolean extractsImmediately() {
            // receiveEnergy cannot put energy back into extract-only storages (generators), so extraction is deferred.
            return false;
        }

        @Override
        public int simulateExtract(int slot, Fe resource, int amount) {
            return storage.extractEnergy(amount, true);
        }

        @Override
        public int restore(int slot, Fe resource, int amount) {
            return 0;
        }

        @Override
        public int simulateInsert(int slot, Fe resource, int amount) {
            return storage.receiveEnergy(amount, true);
        }

        @Override
        public int insert(int slot, Fe resource, int amount) {
            return storage.receiveEnergy(amount, false);
        }

        @Override
        public int insertAnywhere(Fe resource, int amount) {
            return 0;
        }

        @Override
        public int extract(int slot, Fe resource, int amount) {
            return storage.extractEnergy(amount, false);
        }

        @Override
        public void overflow(Fe resource, int amount) {
            LOGGER.error("{} FE could not go back into {}; lost", amount, storage);
        }
    }
}
