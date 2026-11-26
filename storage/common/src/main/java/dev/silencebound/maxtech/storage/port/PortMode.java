package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.util.StringRepresentable;

/**
 * What a port does with the inventory it faces (PRD 7.2, Devices: plain import, export and storage buses are port
 * presets). A block state property, so the port's look shows it. The serialized names are what saves, items and block
 * states hold; a new mode gets a new name and never renames an old one.
 */
public enum PortMode implements StringRepresentable {
    /** Moves what the filter accepts from the inventory into the network. */
    IMPORT("import", 0),
    /** Moves what the filter names from the network into the inventory. */
    EXPORT("export", 1),
    /**
     * Mounts the inventory as network storage (B09 item 8, AE2's storage bus): the network sees its contents and stores
     * into and takes from it directly ({@link PortStorage}). The port's rate does not apply.
     */
    STORAGE("storage", 2);

    private final String name;
    private final int id;

    PortMode(String name, int id) {
        this.name = name;
        this.id = id;
    }

    /**
     * The mode's number for menus and the part's look variant: 0 import, 1 export, 2 storage. Explicit rather than the
     * ordinal, so a reordering never changes it.
     */
    public int id() {
        return id;
    }

    /** The mode with {@link #id()} {@code id}; import for an unknown one. */
    public static PortMode byId(int id) {
        for (PortMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        return IMPORT;
    }

    /** The mode saved as {@code name}; import for an unknown or missing one. */
    public static PortMode byName(String name) {
        for (PortMode mode : values()) {
            if (mode.name.equals(name)) {
                return mode;
            }
        }
        return IMPORT;
    }

    /** The next mode in the cycle import, export, storage; what the GUI button and a wrench switch to. */
    public PortMode next() {
        return switch (this) {
            case IMPORT -> EXPORT;
            case EXPORT -> STORAGE;
            case STORAGE -> IMPORT;
        };
    }

    /** The lang key of the mode's name. */
    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".port.mode." + name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
