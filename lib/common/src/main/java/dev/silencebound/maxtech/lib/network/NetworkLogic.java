package dev.silencebound.maxtech.lib.network;

/**
 * Transport semantics for one network type in one level (Storage's cables, ...). Receives topology events from its
 * {@link NetworkGraph} and a tick for every active network. All calls come from the level tick on the server thread.
 * <p>
 * Callbacks must not change the topology directly; changes requested through {@link NetworkManager} are queued and
 * applied in the same or the next level tick. Structural events fire while the graph is being updated, so treat the
 * network's node set as final only in {@link #onRebuilt}.
 */
public interface NetworkLogic {
    /** A network was created for a node that joined no existing network. */
    default void onNetworkCreated(Network network) {
    }

    /** The last node of {@code network} was removed; the network is discarded. */
    default void onNetworkRemoved(Network network) {
    }

    default void onNodeAdded(Network network, long pos) {
    }

    default void onNodeRemoved(Network network, long pos) {
    }

    /**
     * The owner of the node at {@code pos} invalidated it, e.g. because a neighbour changed. Fires on every
     * invalidation, whether or not the node's links changed; a real change also leads to {@link #onRebuilt}. Use it for
     * per-node state that depends on the surroundings rather than on topology, such as attached endpoints.
     */
    default void onNodeInvalidated(Network network, long pos) {
    }

    /**
     * A node connected {@code absorbed} to {@code survivor}; all of {@code absorbed}'s nodes now belong to
     * {@code survivor} and {@code absorbed} is discarded without {@link #onNetworkRemoved}.
     */
    default void onMerged(Network survivor, Network absorbed) {
    }

    /** {@code part} is a new network holding nodes that were cut off from {@code original}. */
    default void onSplit(Network original, Network part) {
    }

    /**
     * {@code network}'s topology changed since its last rebuild and has settled (no split check pending). Fires at most
     * once per network per tick, after all structural events of that tick; rebuild caches here, not in the finer
     * events.
     */
    default void onRebuilt(Network network) {
    }

    /** Runs once per level tick for every network that is neither paused nor settling a split. */
    void tick(Network network);

    /**
     * {@code network} was paused or resumed in pause mode ({@link Network#isPaused()} is {@code paused} now): a paused
     * network is not ticked, so a host that runs work of its own beside the tick (an energy network) pauses it here.
     * Called from the graph's tick when the state changes, never per tick.
     */
    default void onPausedChanged(Network network, boolean paused) {
    }
}
