package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.network.ByteCapacity;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A test {@link StorageProvider}: amounts per key up to a total capacity, transaction-aware through NeoForge's journal,
 * reporting committed changes to the network's listener as the contract asks. {@link #setDirect} changes it behind the
 * network's back, the way an external inventory can.
 */
final class MemoryProvider extends SnapshotJournal<Object2LongOpenHashMap<StackKey>>
        implements
            StorageProvider,
            ByteCapacity {
    private final Object2LongOpenHashMap<StackKey> amounts = new Object2LongOpenHashMap<>();
    private final long capacity;
    private boolean countBytes;
    private final int priority;
    private @Nullable Listener listener;

    MemoryProvider(long capacity, int priority) {
        this.capacity = capacity;
        this.priority = priority;
    }

    private long total() {
        long total = 0;
        for (long amount : amounts.values()) {
            total += amount;
        }
        return total;
    }

    @Override
    public long insert(StackKey key, long amount, TransactionContext transaction) {
        checkAmount(amount);
        long moved = Math.min(amount, capacity - total());
        if (moved <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        amounts.addTo(key, moved);
        return moved;
    }

    @Override
    public long extract(StackKey key, long amount, TransactionContext transaction) {
        checkAmount(amount);
        long moved = Math.min(amount, amounts.getLong(key));
        if (moved <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        if (amounts.addTo(key, -moved) == moved) {
            amounts.removeLong(key);
        }
        return moved;
    }

    private static void checkAmount(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative amount " + amount);
        }
    }

    @Override
    public long amount(StackKey key) {
        return amounts.getLong(key);
    }

    /**
     * From now on counts one byte per unit stored against its capacity, so a storage-full alarm has bytes to read. Off
     * by default: other tests sum the network's bytes over real cells only.
     */
    void countBytes() {
        countBytes = true;
    }

    @Override
    public long usedBytes() {
        return countBytes ? total() : 0;
    }

    @Override
    public long totalBytes() {
        return countBytes ? capacity : 0;
    }

    @Override
    public void forEachStored(Visitor visitor) {
        for (Object2LongMap.Entry<StackKey> entry : amounts.object2LongEntrySet()) {
            visitor.visit(entry.getKey(), entry.getLongValue());
        }
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @Override
    public int priority() {
        return priority;
    }

    /** Sets an amount without a transaction and tells the network, like an inventory changed by hand. */
    void setDirect(StackKey key, long amount) {
        long before = amounts.getLong(key);
        if (amount <= 0) {
            amounts.removeLong(key);
        } else {
            amounts.put(key, amount);
        }
        Listener current = listener;
        if (current != null && amount != before) {
            current.changed(key, Math.max(0, amount) - before);
        }
    }

    @Override
    protected Object2LongOpenHashMap<StackKey> createSnapshot() {
        return new Object2LongOpenHashMap<>(amounts);
    }

    @Override
    protected void revertToSnapshot(Object2LongOpenHashMap<StackKey> snapshot) {
        amounts.clear();
        amounts.putAll(snapshot);
    }

    @Override
    protected void onRootCommit(Object2LongOpenHashMap<StackKey> original) {
        Listener current = listener;
        if (current == null) {
            return;
        }
        ObjectOpenHashSet<StackKey> keys = new ObjectOpenHashSet<>(original.keySet());
        keys.addAll(amounts.keySet());
        for (StackKey key : keys) {
            long delta = amounts.getLong(key) - original.getLong(key);
            if (delta != 0) {
                current.changed(key, delta);
            }
        }
    }
}
