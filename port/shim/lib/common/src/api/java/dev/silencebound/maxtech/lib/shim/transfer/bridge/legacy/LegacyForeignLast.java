package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.shim.transfer.energy.EnergyHandler;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * FE moves between a 1.21.1 storage and a transactional handler, foreign side last, as main's {@code KindMoves} does
 * for other kinds: simulate the 1.21.1 side, move the transactional side in a nested transaction, carry out the 1.21.1
 * side at once ({@link LegacyDirectEnergy}) and keep on the transactional side exactly what it really moved. Used by
 * {@code EnergyHandlerUtil.move} for a root transaction (or none); the caller must commit that root right after, with
 * nothing in between that can fail or abort. Server thread only.
 *
 * <p>The 1.21.1 side cannot roll back. {@code EnergyHandlerUtil.move} is public api, so a caller that aborts its root
 * after such a move loses (pushed) or duplicates (pulled) the FE that moved; and if the redo on the transactional side
 * comes up short, that difference is only logged. Lib's own callers ({@code FeTransfer}, {@code EnergyChannel}) commit
 * at once. The root-abort rule holds for {@link #into} and {@link #from} (items in {@code ItemChannel}) too; there a
 * short redo is moved back on the 1.21.1 side instead, as far as the handler takes it.
 */
public final class LegacyForeignLast {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** {@link #energy}'s answer when the move is not one for this class. */
    public static final int NOT_FOREIGN = -1;

    private LegacyForeignLast() {
    }

    /**
     * Lib's own, transactional side of a {@link #into}/{@link #from} move: one slot to take from, or the buffers to
     * put into. Implementations are reused per channel, so a move allocates nothing.
     *
     * @param <R> the resource type
     */
    public interface LibSide<R> {
        /** Inserts in {@code transaction}; {@return what went in} */
        int insert(R resource, int amount, TransactionContext transaction);

        /** Extracts in {@code transaction}; {@return what came out} */
        int extract(R resource, int amount, TransactionContext transaction);
    }

    /**
     * Moves up to {@code amount} of {@code resource} from Lib's {@code source} into the 1.21.1 {@code target}, foreign
     * side last, inside {@code root}; {@return what moved}. When it returns more than 0 the target has changed for
     * real: the caller commits {@code root} at once and moves nothing more in it.
     */
    public static <R> int into(LibSide<R> source, LegacyDirect<R> target, R resource, int amount,
            TransactionContext root) {
        int offer = Math.min(amount, target.simulateInsertNow(resource, amount));
        if (offer <= 0) {
            return 0;
        }
        int done;
        try (Transaction part = Transaction.open(root)) {
            int taken = source.extract(resource, offer, part);
            if (taken <= 0) {
                return 0;
            }
            done = Math.max(0, Math.min(taken, target.insertNow(resource, taken)));
            if (done == taken) {
                part.commit();
                return done;
            }
        }
        // The handler took less than it simulated and the nested part rolled back: take exactly that again. If Lib's
        // side gives less now, take the difference back out of the handler rather than duplicate it.
        if (done > 0) {
            int again = Math.max(0, source.extract(resource, done, root));
            if (again < done) {
                int back = target.extractNow(resource, done - again);
                LOGGER.error("Item move: {} took {} x {} without a transaction, Lib's side gave {}; {} taken back", target,
                        done, resource, again, back);
                return again;
            }
        }
        return done;
    }

    /**
     * Moves up to {@code amount} of {@code resource} from the 1.21.1 {@code source} into Lib's {@code target}, foreign
     * side last, inside {@code root}; {@return what moved}. Same contract as {@link #into}.
     */
    public static <R> int from(LegacyDirect<R> source, LibSide<R> target, R resource, int amount,
            TransactionContext root) {
        int offer = Math.min(amount, source.simulateExtractNow(resource, amount));
        if (offer <= 0) {
            return 0;
        }
        int done;
        try (Transaction part = Transaction.open(root)) {
            int put = target.insert(resource, offer, part);
            if (put <= 0) {
                return 0;
            }
            done = Math.max(0, Math.min(put, source.extractNow(resource, put)));
            if (done == put) {
                part.commit();
                return done;
            }
        }
        // The handler gave less than it simulated and the nested part rolled back: put exactly that in again. If Lib's
        // side takes less now, put the difference back into the handler rather than lose it.
        if (done > 0) {
            int again = Math.max(0, target.insert(resource, done, root));
            if (again < done) {
                int back = source.insertNow(resource, done - again);
                LOGGER.error("Item move: {} gave {} x {} without a transaction, Lib's side took {}; {} put back", source,
                        done, resource, again, back);
                return again;
            }
        }
        return done;
    }

    /**
     * Moves up to {@code amount} FE from {@code from} to {@code to} foreign side last when exactly one of them is a
     * {@link LegacyDirectEnergy} and {@code transaction} is a root or null (then this opens and commits its own root);
     * {@return what moved, or {@link #NOT_FOREIGN} when the move is not one for this class}
     */
    public static int energy(EnergyHandler from, EnergyHandler to, int amount,
            @Nullable TransactionContext transaction) {
        boolean fromForeign = from instanceof LegacyDirectEnergy;
        boolean toForeign = to instanceof LegacyDirectEnergy;
        if (fromForeign == toForeign || (transaction != null && transaction.depth() != 0)) {
            return NOT_FOREIGN;
        }
        if (transaction != null) {
            return energyIn(from, to, amount, transaction);
        }
        try (Transaction root = Transaction.openRoot()) {
            int moved = energyIn(from, to, amount, root);
            root.commit();
            return moved;
        }
    }

    private static int energyIn(EnergyHandler from, EnergyHandler to, int amount, TransactionContext root) {
        if (amount <= 0) {
            return 0;
        }
        return to instanceof LegacyDirectEnergy foreign ? intoForeign(from, foreign, amount, root)
                : fromForeign((LegacyDirectEnergy) from, to, amount, root);
    }

    private static int intoForeign(EnergyHandler source, LegacyDirectEnergy target, int amount,
            TransactionContext root) {
        int offer = Math.min(amount, target.simulateInsertNow(amount));
        if (offer <= 0) {
            return 0;
        }
        int done;
        try (Transaction part = Transaction.open(root)) {
            int taken = source.extract(offer, part);
            if (taken <= 0) {
                return 0;
            }
            done = Math.max(0, Math.min(taken, target.insertNow(taken)));
            if (done == taken) {
                part.commit();
                return done;
            }
        }
        // The storage took less than it simulated and the nested part rolled back: take exactly that again.
        if (done > 0) {
            int again = source.extract(done, root);
            if (again != done) {
                LOGGER.error("Energy move: {} took {} FE without a transaction, {} gave {}", target, done, source,
                        again);
            }
        }
        return done;
    }

    private static int fromForeign(LegacyDirectEnergy source, EnergyHandler target, int amount,
            TransactionContext root) {
        int offer = Math.min(amount, source.simulateExtractNow(amount));
        if (offer <= 0) {
            return 0;
        }
        int done;
        try (Transaction part = Transaction.open(root)) {
            int put = target.insert(offer, part);
            if (put <= 0) {
                return 0;
            }
            done = Math.max(0, Math.min(put, source.extractNow(put)));
            if (done == put) {
                part.commit();
                return done;
            }
        }
        // The storage gave less than it simulated and the nested part rolled back: put exactly that in again.
        if (done > 0) {
            int again = target.insert(done, root);
            if (again != done) {
                LOGGER.error("Energy move: {} gave {} FE without a transaction, {} took {}", source, done, target,
                        again);
            }
        }
        return done;
    }
}
