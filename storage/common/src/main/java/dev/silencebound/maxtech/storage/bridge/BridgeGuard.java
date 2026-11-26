package dev.silencebound.maxtech.storage.bridge;

/**
 * The bridges' loop guard (M7b, no loops): held while any bridge reads or moves through a foreign network. While it is
 * held, every {@link ExportedStorage} reports nothing and accepts nothing, and every bridge's own storage does the
 * same. So a foreign network that lists or fills its mounts during the call never sees a Storage network through a
 * bridge, nothing is counted twice, and no operation comes back through a second bridge (the ping-pong between two
 * bridges linking the same pair of networks).
 *
 * <p>
 * The guard is global rather than per Storage network: two Storage networks that already see each other another way (a
 * subnet, or both bridged to the same foreign network) would otherwise count each other twice. The cost is that one
 * Storage network never sees another one through a foreign network. Server thread only; nests.
 */
public final class BridgeGuard {
    private static int depth;

    private BridgeGuard() {
    }

    /** Takes the guard; pair with {@link #exit} in a finally block. */
    static void enter() {
        depth++;
    }

    /** Gives the guard back. */
    static void exit() {
        depth--;
    }

    /** Whether some bridge is talking to a foreign network right now. */
    public static boolean held() {
        return depth > 0;
    }
}
