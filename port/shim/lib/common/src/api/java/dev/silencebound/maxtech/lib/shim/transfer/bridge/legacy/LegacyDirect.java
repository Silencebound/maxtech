package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.resource.Resource;
import org.jspecify.annotations.Nullable;

/**
 * Moves on a 1.21.1 handler that happen at once, without a transaction and without the {@link LegacyLedger}: the
 * "foreign side" of Lib's foreign-last moves ({@code KindMoves}, {@link LegacyForeignLast}). The caller simulates,
 * moves its own side in a nested transaction, carries out this side last and keeps only what it really moved; the
 * root transaction must then commit at once.
 *
 * <p>Every method refuses (returns 0) while the handler's ledger has pending changes in the current transaction, so a
 * direct move never runs against a state the ledger is about to change. Results are clamped to {@code 0..amount}; a
 * handler that reports less than {@code amount} but more than it really moved is not caught. Server or client thread
 * only, like the wrapped handler.
 *
 * @param <R> the resource type
 */
public interface LegacyDirect<R> {
    /** {@return {@code handler} as direct moves when it is a 1.21.1 wrapper that wants them, else null} */
    @SuppressWarnings("unchecked") // a ResourceHandler<R> that is a LegacyDirect is one over the same resource type
    static <R extends Resource> @Nullable LegacyDirect<R> of(@Nullable ResourceHandler<R> handler) {
        return handler instanceof LegacyDirect<?> direct && direct.direct() ? (LegacyDirect<R>) direct : null;
    }

    /** {@return whether moves on this handler should go through this interface rather than the ledger} */
    boolean direct();

    /** {@return how much of {@code amount} the handler would take now, changing nothing} */
    int simulateInsertNow(R resource, int amount);

    /** {@return how much of {@code amount} the handler would give now, changing nothing} */
    int simulateExtractNow(R resource, int amount);

    /** Inserts for real, at once; {@return the amount the handler reports it took} */
    int insertNow(R resource, int amount);

    /** Extracts for real, at once; {@return the amount the handler reports it gave} */
    int extractNow(R resource, int amount);
}
