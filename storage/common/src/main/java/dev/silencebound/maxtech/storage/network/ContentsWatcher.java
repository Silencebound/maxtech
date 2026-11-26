package dev.silencebound.maxtech.storage.network;

/**
 * Hears every change of a storage's totals by key id, the moment its provider commits it; an open terminal turns these
 * into its synced list's deltas, so it never diffs or scans the contents. Kept cheap: one call per changed key, and a
 * storage with no watchers pays one empty-list check. Server thread.
 */
public interface ContentsWatcher {
    /** The key with {@code id} now totals {@code total}; 0 when the storage holds none of it any more. */
    void changed(int id, long total);

    /**
     * {@code id}'s key was released (its total stayed 0) and the id may name another key from the next intern on; a
     * watcher that still owes its client the 0 must send it now.
     */
    void released(int id);
}
