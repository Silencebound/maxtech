package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
// PLATFORM-LEAK: every network transfer runs in NeoForge's transfer transaction (PRD 7.2, cache is a view).
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * A device's way into its network while one of its {@link StorageTask}s runs. Each {@link #insert} or {@link #extract}
 * is one operation (PRD 7.2, Performance: batch transfers): it moves at most one batch of one key ({@link #batchLimit})
 * and pays the network's energy for the operation inside {@code transaction}, so a rollback refunds the energy. With
 * too little energy for the whole batch, the operation moves what the energy pays for. The device holds its channels
 * already (M7N), so an operation does no route work; only the device's own rate per cadence limits how often it
 * operates.
 *
 * <p>
 * An operation is refused, moving nothing, when the network is not working (no power for the operation, two
 * controllers, paused by an unloaded chunk) or a surge regulator holds automation back; {@link #retryTicks()} then says
 * when to try again.
 *
 * <p>
 * A task gets one for each {@link StorageTask#run}; outside its tasks a device gets one from
 * {@link DeviceLink#transfer()} for synchronous work. Each device has its own, so operating inside another device's run
 * does not disturb that run's transfer. Valid only during the call it was handed out for; server thread only.
 */
public interface StorageTransfer {
    /** The network the device is in. */
    NetworkState network();

    /** Most of {@code key}'s kind one operation moves: items, or mB of fluid. */
    long batchLimit(StackKey.Kind kind);

    /**
     * Whether an operation could run now: the network is working and has energy for one. Changes nothing. Use it to
     * skip reading a neighbour when the answer would be thrown away.
     */
    boolean canOperate();

    /**
     * Stores up to {@code amount} of {@code key}, capped at one batch, in the network's providers as part of
     * {@code transaction}, and returns how much went in. One operation when anything moved; a refused or empty
     * operation costs nothing. {@code amount} must not be negative.
     */
    long insert(StackKey key, long amount, TransactionContext transaction);

    /**
     * Takes up to {@code amount} of {@code key}, capped at one batch, out of the network's providers as part of
     * {@code transaction}, and returns how much came out. One operation when anything moved; a refused or empty
     * operation costs nothing. {@code amount} must not be negative.
     */
    long extract(StackKey key, long amount, TransactionContext transaction);

    /**
     * Opens one operation over several keys of {@code kind} (M7E: a port taking mixed slots pays one operation per
     * batch, not per slot). Returns the most the whole operation may move: one batch, less when the energy buffer pays
     * for less; 0 when an operation is refused now, and then no operation is open ({@link #retryTicks()} says when to
     * try again). While it is open, {@link #insert} and {@link #extract} of {@code kind} move what they are asked
     * without a batch cap or a cost of their own; the caller keeps their total within the returned amount. Every open
     * operation must be closed with {@link #finishOperation}, in the same transaction its moves ran in.
     */
    long beginOperation(StackKey.Kind kind);

    /**
     * Closes the operation {@link #beginOperation} opened and pays for {@code moved}, the total its moves kept, as one
     * operation inside {@code transaction}; the network counts the operation only once the root transaction commits.
     * Returns whether it paid; false when {@code moved} is 0 or the energy no longer covers it, and then the caller
     * must roll {@code transaction} back. Without an open operation it returns false and changes nothing. Call it in a
     * {@code finally}, so an exception still closes the operation; the root then rolls back and nothing is counted.
     */
    boolean finishOperation(StackKey.Kind kind, long moved, TransactionContext transaction);

    /**
     * Ticks to wait before an operation can pass again: 0 when {@link #canOperate()}, 10 when the network is not
     * working or the last operation of this run lacked energy (a brown-out), or a long wait while a surge regulator
     * holds automation back on low energy; the network wakes the device's tasks as soon as its energy is back. At least
     * 0.
     */
    int retryTicks();
}
