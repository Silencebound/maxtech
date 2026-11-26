package dev.silencebound.maxtech.storage.cell;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.KeyCounts;
import dev.silencebound.maxtech.lib.resource.KeyRegistry;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.ByteCapacity;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongMaps;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
// PLATFORM-LEAK: cell changes are journaled in NeoForge's transfer transactions (PRD 7.2, cache is a view).
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A cell mounted in a drive slot: the {@link StorageProvider} its network reads and writes, over the cell's counts in
 * {@link CellStorage}. Inserts are limited by the cell's type, its bytes ({@link CellBytes}), its casing (a bulk cell
 * holds one kind) and its add-ons ({@link #insert}); the byte numbers and parts are read when the cell is mounted and
 * kept until it leaves the drive, so its accounting stays consistent.
 *
 * <p>
 * Changes apply to the counts at once and are journaled: an undo log of (key id, delta) entries, where a snapshot is
 * only a position in the log, so a transaction costs no copy of the cell. A rollback undoes the log back to its
 * position; when the root transaction commits, the log is summed per key and reported to the network's listener with
 * the committed deltas, the save data is marked dirty and the drive hears about it (fill band). Server thread only.
 */
public final class CellInventory extends SnapshotJournal<CellInventory.Mark> implements StorageProvider, ByteCapacity {
    private final CellStorage storage;
    private final CellStorage.StoredCell cell;
    private final KeyCounts<StackKey> counts;
    private final KeyRegistry<StackKey> keys;
    private final CellType type;
    private int priority;
    private final Runnable onCommit;
    private final long totalBytes;
    private final long kindBytes;
    private final long componentKindBytes;
    private final long itemUnits;
    private final long fluidUnits;
    private final boolean bulk;
    private final boolean readOnly;
    private final boolean partition;
    private final boolean voidOverflow;
    private final CellFilter.Matcher filter;
    private final int keptKinds;
    private long usedBytes;
    private boolean closed;
    private boolean draining;
    private boolean extractsHeld;
    private @Nullable Listener listener;
    private final IntArrayList logIds = new IntArrayList();
    private final LongArrayList logDeltas = new LongArrayList();
    private final ObjectArrayList<Mark> marks = new ObjectArrayList<>();
    /** What void overflow destroyed in the open transaction (key, amount); told to the listener on commit. */
    private final ObjectArrayList<StackKey> voidKeys = new ObjectArrayList<>();
    private final LongArrayList voidAmounts = new LongArrayList();
    /** Per key id, the deltas of the transaction being committed; empty between commits. */
    private final Int2LongOpenHashMap committed = new Int2LongOpenHashMap();

    /**
     * A cell of {@code totalBytes} capacity; {@code bulk} holds one kind. The add-ons and filter come from
     * {@code data}; its memory and casing are not read.
     */
    CellInventory(CellStorage storage, CellStorage.StoredCell cell, CellType type, long totalBytes, boolean bulk,
            CellData data, Runnable onCommit) {
        this.storage = storage;
        this.cell = cell;
        this.counts = cell.counts;
        this.keys = storage.keys();
        this.type = type;
        this.onCommit = onCommit;
        this.totalBytes = totalBytes;
        this.bulk = bulk;
        this.readOnly = data.has(CellAddon.READ_ONLY);
        this.partition = data.has(CellAddon.PARTITION);
        this.voidOverflow = data.has(CellAddon.VOID_OVERFLOW);
        this.filter = data.filter().matcher();
        this.kindBytes = StorageServerConfig.CELL_KIND_BYTES.getAsLong();
        this.componentKindBytes = StorageServerConfig.CELL_COMPONENT_KIND_BYTES.getAsLong();
        this.itemUnits = type.unitsPerByte(StackKey.Kind.ITEM);
        this.fluidUnits = type.unitsPerByte(StackKey.Kind.FLUID);
        // Kept entries (keys of a removed mod) keep taking their bytes, so the cell cannot be overfilled meanwhile.
        KeptKeys kept = cell.kept;
        this.keptKinds = kept == null ? 0 : kept.size();
        long used = kept == null ? 0 : kept.bytes(type, kindBytes, componentKindBytes);
        ObjectIterator<Int2LongMap.Entry> it = Int2LongMaps.fastIterator(counts.view());
        while (it.hasNext()) {
            Int2LongMap.Entry entry = it.next();
            StackKey key = keys.key(entry.getIntKey());
            if (key != null) {
                // Saturates: kept entries of a kind the type refuses count as Long.MAX_VALUE (the cell is full).
                long sum = used + bytes(key, entry.getLongValue());
                used = sum < used ? Long.MAX_VALUE : sum;
            }
        }
        this.usedBytes = used;
    }

    /** A position in the undo log; pooled, since one is taken per transaction depth that changes the cell. */
    static final class Mark {
        int position;
        int voidPosition;
    }

    /**
     * Inserts what fits by type, bytes and add-ons: nothing with the read-only lock or while draining, with a partition
     * only what the filter accepts (a blank filter takes everything, a deny list everything its entries do not match),
     * in a bulk cell only the kind it holds. With void overflow, what does not fit of a key the filter accepts (never
     * with a blank filter, never a denied key) is taken and destroyed: it is reported as moved but never stored, so a
     * rollback has nothing to undo for it and the network hears only of what was stored. What was destroyed is
     * journaled too and, once the root transaction commits, told to the listener's {@link Listener#voided} (the
     * network's recovery bin, M7C item 20).
     */
    @Override
    public long insert(StackKey key, long amount, TransactionContext transaction) {
        checkAmount(amount);
        if (amount == 0 || closed || draining || readOnly || !type.accepts(key)
                || (partition && !filter.accepts(key))) {
            return 0;
        }
        int id = keys.id(key);
        long count = id < 0 ? 0 : counts.count(id);
        long room = bulk && count == 0 && kinds() > 0
                ? 0
                : CellBytes.room(count, overhead(key), usedBytes, totalBytes, unitsPerByte(key));
        long moved = Math.min(amount, room);
        long voided = voidOverflow && filter.voids(key) ? amount - Math.max(0, moved) : 0;
        if (moved > 0 || voided > 0) {
            updateSnapshots(transaction);
        }
        if (moved > 0) {
            if (id < 0) {
                id = keys.intern(key);
            }
            apply(id, key, moved);
        }
        if (voided > 0) {
            voidKeys.add(key);
            voidAmounts.add(voided);
        }
        return Math.max(0, moved) + voided;
    }

    @Override
    public long extract(StackKey key, long amount, TransactionContext transaction) {
        checkAmount(amount);
        if (amount == 0 || closed || extractsHeld) {
            return 0;
        }
        int id = keys.id(key);
        long moved = id < 0 ? 0 : Math.min(amount, counts.count(id));
        if (moved <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        apply(id, key, -moved);
        return moved;
    }

    private void apply(int id, StackKey key, long delta) {
        long before = counts.count(id);
        long after = counts.add(id, delta);
        usedBytes += bytes(key, after) - bytes(key, before);
        logIds.add(id);
        logDeltas.add(delta);
    }

    private static void checkAmount(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative amount " + amount);
        }
    }

    private long bytes(StackKey key, long count) {
        return CellBytes.bytes(count, overhead(key), unitsPerByte(key));
    }

    private long overhead(StackKey key) {
        return key.hasComponents() ? componentKindBytes : kindBytes;
    }

    private long unitsPerByte(StackKey key) {
        return key.kind() == StackKey.Kind.FLUID ? fluidUnits : itemUnits;
    }

    @Override
    public long amount(StackKey key) {
        int id = keys.id(key);
        return id < 0 ? 0 : counts.count(id);
    }

    @Override
    public void forEachStored(Visitor visitor) {
        ObjectIterator<Int2LongMap.Entry> it = Int2LongMaps.fastIterator(counts.view());
        while (it.hasNext()) {
            Int2LongMap.Entry entry = it.next();
            StackKey key = keys.key(entry.getIntKey());
            if (key != null) {
                visitor.visit(key, entry.getLongValue());
            }
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

    /**
     * Sets the priority the network sees; the drive's own, 0 for a cell outside a drive. The holder re-indexes its
     * providers afterwards ({@link StorageProvider#priority()}).
     */
    public void setPriority(int priority) {
        this.priority = priority;
    }

    /**
     * Whether the cell is being emptied into its network (M7C item 12, the drive's cell transfer): while on, it takes
     * nothing in, so the network's inserts, the transfer's own included, go to other providers. Server thread.
     */
    public void setDraining(boolean draining) {
        this.draining = draining;
    }

    /** Whether {@link #setDraining} is on. */
    public boolean draining() {
        return draining;
    }

    /**
     * While on, the cell gives nothing out. A cell being filled from its network turns it on only around its own
     * network extract, so that extract takes from the other providers and never from the cell it fills. Server thread.
     */
    public void holdExtracts(boolean held) {
        this.extractsHeld = held;
    }

    /** The counts by key id (ids of {@link #key(int)}); a read-only live view, not to be kept. */
    public Int2LongMap contents() {
        return counts.view();
    }

    /** The key with id {@code id} in the cells' shared key registry; {@code null} for an id not in use. */
    public @Nullable StackKey key(int id) {
        return keys.key(id);
    }

    /** The id of {@code key} in the cells' shared key registry; -1 for a key never interned. */
    public int keyId(StackKey key) {
        return keys.id(key);
    }

    /** How much of the key with id {@code id} the cell holds. */
    public long amount(int id) {
        return counts.count(id);
    }

    /** The cell's id in {@link CellStorage}. */
    public long id() {
        return cell.id;
    }

    /** Bytes the contents use now. */
    @Override
    public long usedBytes() {
        return usedBytes;
    }

    /** The cell's capacity in bytes, as read when it was mounted. */
    @Override
    public long totalBytes() {
        return totalBytes;
    }

    /** Kinds held now, kept entries ({@link #keptKinds()}) included. */
    public int kinds() {
        return counts.kinds() + keptKinds;
    }

    /** Kinds kept because their keys no longer load (a removed mod); in no view, never extracted. */
    public int keptKinds() {
        return keptKinds;
    }

    /** The fill band a drive shows ({@link CellBytes#band}). */
    public int band() {
        return CellBytes.band(usedBytes, totalBytes);
    }

    CellStorage.StoredCell cell() {
        return cell;
    }

    /** Unmounted: refuses everything and reports nothing more. */
    void close() {
        closed = true;
        listener = null;
    }

    @Override
    protected Mark createSnapshot() {
        Mark mark = marks.isEmpty() ? new Mark() : marks.pop();
        mark.position = logIds.size();
        mark.voidPosition = voidKeys.size();
        return mark;
    }

    @Override
    protected void revertToSnapshot(Mark snapshot) {
        for (int i = logIds.size() - 1; i >= snapshot.position; i--) {
            int id = logIds.getInt(i);
            StackKey key = keys.key(id);
            long before = counts.count(id);
            long after = counts.add(id, -logDeltas.getLong(i));
            if (key != null) {
                usedBytes += bytes(key, after) - bytes(key, before);
            }
        }
        logIds.size(snapshot.position);
        logDeltas.size(snapshot.position);
        voidKeys.size(snapshot.voidPosition);
        voidAmounts.size(snapshot.voidPosition);
    }

    @Override
    protected void releaseSnapshot(Mark snapshot) {
        marks.push(snapshot);
    }

    @Override
    protected void onRootCommit(Mark originalState) {
        int size = logIds.size();
        for (int i = 0; i < size; i++) {
            committed.addTo(logIds.getInt(i), logDeltas.getLong(i));
        }
        Listener current = listener;
        for (int i = 0; i < size; i++) {
            int id = logIds.getInt(i);
            // remove returns 0 for an id already reported, so each key is reported once with its sum.
            long delta = committed.remove(id);
            StackKey key = keys.key(id);
            if (delta != 0 && current != null && key != null) {
                current.changed(key, delta);
            }
        }
        logIds.clear();
        logDeltas.clear();
        int voids = voidKeys.size();
        for (int i = 0; i < voids; i++) {
            if (current != null) {
                current.voided(voidKeys.get(i), voidAmounts.getLong(i));
            }
        }
        voidKeys.clear();
        voidAmounts.clear();
        // A transaction that only destroyed overflow changed nothing stored.
        if (size > 0) {
            storage.setDirty();
            onCommit.run();
        }
    }
}
