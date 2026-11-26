package dev.silencebound.maxtech.storage.ioport;

import dev.silencebound.maxtech.storage.MaxtechStorage;

/**
 * Which way an IO port moves a cell's contents (B09 item 11). Saved and synced by {@link #id()}.
 */
public enum IoPortMode {
    /** The cell empties into the network. */
    UNLOAD("unload", 0),
    /** The cell fills from the network, taking only what its own rules accept. */
    LOAD("load", 1);

    private final String name;
    private final int id;

    IoPortMode(String name, int id) {
        this.name = name;
        this.id = id;
    }

    /** The value saved and synced for this one; never changes. */
    public int id() {
        return id;
    }

    /** The next value; what the screen's button switches to. */
    public IoPortMode next() {
        IoPortMode[] values = values();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == this) {
                return values[(i + 1) % values.length];
            }
        }
        return this;
    }

    /** The lang key of the mode's name. */
    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".io_port.mode." + name;
    }

    /** The mode with {@code id}; {@link #UNLOAD} for anything unknown. */
    public static IoPortMode byId(int id) {
        for (IoPortMode value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        return UNLOAD;
    }
}
