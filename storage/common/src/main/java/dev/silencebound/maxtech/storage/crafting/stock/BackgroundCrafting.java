package dev.silencebound.maxtech.storage.crafting.stock;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Where stock keepers and crafting cards send their crafts: background jobs that the job queue (M7c area A) plans and
 * runs after every player request and never pre-empting one (C9, C16), and which never take the last CPUs the network
 * keeps free for players (M7L2 chunk 20). The queue installs itself with {@link BackgroundCrafts#install}; until it
 * does, every request is refused. Server thread only.
 */
public interface BackgroundCrafting {
    /**
     * One background craft.
     *
     * @param key
     *            what to make
     * @param amount
     *            how much, at least 1; the queue caps it to the network's per-job maximum for automation
     *            ({@code CraftLimits}), and the requester asks again for the rest
     * @param priority
     *            higher goes first among background jobs; player requests always go before any
     * @param cpuTier
     *            the only CPU tier the job may run on, 1 to 3, or 0 for any
     * @param owner
     *            whose device asks, for research and security
     */
    record Request(StackKey key, long amount, int priority, int cpuTier, OwnerRecord owner) {
    }

    /** How a submitted craft ended. */
    enum Outcome {
        /** Everything asked for was made. */
        DONE,
        /** Some was made; the rest was missing. */
        PARTIAL,
        /** Cancelled by a player or because the network went away. */
        CANCELLED,
        /** Planning failed or nothing could be made. */
        FAILED
    }

    /**
     * Queues {@code request} on {@code network}. True when the queue took it: {@code done} is then called exactly once
     * on the server thread, never from inside this call. False when it is refused now (nothing craftable, the queue
     * full); {@code done} is then never called.
     */
    boolean submit(NetworkState network, Request request, Consumer<Outcome> done);

    /**
     * {@link #submit}, also telling {@code queued} the id of the crafting job once the request is planned and queued
     * (never for a refused or failed one), so the requester can find its own job later (a logic graph cancelling only
     * its own crafts). Called at most once, on the server thread, before {@code done}. A queue without job ids never
     * calls it.
     */
    default boolean submit(NetworkState network, Request request, Consumer<Outcome> done, Consumer<UUID> queued) {
        return submit(network, request, done);
    }
}
