package dev.silencebound.maxtech.storage.cell;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * A cell's casing (PRD 7.2, the cell builder): how many memory modules and add-ons the cell takes, and whether it is a
 * bulk cell (one kind, {@code cells.bulkMultiplier} times its memory) or a portable one. Each casing is an item of its
 * own, and each has its own cell item ({@link StorageCellItem#casing()}). Slot counts and the multiplier are server
 * config; a client in a world reads the values the server sent (outside a world it shows the most slots and no
 * multiplier).
 */
public enum CellCasing {
    SMALL("small", false, false), MEDIUM("medium", false, false), LARGE("large", false, false), BULK("bulk", true,
            false), PORTABLE("portable", false, true);

    /** The most memory module slots any casing may be configured to (the workbench shows this many). */
    public static final int MAX_MEMORY_SLOTS = 8;
    /** The most add-on slots any casing may be configured to. */
    public static final int MAX_ADDON_SLOTS = 4;

    private final String id;
    private final boolean bulk;
    private final boolean portable;

    CellCasing(String id, boolean bulk, boolean portable) {
        this.id = id;
        this.bulk = bulk;
        this.portable = portable;
    }

    /** The casing's name in ids and config ({@code small}, {@code bulk} ...). */
    public String id() {
        return id;
    }

    /** Whether the cell holds one kind only, with its memory multiplied. */
    public boolean bulk() {
        return bulk;
    }

    /** Whether the cell is the handheld one with its own terminal. */
    public boolean portable() {
        return portable;
    }

    /** Memory module slots, from config; any thread. */
    public int memorySlots() {
        return (int) StorageServerConfig.loadedOr(asLong(memoryConfig()), MAX_MEMORY_SLOTS);
    }

    /** Add-on slots, from config; any thread. */
    public int addonSlots() {
        return (int) StorageServerConfig.loadedOr(asLong(addonConfig()), MAX_ADDON_SLOTS);
    }

    /** Bytes a cell of this casing holds with {@code memoryBytes} of memory modules; saturates. */
    public long capacity(long memoryBytes) {
        if (!bulk) {
            return memoryBytes;
        }
        long multiplier = StorageServerConfig.loadedOr(StorageServerConfig.BULK_MULTIPLIER, 1);
        long high = Math.multiplyHigh(memoryBytes, multiplier);
        long low = memoryBytes * multiplier;
        return high != 0 || low < 0 ? Long.MAX_VALUE : low;
    }

    private IntSupplier memoryConfig() {
        return switch (this) {
            case SMALL -> StorageServerConfig.SMALL_MEMORY_SLOTS;
            case MEDIUM -> StorageServerConfig.MEDIUM_MEMORY_SLOTS;
            case LARGE -> StorageServerConfig.LARGE_MEMORY_SLOTS;
            case BULK -> StorageServerConfig.BULK_MEMORY_SLOTS;
            case PORTABLE -> StorageServerConfig.PORTABLE_MEMORY_SLOTS;
        };
    }

    private IntSupplier addonConfig() {
        return switch (this) {
            case SMALL -> StorageServerConfig.SMALL_ADDON_SLOTS;
            case MEDIUM -> StorageServerConfig.MEDIUM_ADDON_SLOTS;
            case LARGE -> StorageServerConfig.LARGE_ADDON_SLOTS;
            case BULK -> StorageServerConfig.BULK_ADDON_SLOTS;
            case PORTABLE -> StorageServerConfig.PORTABLE_ADDON_SLOTS;
        };
    }

    private static LongSupplier asLong(IntSupplier value) {
        return value::getAsInt;
    }
}
