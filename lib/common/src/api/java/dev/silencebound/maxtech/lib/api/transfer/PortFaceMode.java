package dev.silencebound.maxtech.lib.api.transfer;

import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * What one face of a {@link TransferPort} does with one resource kind (ADR-0018): nothing, push the port's buffer out
 * to the block on that side, or pull from it. A face pushes or pulls, never both (owner). Immutable; any thread.
 */
@ApiStatus.Experimental
public enum PortFaceMode {
    /** Nothing moves through the face. */
    OFF(0),
    /** The port moves from its buffer into the block on this side. */
    PUSH(1),
    /** The port moves from the block on this side into its buffer. */
    PULL(2);

    private static final PortFaceMode[] BY_ID = {OFF, PUSH, PULL};

    private final int id;

    PortFaceMode(int id) {
        this.id = id;
    }

    /** Stable id in {@code 0..2}, for saving and for packing two bits per face; never the ordinal. */
    public int id() {
        return id;
    }

    /** The mode with {@code id}, or empty outside {@code 0..2}. */
    public static Optional<PortFaceMode> byId(int id) {
        return id >= 0 && id < BY_ID.length ? Optional.of(BY_ID[id]) : Optional.empty();
    }

    /**
     * Whether a face in this mode moves anything when the block on its side is another Lib port whose facing face
     * ({@code face.getOpposite()}) has {@code facing} for the same kind. Only two active faces are restricted (owner):
     * a {@code PUSH} face facing a {@code PULL} face moves and the pushing port does it, so the {@code PULL} face skips
     * the pusher; two {@code PUSH} faces or two {@code PULL} faces move nothing between them. A neighbour's {@code OFF}
     * face counts as a block that is not a port (whether it exposes its buffers is that block's choice), so a
     * {@code PUSH} or {@code PULL} face acts on it as usual. An {@code OFF} face never moves.
     */
    public boolean movesAgainst(PortFaceMode facing) {
        return this != OFF && (facing == OFF || (this == PUSH && facing == PULL));
    }

    /**
     * The mode a wrench or button moves to from this one: the next in the order {@code OFF, PUSH, PULL}, wrapping round
     * and skipping modes not in {@code allowed}. Returns this mode if {@code allowed} holds no other.
     */
    public PortFaceMode next(Set<PortFaceMode> allowed) {
        for (int step = 1; step < BY_ID.length; step++) {
            PortFaceMode candidate = BY_ID[(id + step) % BY_ID.length];
            if (allowed.contains(candidate)) {
                return candidate;
            }
        }
        return this;
    }
}
