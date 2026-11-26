package dev.silencebound.maxtech.storage.network;

/**
 * The server time one network, device or logic rule spent during the running or latest {@link NetworkProfiler} session.
 * A tally from an older session counts as empty and starts over on its first {@link #add}, so a new session never has
 * to visit every tally to clear it. Adding allocates nothing. Server thread only.
 */
public final class ProfileTally {
    private int session;
    private long nanos;
    private int runs;

    /** Adds one timed run of {@code elapsed} nanoseconds to the current session. */
    public void add(long elapsed) {
        int current = NetworkProfiler.session;
        if (session != current) {
            session = current;
            nanos = 0;
            runs = 0;
        }
        nanos += elapsed;
        runs++;
    }

    /** Nanoseconds counted in the current or latest session; 0 when nothing ran in it. */
    public long nanos() {
        return session == NetworkProfiler.session ? nanos : 0;
    }

    /** Timed runs in the current or latest session; 0 when nothing ran in it. */
    public int runs() {
        return session == NetworkProfiler.session ? runs : 0;
    }
}
