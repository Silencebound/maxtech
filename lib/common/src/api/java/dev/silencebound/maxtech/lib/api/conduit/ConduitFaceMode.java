package dev.silencebound.maxtech.lib.api.conduit;

import java.util.Optional;
import java.util.Set;

/**
 * How one face of a conduit meets what is on that side (ADR-0005), seen from the conduit. Towards a block every mode
 * applies; between two conduits a face is only {@link #BOTH} (linked) or {@link #DISABLED}. Immutable and safe from any
 * thread.
 */
public enum ConduitFaceMode {
    /** The conduit gives to and takes from the block. */
    BOTH(0, true, true),
    /** The conduit only gives to the block. */
    INSERT(1, true, false),
    /** The conduit only takes from the block. */
    EXTRACT(2, false, true),
    /** Nothing passes; towards another conduit the link is cut. */
    DISABLED(3, false, false);

    private static final ConduitFaceMode[] BY_ID = {BOTH, INSERT, EXTRACT, DISABLED};

    private final int id;
    private final boolean inserts;
    private final boolean extracts;

    ConduitFaceMode(int id, boolean inserts, boolean extracts) {
        this.id = id;
        this.inserts = inserts;
        this.extracts = extracts;
    }

    /**
     * Stable id in {@code 0..3}, used for saving and for packing two bits per side. Never changes between versions,
     * unlike the ordinal.
     */
    public int id() {
        return id;
    }

    /** The mode with {@code id}, or empty if {@code id} is outside {@code 0..3}. */
    public static Optional<ConduitFaceMode> byId(int id) {
        return id >= 0 && id < BY_ID.length ? Optional.of(BY_ID[id]) : Optional.empty();
    }

    /** Whether the conduit may give to the block on this face. */
    public boolean inserts() {
        return inserts;
    }

    /** Whether the conduit may take from the block on this face. */
    public boolean extracts() {
        return extracts;
    }

    /**
     * The mode a wrench moves to from this one: the next in the order {@code BOTH, INSERT, EXTRACT, DISABLED}, wrapping
     * round and skipping modes not in {@code supported}. Returns this mode if {@code supported} holds no other mode.
     */
    public ConduitFaceMode next(Set<ConduitFaceMode> supported) {
        for (int step = 1; step < BY_ID.length; step++) {
            ConduitFaceMode candidate = BY_ID[(id + step) % BY_ID.length];
            if (supported.contains(candidate)) {
                return candidate;
            }
        }
        return this;
    }
}
