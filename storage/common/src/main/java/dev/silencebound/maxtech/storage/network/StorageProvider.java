package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
// PLATFORM-LEAK: every network transfer runs in NeoForge's transfer transaction (PRD 7.2, cache is a view).
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Something that holds items and fluids for a storage network: a cell in a drive, later an external inventory behind a
 * port or a bridge to another mod's storage (PRD 7.2, Storage data model). The network keeps an index of what every
 * provider holds and asks providers only through this interface.
 *
 * <p>
 * The provider is the source of truth. The network's totals change only through the {@link Listener} it installs, and
 * the provider reports a change only once the root transaction that made it has committed, with the amount that
 * actually moved; a rolled-back insert or extract reports nothing. So a stale index can make a display briefly wrong
 * but can never duplicate or lose anything.
 *
 * <p>
 * Server thread only. Implementations must honour transactions (NeoForge's {@code SnapshotJournal} is the usual base).
 */
public interface StorageProvider {
    /**
     * Inserts up to {@code amount} of {@code key} as part of {@code transaction} and returns how much went in, from 0
     * to {@code amount}. Nothing is final until the root transaction commits. {@code amount} must not be negative; 0
     * returns 0.
     */
    long insert(StackKey key, long amount, TransactionContext transaction);

    /**
     * Extracts up to {@code amount} of {@code key} as part of {@code transaction} and returns how much came out, from 0
     * to {@code amount}. {@code amount} must not be negative; 0 returns 0.
     */
    long extract(StackKey key, long amount, TransactionContext transaction);

    /** How much of {@code key} the provider holds now, committed changes only; 0 for a key it does not hold. */
    long amount(StackKey key);

    /**
     * Reports every key the provider holds with its amount, each once and above 0, in no particular order. Called when
     * the provider joins a network, never per tick.
     */
    void forEachStored(Visitor visitor);

    /**
     * Installs the network's listener, or removes it with {@code null}. A provider holds at most one; the next call
     * replaces it. The network sets it when the provider joins and clears it when it leaves.
     */
    void setListener(@Nullable Listener listener);

    /**
     * Where inserts try first: higher first, ties in no fixed order. 0 by default. Must not change while the provider
     * is in a network; a provider whose priority changes leaves and rejoins.
     */
    default int priority() {
        return 0;
    }

    /** Receives a provider's contents; see {@link #forEachStored}. */
    @FunctionalInterface
    interface Visitor {
        /** One key with its amount, above 0. */
        void visit(StackKey key, long amount);
    }

    /** Hears about committed changes; see the class comment. */
    @FunctionalInterface
    interface Listener {
        /**
         * The amount of {@code key} changed by {@code delta} (never 0): positive for what came in, negative for what
         * went out. Called after the root transaction committed, on the server thread, once per key and transaction or
         * once per change; the network adds every call.
         */
        void changed(StackKey key, long delta);

        /**
         * {@code amount} (positive) of {@code key} was taken in and destroyed by void overflow. Called after the root
         * transaction committed, on the server thread, once per insert that destroyed something; the network keeps it
         * in its recovery bin (M7C item 20). Does nothing by default.
         */
        default void voided(StackKey key, long amount) {
        }
    }
}
