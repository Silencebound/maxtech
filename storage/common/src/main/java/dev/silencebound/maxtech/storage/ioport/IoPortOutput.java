package dev.silencebound.maxtech.storage.ioport;

import dev.silencebound.maxtech.storage.MaxtechStorage;

/**
 * When an IO port moves a cell from its input to its output slots (B09 item 11). Saved and synced by {@link #id()}.
 */
public enum IoPortOutput {
    /** As soon as the cell holds nothing. */
    EMPTY("empty", 0),
    /** As soon as the cell has no byte free. */
    FULL("full", 1),
    /** Once the port can do no more with the cell: a whole pass over its kinds (or the network's) moved nothing. */
    WORK_DONE("work_done", 2);

    private final String name;
    private final int id;

    IoPortOutput(String name, int id) {
        this.name = name;
        this.id = id;
    }

    /** The value saved and synced for this one; never changes. */
    public int id() {
        return id;
    }

    /** The next value; what the screen's button switches to. */
    public IoPortOutput next() {
        IoPortOutput[] values = values();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == this) {
                return values[(i + 1) % values.length];
            }
        }
        return this;
    }

    /** The lang key of the setting's name. */
    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".io_port.output." + name;
    }

    /** The setting with {@code id}; {@link #WORK_DONE} for anything unknown. */
    public static IoPortOutput byId(int id) {
        for (IoPortOutput value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        return WORK_DONE;
    }
}
