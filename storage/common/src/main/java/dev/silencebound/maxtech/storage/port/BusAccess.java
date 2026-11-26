package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.util.StringRepresentable;

/**
 * What a port in storage mode lets the network do with the inventory it mounts (B09 item 8): read (see and take), write
 * (store only; the contents are not shown, as in AE2) or both, the default. The serialized names are what saves and
 * items hold.
 */
public enum BusAccess implements StringRepresentable {
    /** The network sees the contents and takes from them, but stores nothing there. */
    READ("read", 0),
    /** The network stores there but neither sees nor takes the contents. */
    WRITE("write", 1),
    /** The network sees, takes and stores. */
    BOTH("both", 2);

    private final String name;
    private final int id;

    BusAccess(String name, int id) {
        this.name = name;
        this.id = id;
    }

    /** Whether the network sees the contents and may take from them. */
    public boolean reads() {
        return this != WRITE;
    }

    /** Whether the network may store into the inventory. */
    public boolean writes() {
        return this != READ;
    }

    /** The number menus sync: 0 read, 1 write, 2 both. Explicit rather than the ordinal. */
    public int id() {
        return id;
    }

    /** The access with {@link #id()} {@code id}; both for an unknown one. */
    public static BusAccess byId(int id) {
        for (BusAccess access : values()) {
            if (access.id == id) {
                return access;
            }
        }
        return BOTH;
    }

    /** The access saved as {@code name}; both for an unknown or missing one. */
    public static BusAccess byName(String name) {
        for (BusAccess access : values()) {
            if (access.name.equals(name)) {
                return access;
            }
        }
        return BOTH;
    }

    /** The next in the cycle both, read, write; what the GUI button switches to. */
    public BusAccess next() {
        return switch (this) {
            case BOTH -> READ;
            case READ -> WRITE;
            case WRITE -> BOTH;
        };
    }

    /** The lang key of the access's name. */
    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".port.access." + name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
