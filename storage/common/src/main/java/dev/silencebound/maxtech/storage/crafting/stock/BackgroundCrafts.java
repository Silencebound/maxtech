package dev.silencebound.maxtech.storage.crafting.stock;

/**
 * Holds the {@link BackgroundCrafting} in use: the job queue's once it installs itself, a test stand-in in GameTests,
 * and before either one that refuses everything. Server thread only.
 */
public final class BackgroundCrafts {
    /** Refuses every request; what stock keepers and cards see before the job queue installs itself. */
    public static final BackgroundCrafting NONE = (network, request, done) -> false;

    private static BackgroundCrafting current = NONE;

    private BackgroundCrafts() {
    }

    public static BackgroundCrafting get() {
        return current;
    }

    /** Makes {@code crafting} the queue background requests go to; {@link #NONE} switches them off. */
    public static void install(BackgroundCrafting crafting) {
        current = crafting;
    }
}
