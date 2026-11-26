package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.util.StringRepresentable;

/**
 * How a port filter entry matches (B09 item 18, AE2 and RS parity): the named item or fluid with its data components,
 * any variant of it, everything in one of its tags, or everything from its mod. The serialized names are what saves and
 * items hold; a new mode gets a new name and never renames an old one. An entry saved before the modes existed is
 * exact.
 */
public enum MatchMode implements StringRepresentable {
    /** The item or fluid with exactly its data components. */
    EXACT("exact", 0),
    /** The item or fluid whatever its data components (damage, enchantments, names). */
    FUZZY("fuzzy", 1),
    /** Everything in one tag of the item or fluid. */
    TAG("tag", 2),
    /** Everything whose registry id has the item's or fluid's namespace. */
    MOD("mod", 3);

    private final String name;
    private final int id;

    MatchMode(String name, int id) {
        this.name = name;
        this.id = id;
    }

    /** The mode's number in menu data slots; explicit, so a reordering never changes it. */
    public int id() {
        return id;
    }

    /** The mode with {@link #id()} {@code id}; exact for an unknown one. */
    public static MatchMode byId(int id) {
        for (MatchMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        return EXACT;
    }

    /** The mode saved as {@code name}; exact for an unknown or missing one. */
    public static MatchMode byName(String name) {
        for (MatchMode mode : values()) {
            if (mode.name.equals(name)) {
                return mode;
            }
        }
        return EXACT;
    }

    /** The lang key of the mode's name. */
    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".port.match." + name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
