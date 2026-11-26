package dev.silencebound.maxtech.storage.cell;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * A storage cell item's data component ({@code maxtech_storage:cell}): what the cell is built from, the id of its
 * contents and a cache of its stats for the tooltip (PRD 7.2, Persistence: contents live in level save data keyed by
 * the id, never on the item). The casing is the cell item itself ({@link StorageCellItem#casing()}). Fields added in
 * M6b ({@code addons}, {@code filter}) are optional in the codec, so cells made before them read as having none. Cells
 * saved while cells had their own priority carry a {@code priority} field; the record codec skips fields it does not
 * name, so they load without it (priority is the drive's now, owner decision 2026-10-07); their priority add-on, since
 * deleted, is dropped from {@code addons}.
 *
 * @param id
 *            the cell's id in {@link CellStorage}; {@link #NO_ID} until the cell first enters a drive
 * @param type
 *            the controller chip's {@link CellType} registry id
 * @param memory
 *            the memory modules, whose bytes add up to the cell's capacity
 * @param addons
 *            the add-on items ({@link CellAddonItem}), at most one of each kind
 * @param filter
 *            the keys the partition and void overflow add-ons act on; kept while those add-ons are out
 * @param usedBytes
 *            cached: bytes used when the cell last left a drive or was saved in one
 * @param totalBytes
 *            cached: the capacity then; 0 before the cell was ever in a drive
 * @param kinds
 *            cached: kinds held then, kept ones included
 * @param kept
 *            cached: kinds kept then because their keys no longer load (a removed mod), for the tooltip's note
 */
public record CellData(long id, Identifier type, List<Item> memory, List<Item> addons, CellFilter filter,
        long usedBytes, long totalBytes, int kinds, int kept) {
    /** The id of a cell that never was in a drive. */
    public static final long NO_ID = 0;
    /** Memory modules or add-ons a cell can name; more than any casing holds. */
    private static final int MAX_PARTS = 64;
    /**
     * Add-on item ids; one no longer registered (the priority add-on, deleted after M7N) is dropped, so the cell keeps
     * its id, memory and other add-ons instead of failing to load.
     */
    private static final Codec<List<Item>> ADDONS_CODEC = Identifier.CODEC.sizeLimitedListOf(MAX_PARTS)
            .xmap(CellData::knownItems, CellData::itemIds);
    /**
     * Memory module ids, read the same way: a module whose item is gone is dropped (its bytes with it) instead of the
     * whole cell item failing to load, which would cut the item off from its contents.
     */
    private static final Codec<List<Item>> MEMORY_CODEC = ADDONS_CODEC;

    public static final Codec<CellData> CODEC = RecordCodecBuilder
            .create(i -> i.group(Codec.LONG.optionalFieldOf("id", NO_ID).forGetter(CellData::id),
                    Identifier.CODEC.fieldOf("type").forGetter(CellData::type),
                    MEMORY_CODEC.fieldOf("memory").forGetter(CellData::memory),
                    ADDONS_CODEC.optionalFieldOf("addons", List.of()).forGetter(CellData::addons),
                    CellFilter.CODEC.optionalFieldOf("filter", CellFilter.EMPTY).forGetter(CellData::filter),
                    Codec.LONG.optionalFieldOf("used_bytes", 0L).forGetter(CellData::usedBytes),
                    Codec.LONG.optionalFieldOf("total_bytes", 0L).forGetter(CellData::totalBytes),
                    Codec.INT.optionalFieldOf("kinds", 0).forGetter(CellData::kinds),
                    Codec.INT.optionalFieldOf("kept", 0).forGetter(CellData::kept)).apply(i, CellData::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, CellData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, CellData::id, Identifier.STREAM_CODEC, CellData::type,
            ByteBufCodecs.registry(Registries.ITEM).apply(ByteBufCodecs.list(MAX_PARTS)), CellData::memory,
            ByteBufCodecs.registry(Registries.ITEM).apply(ByteBufCodecs.list(MAX_PARTS)), CellData::addons,
            CellFilter.STREAM_CODEC, CellData::filter, ByteBufCodecs.VAR_LONG, CellData::usedBytes,
            ByteBufCodecs.VAR_LONG, CellData::totalBytes, ByteBufCodecs.VAR_INT, CellData::kinds, ByteBufCodecs.VAR_INT,
            CellData::kept, CellData::new);

    public CellData {
        memory = List.copyOf(memory);
        addons = List.copyOf(addons);
    }

    private static List<Item> knownItems(List<Identifier> ids) {
        List<Item> items = new ArrayList<>(ids.size());
        for (Identifier id : ids) {
            BuiltInRegistries.ITEM.getOptional(id).ifPresent(items::add);
        }
        return items;
    }

    private static List<Identifier> itemIds(List<Item> items) {
        List<Identifier> ids = new ArrayList<>(items.size());
        for (Item item : items) {
            ids.add(BuiltInRegistries.ITEM.getKey(item));
        }
        return ids;
    }

    /** A new, empty cell of {@code type} with {@code memory} and no add-ons. */
    public static CellData of(Identifier type, List<Item> memory) {
        return new CellData(NO_ID, type, memory, List.of(), CellFilter.EMPTY, 0, 0, 0, 0);
    }

    /** Whether the cell has an id yet. */
    public boolean hasId() {
        return id != NO_ID;
    }

    /** This cell with {@code id}. */
    public CellData withId(long id) {
        return new CellData(id, type, memory, addons, filter, usedBytes, totalBytes, kinds, kept);
    }

    /** This cell with the cached stats replaced. */
    public CellData withStats(long usedBytes, long totalBytes, int kinds, int kept) {
        return new CellData(id, type, memory, addons, filter, usedBytes, totalBytes, kinds, kept);
    }

    /** This cell with another type, memory and add-ons; id, filter and stats stay. */
    public CellData withParts(Identifier type, List<Item> memory, List<Item> addons) {
        return new CellData(id, type, memory, addons, filter, usedBytes, totalBytes, kinds, kept);
    }

    /** This cell with another filter. */
    public CellData withFilter(CellFilter filter) {
        return new CellData(id, type, memory, addons, filter, usedBytes, totalBytes, kinds, kept);
    }

    /** Whether one of the add-ons is {@code addon}. */
    public boolean has(CellAddon addon) {
        for (Item item : addons) {
            if (item instanceof CellAddonItem part && part.addon() == addon) {
                return true;
            }
        }
        return false;
    }

    /** Bytes the memory modules add up to, from config; modules this mod does not know add nothing. Saturates. */
    public long memoryBytes() {
        long total = 0;
        for (Item item : memory) {
            if (item instanceof MemoryModuleItem module) {
                long sum = total + module.bytes();
                total = sum < total ? Long.MAX_VALUE : sum;
            }
        }
        return total;
    }

    /** The capacity in a cell of {@code casing}: {@link #memoryBytes()}, multiplied for a bulk casing. */
    public long capacity(CellCasing casing) {
        return casing.capacity(memoryBytes());
    }
}
