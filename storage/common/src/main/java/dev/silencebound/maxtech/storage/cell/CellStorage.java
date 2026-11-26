package dev.silencebound.maxtech.storage.cell;

import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.ListBuilder;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.KeyCounts;
import dev.silencebound.maxtech.lib.resource.KeyPalette;
import dev.silencebound.maxtech.lib.resource.KeyRegistry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Every storage cell's contents, in the Overworld's save data ({@code data/maxtech_storage/cells.dat}), keyed by the id
 * on the cell item (PRD 7.2, Persistence). Cells keep their contents wherever the item goes: into another drive, a
 * chest, another dimension. Each cell is one Lib {@link KeyCounts} (all cells share one key registry) and is saved as a
 * {@link KeyPalette}.
 *
 * <p>
 * Saving rewrites only the cells that changed: each cell keeps the tag it was last saved or loaded as (and its whole
 * list entry) and reuses it while its counts are clean, so a save costs one palette encoding per changed cell, and each
 * key is encoded once per save however many cells hold it. The vanilla save encodes on the server thread (a snapshot)
 * and writes the result off it. The palette is read key by key: a key that no longer loads (an item or fluid of a
 * removed mod) is kept as saved with its count ({@link KeptKeys}) and written back unchanged, while everything else in
 * the cell stays usable. Only contents whose palette itself is broken are kept whole and keep the cell out of use, so
 * nothing is deleted.
 *
 * <p>
 * Server thread only.
 */
