package dev.silencebound.maxtech.storage.crafting.provider;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * Where crafting outputs are reported as they enter a network (M7 C13): a pattern provider calls it for what it pulls
 * out of a machine, an interface for what a machine pushes into it. Running jobs (M7c area A's executor) listen and
 * count it against what they wait for. Every call reports moves that are already committed, never a simulation. Server
 * thread only.
 */
public final class CraftingDeliveries {
    /** What a job executor registers. */
    public interface Listener {
        /**
         * Whether a running job on {@code network} waits for {@code key}. Asked on every interface insert, so it must
         * be a lookup; an interface only reports keys some listener expects.
         */
        boolean expects(NetworkState network, StackKey key);

        /** {@code amount} of {@code key} entered {@code network} from a crafting target; committed. */
        void delivered(NetworkState network, StackKey key, long amount);
    }

    private static final ObjectArrayList<Listener> LISTENERS = new ObjectArrayList<>(1);

    private CraftingDeliveries() {
    }

    /** Adds {@code listener}; adding one twice adds it once. */
    public static void listen(Listener listener) {
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    /** Removes {@code listener}; nothing happens for one never added. */
    public static void unlisten(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** Whether some listener waits for {@code key} on {@code network}; false without listeners. */
    public static boolean expects(NetworkState network, StackKey key) {
        for (int i = 0; i < LISTENERS.size(); i++) {
            if (LISTENERS.get(i).expects(network, key)) {
                return true;
            }
        }
        return false;
    }

    /** Reports {@code amount} of {@code key} that entered {@code network}; does nothing for 0 or less. */
    public static void delivered(NetworkState network, StackKey key, long amount) {
        if (amount <= 0) {
            return;
        }
        for (int i = 0; i < LISTENERS.size(); i++) {
            LISTENERS.get(i).delivered(network, key, amount);
        }
    }
}
