package dev.silencebound.maxtech.storage.network;

/**
 * A {@link StorageProvider} whose room is counted in bytes (PRD 7.2, capacity: a cell's memory). The network adds these
 * up only when something asks ({@link NetworkState#usedBytes}, {@link NetworkState#totalBytes}: a display panel's
 * sample), never on the change path. Server thread only; both values are committed state outside a transaction.
 */
public interface ByteCapacity {
    /** Bytes the contents use now, from 0 to {@link #totalBytes()}. */
    long usedBytes();

    /** The provider's capacity in bytes; 0 for none. */
    long totalBytes();
}
