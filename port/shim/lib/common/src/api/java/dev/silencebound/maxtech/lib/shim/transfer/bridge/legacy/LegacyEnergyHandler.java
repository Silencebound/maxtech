package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.shim.transfer.TransferPreconditions;
import dev.silencebound.maxtech.lib.shim.transfer.energy.EnergyHandler;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.SnapshotJournal;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.slf4j.Logger;

/**
 * An {@link EnergyHandler} over a 1.21.1 {@link IEnergyStorage}, which has no transactions. Moves are simulated on the
 * real storage, combined per root transaction, and applied when it commits, so a rollback never touches the storage.
 * A storage whose simulation promises more than it then accepts would lose or create energy on commit; that is logged
 * as an error.
 *
 * <p>Server or client thread only, like the wrapped storage.
 */
public final class LegacyEnergyHandler implements EnergyHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final IEnergyStorage storage;
    private final Pending pending = new Pending();

    public LegacyEnergyHandler(IEnergyStorage storage) {
        this.storage = storage;
    }

    /** {@return the wrapped 1.21.1 storage} */
    public IEnergyStorage storage() {
        return storage;
    }

    @Override
    public long getAmountAsLong() {
        return Math.max(0, (long) storage.getEnergyStored() + pending.delta);
    }

    @Override
    public long getCapacityAsLong() {
        return storage.getMaxEnergyStored();
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        if (amount == 0) {
            return 0;
        }
        int delta = pending.delta;
        int accepted;
        if (delta >= 0) {
            accepted = storage.receiveEnergy(LegacyMath.add(delta, amount), true) - delta;
        } else {
            int back = Math.min(amount, -delta);
            int rest = amount - back;
            accepted = back + (rest > 0 ? storage.receiveEnergy(rest, true) : 0);
        }
        if (accepted <= 0) {
            return 0;
        }
        pending.set(delta + accepted, transaction);
        return accepted;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        if (amount == 0) {
            return 0;
        }
        int delta = pending.delta;
        int accepted;
        if (delta <= 0) {
            accepted = storage.extractEnergy(LegacyMath.add(-delta, amount), true) + delta;
        } else {
            int back = Math.min(amount, delta);
            int rest = amount - back;
            accepted = back + (rest > 0 ? storage.extractEnergy(rest, true) : 0);
        }
        if (accepted <= 0) {
            return 0;
        }
        pending.set(delta - accepted, transaction);
        return accepted;
    }

    @Override
    public String toString() {
        return "LegacyEnergyHandler{" + storage + "}";
    }

    /** The combined pending energy change of the current root transaction. */
    private final class Pending extends SnapshotJournal<Integer> {
        int delta;

        void set(int newDelta, TransactionContext transaction) {
            updateSnapshots(transaction);
            delta = newDelta;
        }

        @Override
        protected Integer createSnapshot() {
            return delta;
        }

        @Override
        protected void revertToSnapshot(Integer snapshot) {
            delta = snapshot;
        }

        @Override
        protected void onRootCommit(Integer originalState) {
            int change = delta;
            delta = 0;
            if (change > 0) {
                int received = storage.receiveEnergy(change, false);
                if (received < change) {
                    LOGGER.error("{} accepted {} FE in simulation but not on commit; {} FE lost", storage, change,
                            change - received);
                }
            } else if (change < 0) {
                int extracted = storage.extractEnergy(-change, false);
                if (extracted < -change) {
                    LOGGER.error("{} gave {} FE in simulation but not on commit; {} FE created", storage, -change,
                            -change - extracted);
                }
            }
        }
    }
}
