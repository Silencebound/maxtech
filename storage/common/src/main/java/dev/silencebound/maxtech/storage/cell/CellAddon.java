package dev.silencebound.maxtech.storage.cell;

/**
 * The add-ons a cell can take in its add-on slots (PRD 7.2, the cell builder), at most one of each. Each one changes
 * how its {@link CellInventory} answers inserts; extracting is never limited.
 */
public enum CellAddon {
    /**
     * Takes only what the cell's {@link CellFilter} names, or with a deny list everything it does not; a blank filter
     * takes everything.
     */
    PARTITION("partition"),
    /**
     * Destroys what does not fit of the keys the cell's filter names (with a deny list: all keys but the denied ones),
     * so a full cell keeps taking them. A blank filter voids nothing.
     */
    VOID_OVERFLOW("void_overflow"),
    /** Refuses every insert. */
    READ_ONLY("read_only");

    private final String id;

    CellAddon(String id) {
        this.id = id;
    }

    /** The add-on's name in ids ({@code partition} ...). */
    public String id() {
        return id;
    }
}
