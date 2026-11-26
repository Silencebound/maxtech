package dev.silencebound.maxtech.storage.subnet;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.Locale;

/**
 * How a subnet and its parent network see each other's storage (PRD 7.2, Subnet controller; owner decision 2026-10-04).
 * Up is the parent seeing the subnet, down the subnet seeing the parent; a side that sees the other can take from it,
 * and stores into it only when merged.
 */
public enum SubnetMode {
    /** Each side sees, takes from and stores into the other's storage. */
    MERGED(0, true, true),
    /** The parent sees and takes the subnet's contents; the subnet does not see the parent. */
    SHARES_UP(1, true, false),
    /** The subnet sees and takes (pulls) the parent's contents; the parent does not see the subnet. */
    CLIENT(2, false, true),
    /** Neither side sees the other; the subnet runs on its own. */
    ISOLATED(3, false, false);

    private static final SubnetMode[] VALUES = values();

    private final int id;
    private final boolean up;
    private final boolean down;

    SubnetMode(int id, boolean up, boolean down) {
        this.id = id;
        this.up = up;
        this.down = down;
    }

    /** Whether the parent sees the subnet's storage. */
    public boolean up() {
        return up;
    }

    /** Whether the subnet sees the parent's storage. */
    public boolean down() {
        return down;
    }

    /** Whether the side that sees the other may also store into it. */
    public boolean stores() {
        return this == MERGED;
    }

    /** The next mode, for the screen's button. */
    public SubnetMode next() {
        return byId((id + 1) % VALUES.length);
    }

    /** The mode with saved index {@code id}; merged for an unknown one. */
    public static SubnetMode byId(int id) {
        for (SubnetMode mode : VALUES) {
            if (mode.id == id) {
                return mode;
            }
        }
        return MERGED;
    }

    /** The saved index; fixed per mode. */
    public int id() {
        return id;
    }

    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".subnet.mode." + name().toLowerCase(Locale.ROOT);
    }
}
