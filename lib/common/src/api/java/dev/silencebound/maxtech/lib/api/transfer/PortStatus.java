package dev.silencebound.maxtech.lib.api.transfer;

import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;

/** What a {@link TransferPort} last did with one resource kind, for screens and probes. Immutable; any thread. */
@ApiStatus.Experimental
public enum PortStatus {
    /** No face pushes or pulls the kind, or the port is stopped. */
    OFF(0),
    /** Nothing to move: no partner on an active face, an empty buffer to push or a full one to pull into. */
    IDLE(1),
    /** The last operation moved something. */
    MOVING(2),
    /** There is something to move, but every partner refused it (full, filtered, or no transactions on either side). */
    BLOCKED(3);

    private static final PortStatus[] BY_ID = {OFF, IDLE, MOVING, BLOCKED};

    private final int id;

    PortStatus(int id) {
        this.id = id;
    }

    /** Stable id in {@code 0..3}, for syncing; never the ordinal. */
    public int id() {
        return id;
    }

    /** The status with {@code id}, or empty outside {@code 0..3}. */
    public static Optional<PortStatus> byId(int id) {
        return id >= 0 && id < BY_ID.length ? Optional.of(BY_ID[id]) : Optional.empty();
    }
}