public final class CellStorage extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final Codec<KeyPalette<StackKey>> PALETTE = KeyPalette.codec(StackKey.CODEC);
    /** The palette with its keys left undecoded, so each key can fail on its own. */
    private static final Codec<KeyPalette<Dynamic<?>>> RAW_PALETTE = KeyPalette.codec(Codec.PASSTHROUGH);
    /** {@link KeyPalette#codec}'s field names, for {@link #encodeContents}. */
    private static final String PALETTE_KEYS = "keys";
    private static final String PALETTE_INDICES = "indices";
    private static final String PALETTE_COUNTS = "counts";
    private static final String NEXT_ID = "next_id";
    private static final String CELLS = "cells";
    private static final String ID = "id";
    private static final String CONTENTS = "contents";
    private static final String HOME_DIMENSION = "home_dimension";
    private static final String HOME_POS = "home_pos";

    public static final Codec<CellStorage> CODEC = new Codec<>() {
        @Override
        public <T> DataResult<T> encode(CellStorage storage, DynamicOps<T> ops, T prefix) {
            return storage.encode(ops, prefix);
        }

        @Override
        public <T> DataResult<Pair<CellStorage, T>> decode(DynamicOps<T> ops, T input) {
            return CellStorage.decode(ops, input).map(storage -> Pair.of(storage, input));
        }
    };
    public static final SavedDataType<CellStorage> TYPE = StorageSaveFormats.savedData(MaxtechStorage.id("cells"),
            CellStorage::new, CODEC);

    private final KeyRegistry<StackKey> keys = KeyRegistry.stackKeys();
    private final Long2ObjectOpenHashMap<StoredCell> cells = new Long2ObjectOpenHashMap<>();
    private long nextId = 1;
    private int lastEncoded;

    /** An empty storage. The game's is the Overworld's ({@link #get}); others are for tests. */
    public CellStorage() {
    }

    /** The server's cell storage, from the Overworld's save data. */
    public static CellStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** One cell's contents and what it was saved as. */
    static final class StoredCell {
        final long id;
        final KeyCounts<StackKey> counts;
        /** The contents' tag as last saved or loaded; valid while {@link #counts} is clean. */
        @Nullable
        Tag saved;
        /** Contents that failed to load, kept as they were; the cell cannot be used while set. */
        @Nullable
        Tag unreadable;
        /**
         * The whole cell list entry as last saved; valid while {@link #saved} (or {@link #unreadable}) is reused and
         * the home is unchanged, so an unchanged cell costs no encoding at all.
         */
        @Nullable
        Tag savedEntry;
        /** Palette entries whose keys failed to load, kept as saved; the rest of the cell stays usable. */
        @Nullable
        KeptKeys kept;
        /** The inventory of the drive slot the cell sits in, or {@code null} while it is in none. */
        @Nullable
        CellInventory mount;
        /**
         * The dimension and position of the controller of the network the cell was last in (M7C item 20), where its
         * contents go when the cell item is destroyed; {@code null} while unknown.
         */
        @Nullable
        String homeDimension;
        long homePos;

        StoredCell(long id, KeyCounts<StackKey> counts) {
            this.id = id;
            this.counts = counts;
        }
    }

    /** A fresh cell id; cells get one when they first enter a drive. */
    public long newId() {
        long id = nextId++;
        setDirty();
        return id;
    }

    /**
     * Mounts the cell {@code id} for a drive slot: its contents become reachable through the returned inventory until
     * {@link #unmount}. An id this storage does not know (a cell from another world) starts empty. Returns {@code null}
     * when the cell is already mounted elsewhere (a copied item) or its contents could not be read.
     */
    public @Nullable CellInventory mount(long id, CellType type, CellCasing casing, CellData data, Runnable onCommit) {
        return mount(id, type, data.capacity(casing), casing.bulk(), data, onCommit);
    }

    /**
     * Mounts the cell {@code id} as {@link #mount(long, CellType, CellCasing, CellData, Runnable)} does, for storage
     * that is no cell item: a block with a cell of its own (B09 item 27, the storage block), holding any number of
     * kinds in {@code totalBytes}, with no add-ons and no filter.
     */
    public @Nullable CellInventory mount(long id, CellType type, long totalBytes, Runnable onCommit) {
        // The inventory reads only the add-ons and filter of the data, so the type id in it is a placeholder.
        return mount(id, type, totalBytes, false, CellData.of(CellTypes.REGISTRY_KEY.identifier(), List.of()),
                onCommit);
    }

    private @Nullable CellInventory mount(long id, CellType type, long totalBytes, boolean bulk, CellData data,
            Runnable onCommit) {
        StoredCell cell = cells.get(id);
        if (cell == null) {
            cell = new StoredCell(id, new KeyCounts<>(keys));
            cells.put(id, cell);
            if (id >= nextId) {
                nextId = id + 1;
            }
            setDirty();
        }
        if (cell.mount != null || cell.unreadable != null) {
            return null;
        }
        CellInventory inventory = new CellInventory(this, cell, type, totalBytes, bulk, data, onCommit);
        cell.mount = inventory;
        return inventory;
    }

    /**
     * Takes a cell out of use: its inventory refuses everything from now on and stops reporting. A cell left empty is
     * forgotten; its item keeps the id and starts empty again when it next enters a drive.
     */
    public void unmount(CellInventory inventory) {
        StoredCell cell = inventory.cell();
        inventory.close();
        // Identity: the cell may have been mounted again by another inventory in the meantime.
        if (cell.mount == inventory) {
            cell.mount = null;
            if (cell.counts.kinds() == 0 && cell.unreadable == null && cell.kept == null
                    && cells.get(cell.id) == cell) {
                cells.remove(cell.id);
                setDirty();
            }
        }
    }

    /**
     * Remembers that the cell {@code id} was last in the network of the controller at {@code pos} in {@code dimension},
     * for when its item is destroyed (M7C item 20). Ignored for a cell holding nothing.
     */
    public void setHome(long id, String dimension, long pos) {
        StoredCell cell = cells.get(id);
        if (cell == null || (dimension.equals(cell.homeDimension) && pos == cell.homePos)) {
            return;
        }
        cell.homeDimension = dimension;
        cell.homePos = pos;
        cell.savedEntry = null;
        setDirty();
    }

    /** The dimension of the cell's home controller ({@link #setHome}); empty when unknown. */
    public String homeDimension(long id) {
        StoredCell cell = cells.get(id);
        String dimension = cell == null ? null : cell.homeDimension;
        return dimension == null ? "" : dimension;
    }

    /** The position of the cell's home controller; meaningful only while {@link #homeDimension} is not empty. */
    public long homePos(long id) {
        StoredCell cell = cells.get(id);
        return cell == null ? 0 : cell.homePos;
    }

    /**
     * Empties the cell {@code id}, whose item was destroyed, into {@code visitor} (each key with its amount) and
     * forgets it. Does nothing (false) for a cell that is unknown, in a drive (a copied item's twin) or unreadable.
     * Kept entries ({@link KeptKeys}) cannot be handed out, so a cell with any stays stored with only those.
     */
    public boolean takeAll(long id, StorageProvider.Visitor visitor) {
        StoredCell cell = cells.get(id);
        if (cell == null || cell.mount != null || cell.unreadable != null) {
            return false;
        }
        setDirty();
        ObjectIterator<Int2LongMap.Entry> it = Int2LongMaps.fastIterator(cell.counts.view());
        while (it.hasNext()) {
            Int2LongMap.Entry entry = it.next();
            StackKey key = keys.key(entry.getIntKey());
            if (key != null && entry.getLongValue() > 0) {
                visitor.visit(key, entry.getLongValue());
            }
        }
        if (cell.kept == null) {
            cells.remove(id);
        } else {
            cell.counts.clear();
        }
        return true;
    }

    /**
     * Test hook, not for game code: replaces what the cell {@code id} holds with {@code contents} in the saved form (a
     * key palette, as the save data writes it), read exactly as a load reads it, so keys that no longer load are kept
     * ({@link KeptKeys}). GameTests use it to give a live cell an entry of a removed mod. Does nothing (false) while
     * the cell is mounted.
     */
    public boolean restoreForTests(long id, Tag contents) {
        StoredCell current = cells.get(id);
        if (id == CellData.NO_ID || (current != null && current.mount != null)) {
            return false;
        }
        CompoundTag entry = new CompoundTag();
        entry.putLong(ID, id);
        entry.put(CONTENTS, contents.copy());
        load(NbtOps.INSTANCE, entry);
        StoredCell loaded = cells.get(id);
        if (loaded != null && current != null) {
            loaded.homeDimension = current.homeDimension;
            loaded.homePos = current.homePos;
        }
        // Re-encoded on the next save, so the counts are written from what was loaded, not from the restored tag.
        if (loaded != null) {
            loaded.saved = null;
        }
        setDirty();
        return loaded != null;
    }

    /** The registry the cells' keys are interned in. */
    KeyRegistry<StackKey> keys() {
        return keys;
    }

    /** How much of {@code key} the cell {@code id} holds; 0 for an unknown cell. */
    public long amount(long id, StackKey key) {
        StoredCell cell = cells.get(id);
        int keyId = keys.id(key);
        return cell == null || keyId < 0 ? 0 : cell.counts.count(keyId);
    }

    /** Kinds the cell {@code id} holds, kept entries ({@link #keptKinds}) included; 0 for an unknown cell. */
    public int kinds(long id) {
        StoredCell cell = cells.get(id);
        return cell == null ? 0 : cell.counts.kinds() + keptKinds(cell);
    }

    /**
     * Kinds the cell {@code id} keeps because their keys no longer load (an item of a removed mod); they are in no view
     * and come back when the mod does. 0 for an unknown cell.
     */
    public int keptKinds(long id) {
        StoredCell cell = cells.get(id);
        return cell == null ? 0 : keptKinds(cell);
    }

    static int keptKinds(StoredCell cell) {
        KeptKeys kept = cell.kept;
        return kept == null ? 0 : kept.size();
    }

    /**
     * Bytes the contents of the cell {@code id} need in a cell of {@code type} with the byte numbers in config now; 0
     * for an unknown cell. Kept entries count with the bytes they took. {@link Long#MAX_VALUE} when {@code type}
     * refuses a key the cell holds or the contents could not be read, so no capacity is ever enough. Walks the cell's
     * kinds: for the workbench, not for hot paths.
     */
    public long requiredBytes(long id, CellType type) {
        StoredCell cell = cells.get(id);
        if (cell == null) {
            return 0;
        }
        if (cell.unreadable != null) {
            return Long.MAX_VALUE;
        }
        long kindBytes = StorageServerConfig.CELL_KIND_BYTES.getAsLong();
        long componentKindBytes = StorageServerConfig.CELL_COMPONENT_KIND_BYTES.getAsLong();
        KeptKeys kept = cell.kept;
        long total = kept == null ? 0 : kept.bytes(type, kindBytes, componentKindBytes);
        ObjectIterator<Int2LongMap.Entry> it = Int2LongMaps.fastIterator(cell.counts.view());
        while (it.hasNext()) {
            Int2LongMap.Entry entry = it.next();
            StackKey key = keys.key(entry.getIntKey());
            if (key == null || entry.getLongValue() <= 0) {
                continue;
            }
            if (!type.accepts(key)) {
                return Long.MAX_VALUE;
            }
            long bytes = CellBytes.bytes(entry.getLongValue(), key.hasComponents() ? componentKindBytes : kindBytes,
                    type.unitsPerByte(key.kind()));
            long sum = total + bytes;
            total = sum < total ? Long.MAX_VALUE : sum;
        }
        return total;
    }

    /** Whether the cell {@code id} is known (it holds something or sits in a drive). */
    public boolean contains(long id) {
        return cells.containsKey(id);
    }

    /** Whether the cell {@code id} is in a drive now. */
    public boolean isMounted(long id) {
        StoredCell cell = cells.get(id);
        return cell != null && cell.mount != null;
    }

    /** Cells whose contents the last save encoded; the others reused their saved tag. For tests and diagnostics. */
    public int lastEncodedCells() {
        return lastEncoded;
    }

    // ops.empty() is a Tag, so this ops' values are Tags and a saved Tag is a valid T.
    @SuppressWarnings("unchecked")
    private <T> DataResult<T> encode(DynamicOps<T> ops, T prefix) {
        keys.releaseUnused();
        boolean nbt = ops.empty() instanceof Tag;
        ListBuilder<T> list = ops.listBuilder();
        Int2ObjectOpenHashMap<T> encodedKeys = new Int2ObjectOpenHashMap<>();
        int encoded = 0;
        for (StoredCell cell : cells.values()) {
            T contents;
            Tag reusable = cell.unreadable != null ? cell.unreadable : cell.counts.isDirty() ? null : cell.saved;
            Tag entryTag = cell.savedEntry;
            if (nbt && reusable != null && entryTag != null) {
                list.add((T) entryTag);
                continue;
            }
            if (reusable != null) {
                contents = nbt ? (T) reusable : NbtOps.INSTANCE.convertTo(ops, reusable);
            } else {
                DataResult<T> result = encodeContents(ops, cell, encodedKeys);
                if (result.isError()) {
                    return result;
                }
                contents = result.getOrThrow();
                encoded++;
                cell.saved = nbt ? (Tag) contents : null;
                cell.counts.markSaved();
            }
            RecordBuilder<T> entry = ops.mapBuilder().add(ID, ops.createLong(cell.id)).add(CONTENTS, contents);
            String home = cell.homeDimension;
            if (home != null) {
                entry.add(HOME_DIMENSION, ops.createString(home)).add(HOME_POS, ops.createLong(cell.homePos));
            }
            DataResult<T> built = entry.build(ops.empty());
            cell.savedEntry = nbt ? (Tag) built.result().orElse(null) : null;
            list.add(built);
        }
        lastEncoded = encoded;
        return ops.mapBuilder().add(NEXT_ID, ops.createLong(nextId)).add(CELLS, list.build(ops.empty())).build(prefix);
    }

    private static <T> DataResult<CellStorage> decode(DynamicOps<T> ops, T input) {
        return ops.getMap(input).flatMap(map -> {
            CellStorage storage = new CellStorage();
            T next = map.get(NEXT_ID);
            long nextId = next == null ? 1 : ops.getNumberValue(next).result().map(Number::longValue).orElse(1L);
            T list = map.get(CELLS);
            if (list != null) {
                List<T> entries = new ArrayList<>();
                DataResult<?> read = ops.getList(list).map(consumer -> {
                    consumer.accept(entries::add);
                    return entries;
                });
                if (read.isError()) {
                    return DataResult.error(() -> "Cell list unreadable: " + read);
                }
                for (T entry : entries) {
                    storage.load(ops, entry);
                }
            }
            storage.nextId = Math.max(storage.nextId, nextId);
            return DataResult.success(storage);
        });
    }

    /**
     * Test and dev hook, not for game code: loads one saved cell entry (as {@link #CODEC} writes it in its cell list)
     * into this live storage the way a world load does, so its unknown keys become kept entries. A cell of the same id
     * is replaced; it must not be mounted. Server thread only.
     */
    public <T> void loadSavedCellForTests(DynamicOps<T> ops, T entry) {
        load(ops, entry);
    }

    private <T> void load(DynamicOps<T> ops, T entry) {
        MapLike<T> map = ops.getMap(entry).result().orElse(null);
        T idValue = map == null ? null : map.get(ID);
        T contents = map == null ? null : map.get(CONTENTS);
        long id = idValue == null
                ? CellData.NO_ID
                : ops.getNumberValue(idValue).result().map(Number::longValue).orElse(CellData.NO_ID);
        if (id == CellData.NO_ID || contents == null) {
            LOGGER.error("Skipping a stored cell without an id or contents: {}", entry);
            return;
        }
        StoredCell cell = new StoredCell(id, new KeyCounts<>(keys));
        DataResult<KeyPalette<Dynamic<?>>> palette = RAW_PALETTE.parse(ops, contents);
        if (palette.isSuccess()) {
            loadPalette(id, cell, palette.getOrThrow());
            cell.saved = contents instanceof Tag tag ? tag : null;
        } else {
            LOGGER.error("Contents of storage cell {} could not be read and are kept unchanged: {}", id,
                    palette.error().map(DataResult.Error::message).orElse(""));
            cell.unreadable = contents instanceof Tag tag ? tag : ops.convertTo(NbtOps.INSTANCE, contents);
        }
        T home = map == null ? null : map.get(HOME_DIMENSION);
        T homePos = map == null ? null : map.get(HOME_POS);
        if (home != null && homePos != null) {
            cell.homeDimension = ops.getStringValue(home).result().orElse(null);
            cell.homePos = ops.getNumberValue(homePos).result().map(Number::longValue).orElse(0L);
        }
        cells.put(id, cell);
        nextId = Math.max(nextId, id + 1);
    }

    /**
     * The cell's contents in {@link #PALETTE}'s format: its counts as a palette, then its kept entries as they were
     * saved. Built by hand rather than through the codec so each key is encoded once per save ({@code encodedKeys}, by
     * key id) and shared by every cell holding it, instead of once per cell.
     */
    private <T> DataResult<T> encodeContents(DynamicOps<T> ops, StoredCell cell, Int2ObjectOpenHashMap<T> encodedKeys) {
        Int2LongMap held = cell.counts.view();
        KeptKeys kept = cell.kept;
        int size = held.size() + (kept == null ? 0 : kept.size());
        int[] indices = new int[size];
        long[] counts = new long[size];
        ListBuilder<T> list = ops.listBuilder();
        int i = 0;
        // The view's entry iterator allocates an entry each; its key iterator does not.
        IntIterator it = held.keySet().iterator();
        while (it.hasNext()) {
            int id = it.nextInt();
            T encoded = encodedKeys.get(id);
            if (encoded == null) {
                StackKey key = keys.key(id);
                if (key == null) {
                    throw new IllegalStateException("Key id " + id + " held but not interned");
                }
                DataResult<T> result = StackKey.CODEC.encodeStart(ops, key);
                if (result.isError()) {
                    return result;
                }
                encoded = result.getOrThrow();
                encodedKeys.put(id, encoded);
            }
            list.add(encoded);
            indices[i] = i;
            counts[i++] = held.get(id);
        }
        if (kept != null) {
            for (int k = 0; k < kept.size(); k++) {
                // As Codec.PASSTHROUGH writes a kept key's Dynamic.
                list.add(new Dynamic<>(NbtOps.INSTANCE, kept.key(k).copy()).convert(ops).getValue());
                indices[i] = i;
                counts[i++] = kept.count(k);
            }
        }
        return ops.mapBuilder().add(PALETTE_KEYS, list.build(ops.empty()))
                .add(PALETTE_INDICES, ops.createIntList(IntStream.of(indices)))
                .add(PALETTE_COUNTS, ops.createLongList(LongStream.of(counts))).build(ops.empty());
    }

    /**
     * Loads a palette key by key: keys that decode go into the cell's counts, the others into its {@link KeptKeys} with
     * the sum of their entries' counts.
     */
    private static void loadPalette(long id, StoredCell cell, KeyPalette<Dynamic<?>> raw) {
        List<Dynamic<?>> rawKeys = raw.keys();
        int[] slot = new int[rawKeys.size()];
        List<StackKey> known = new ArrayList<>(rawKeys.size());
        List<Tag> keptKeys = new ArrayList<>();
        for (int i = 0; i < rawKeys.size(); i++) {
            Dynamic<?> rawKey = rawKeys.get(i);
            DataResult<StackKey> key = StackKey.CODEC.parse(rawKey);
            if (key.isSuccess()) {
                slot[i] = known.size();
                known.add(key.getOrThrow());
            } else {
                LOGGER.warn("Storage cell {} keeps an entry it cannot read until its mod is back: {}", id,
                        key.error().map(DataResult.Error::message).orElse(""));
                slot[i] = -1 - keptKeys.size();
                keptKeys.add(rawKey.convert(NbtOps.INSTANCE).getValue());
            }
        }
        if (keptKeys.isEmpty()) {
            cell.counts.load(new KeyPalette<>(known, raw.indices(), raw.counts()));
            return;
        }
        long[] keptCounts = new long[keptKeys.size()];
        int knownEntries = 0;
        for (int index : raw.indices()) {
            if (slot[index] >= 0) {
                knownEntries++;
            }
        }
        int[] indices = new int[knownEntries];
        long[] counts = new long[knownEntries];
        int next = 0;
        for (int i = 0; i < raw.entries(); i++) {
            int target = slot[raw.indices()[i]];
            long count = raw.counts()[i];
            if (target >= 0) {
                indices[next] = target;
                counts[next++] = count;
            } else {
                long sum = keptCounts[-1 - target] + count;
                keptCounts[-1 - target] = sum < 0 ? Long.MAX_VALUE : sum;
            }
        }
        cell.counts.load(new KeyPalette<>(known, indices, counts));
        cell.kept = new KeptKeys(keptKeys, keptCounts);
    }
}
