package dev.silencebound.maxtech.storage.network;

import java.util.UUID;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The player a network operation is done for (PRD 7.2, Multiplayer; M6f decision F9): a player-facing path sets it
 * around its operations and {@link NetworkState#insert}/{@link NetworkState#extract} hand it to the network's
 * {@link MoveWatcher}s. A static field rather than a parameter so the terminal's source interface, the network chest
 * and the tools keep their signatures, and an operation allocates nothing for it. Device operations (ports, buses,
 * crafting) are always automation, whatever is set here. Server thread only.
 *
 * <pre>
 * UUID previous = NetworkActor.begin(player);
 * try {
 *     network.extract(key, amount, transaction);
 * } finally {
 *     NetworkActor.end(previous);
 * }
 * </pre>
 */
public final class NetworkActor {
    private static @Nullable UUID current;

    private NetworkActor() {
    }

    /** Attributes the following player operations to {@code player}; returns what to hand {@link #end}. */
    public static @Nullable UUID begin(Player player) {
        UUID previous = current;
        current = player.getUUID();
        return previous;
    }

    /** Restores the actor {@link #begin} replaced. */
    public static void end(@Nullable UUID previous) {
        current = previous;
    }

    /** The player set now; null for none (automation). */
    static @Nullable UUID current() {
        return current;
    }
}
