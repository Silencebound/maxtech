package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import java.util.Arrays;

/**
 * What one multi-key operation ({@link StorageTransfer#beginOperation}) moved, key by key, with the last index it used:
 * the device reports or credits these only after the operation's root transaction commits. Reused by its device, so an
 * operation allocates nothing once the arrays have grown; keys stay referenced until overwritten (interned, so cheap).
 * Server thread only.
 */
public final class OperationMoves {
    private StackKey[] keys = new StackKey[4];
    private long[] amounts = new long[4];
    private int size;
    private int lastIndex = -1;

    /** Forgets every move. */
    public void clear() {
        size = 0;
        lastIndex = -1;
    }

    /** Records {@code amount} of {@code key} moved through index {@code index}. */
    public void add(StackKey key, long amount, int index) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            amounts = Arrays.copyOf(amounts, size * 2);
        }
        keys[size] = key;
        amounts[size] = amount;
        size++;
        lastIndex = index;
    }

    /** How many moves are recorded. */
    public int size() {
        return size;
    }

    /** The key of move {@code i}. */
    public StackKey key(int i) {
        return keys[i];
    }

    /** The amount of move {@code i}. */
    public long amount(int i) {
        return amounts[i];
    }

    /** The index the last move used, or -1 without one. */
    public int lastIndex() {
        return lastIndex;
    }

    /** The total recorded for {@code key}; a linear scan, as an operation holds a handful of moves. */
    public long amountOf(StackKey key) {
        long total = 0;
        for (int i = 0; i < size; i++) {
            if (keys[i].equals(key)) {
                total += amounts[i];
            }
        }
        return total;
    }

    /** Tells {@code network}'s observer about every recorded move; call after the root transaction committed. */
    public void reportTo(NetworkState network) {
        for (int i = 0; i < size; i++) {
            network.transferred(keys[i], amounts[i]);
        }
    }
}
