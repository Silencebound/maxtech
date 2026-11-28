package dev.silencebound.maxtech.lib.transfer;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.platform.KindStorage;
// PLATFORM-LEAK: port moves are NeoForge transfer transactions against the real handlers (ADR-0002, ADR-0018).
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.slf4j.Logger;

/**
 * One move of one key between two {@link KindStorage}s inside a port operation's root transaction, counting only what
 * both sides moved (ADR-0017, ADR-0018):
 * <ul>
 * <li>Both sides transactional: in a nested transaction, insert into the target, then extract that from the source. A
 * source that gives less rolls the nested part back, and the move is tried once more with what it gave.</li>
 * <li>One side without transactions ("foreign side last"): simulate the foreign side, move the transactional side in a
 * nested transaction, carry out the foreign side, and keep on the transactional side only what the foreign side really
 * moved. The foreign side cannot roll back, so after such a move the caller commits the root at once and runs nothing
 * that can fail or abort in between.</li>
 * <li>Both sides without transactions: refused, nothing moves (owner).</li>
 * </ul>
 * Server thread only.
 */
final class KindMoves {
    private static final Logger LOGGER = LogUtils.getLogger();

    private KindMoves() {
    }

    /**
     * Moves up to {@code amount} of {@code key} from {@code source} to {@code target} as part of {@code root}; returns
     * what moved, 0 for nothing (or a refusal).
     */
    static <K> long move(KindStorage<K> source, KindStorage<K> target, K key, long amount, TransactionContext root) {
        if (amount <= 0) {
            return 0;
        }
        boolean sourceRollsBack = source.transactional();
        boolean targetRollsBack = target.transactional();
        if (sourceRollsBack && targetRollsBack) {
            long moved = bothTransactional(source, target, key, amount, root);
            if (moved < 0) {
                moved = bothTransactional(source, target, key, -moved, root);
            }
            return Math.max(0, moved);
        }
        if (sourceRollsBack) {
            return intoForeign(source, target, key, amount, root);
        }
        if (targetRollsBack) {
            return fromForeign(source, target, key, amount, root);
        }
        return 0;
    }

    /** Returns what moved, or minus what the source gave when it gave some but less than the target took. */
    private static <K> long bothTransactional(KindStorage<K> source, KindStorage<K> target, K key, long amount,
            TransactionContext root) {
        try (Transaction part = Transaction.open(root)) {
            long put = target.insert(key, amount, part);
            if (put <= 0) {
                return 0;
            }
            long got = source.extract(key, put, part);
            if (got == put) {
                part.commit();
                return put;
            }
            return got > 0 && got < put ? -got : 0;
        }
    }

    /** The target cannot roll back: it is carried out last, after the source gave what it will take. */
    private static <K> long intoForeign(KindStorage<K> source, KindStorage<K> target, K key, long amount,
            TransactionContext root) {
        long offer = Math.min(amount, target.simulateInsert(key, amount, root));
        if (offer <= 0) {
            return 0;
        }
        long done;
        try (Transaction part = Transaction.open(root)) {
            long taken = source.extract(key, offer, part);
            if (taken <= 0) {
                return 0;
            }
            done = Math.max(0, Math.min(taken, target.insert(key, taken, part)));
            if (done == taken) {
                part.commit();
                return done;
            }
        }
        // The foreign side took less than it simulated and the nested part rolled back: take exactly that again.
        if (done > 0) {
            long again = source.extract(key, done, root);
            if (again != done) {
                LOGGER.error("Transfer port: a handler without transactions took {} of {}, the port's side gave {}",
                        done, key, again);
            }
        }
        return done;
    }

    /** The source cannot roll back: it is carried out last, after the target took what it will give. */
    private static <K> long fromForeign(KindStorage<K> source, KindStorage<K> target, K key, long amount,
            TransactionContext root) {
        long offer = Math.min(amount, source.simulateExtract(key, amount, root));
        if (offer <= 0) {
            return 0;
        }
        long done;
        try (Transaction part = Transaction.open(root)) {
            long put = target.insert(key, offer, part);
            if (put <= 0) {
                return 0;
            }
            done = Math.max(0, Math.min(put, source.extract(key, put, part)));
            if (done == put) {
                part.commit();
                return done;
            }
        }
        // The foreign side gave less than it simulated and the nested part rolled back: put exactly that in again.
        if (done > 0) {
            long again = target.insert(key, done, root);
            if (again != done) {
                LOGGER.error("Transfer port: a handler without transactions gave {} of {}, the port's side took {}",
                        done, key, again);
            }
        }
        return done;
    }
}
