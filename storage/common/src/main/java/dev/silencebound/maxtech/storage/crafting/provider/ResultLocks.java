package dev.silencebound.maxtech.storage.crafting.provider;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.crafting.pattern.NetworkPatterns;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.List;

/**
 * Unlocks providers in {@link LockMode#UNTIL_RESULT} (B09 item 26): a {@link CraftingDeliveries} listener that hands
 * every report of a key to the providers on the network (and linked ones) holding a pattern for it, each once. Reports
 * come from a provider's own pulls and from interfaces a machine pushes into, so either way unlocks. A lookup per
 * report, never a scan of the level. Server thread.
 */
public final class ResultLocks implements CraftingDeliveries.Listener {
    public static final ResultLocks INSTANCE = new ResultLocks();

    private ResultLocks() {
    }

    /** Whether some provider for {@code key} on {@code network} waits for it, so an interface reports it. */
    @Override
    public boolean expects(NetworkState network, StackKey key) {
        List<NetworkPatterns.Entry> entries = network.patterns().allPatternsFor(key);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).provider() instanceof ProviderLogic logic && logic.awaitsResult(key)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void delivered(NetworkState network, StackKey key, long amount) {
        List<NetworkPatterns.Entry> entries = network.patterns().allPatternsFor(key);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).provider() instanceof ProviderLogic logic && logic.awaitsResult(key)
                    && first(entries, i, logic)) {
                logic.resultDelivered(key, amount);
            }
        }
    }

    /** Whether entry {@code index} is {@code logic}'s first in {@code entries}; a provider counts a report once. */
    private static boolean first(List<NetworkPatterns.Entry> entries, int index, ProviderLogic logic) {
        for (int i = 0; i < index; i++) {
            if (entries.get(i).provider() == logic) {
                return false;
            }
        }
        return true;
    }
}
