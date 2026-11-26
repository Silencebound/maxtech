package dev.silencebound.maxtech.storage.crafting.provider;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternProvider;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.job.PatternDispatch;
import dev.silencebound.maxtech.storage.crafting.pattern.NetworkPatterns;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternReturns;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Machine-aware dispatch (storage/PRD.md 7.2, extension 4): sends a crafting step to the providers on a network that
 * have its pattern and accept it now, split by a {@link DispatchStrategy}. Providers that are not Storage's own (M7b's
 * crafting pipes) count as idle with the default duration. Providers holding the pattern at a higher pattern priority
 * are served first; what they do not take falls through to the next lower priority in the same call, and providers of
 * one priority split their part. A pattern with a returned input goes to one provider only, since the job holds one of
 * it. Each share is pushed whole; a refused share is halved until a push passes or one craft is refused, and whatever
 * is not handed over stays with the caller to try again later. Called by the executor on its cadence, never per tick;
 * server thread.
 */
public final class CraftingDispatcher {
    private CraftingDispatcher() {
    }

    /**
     * {@link #dispatch(NetworkState, CraftingPattern, int, Object2LongMap, DispatchStrategy)} with the configured
     * strategy.
     */
    public static int dispatch(NetworkState network, CraftingPattern pattern, int crafts,
            Object2LongMap<StackKey> inputs) {
        return dispatch(network, pattern, crafts, inputs, StorageServerConfig.CRAFTING_DISPATCH_STRATEGY.get());
    }

    /**
     * Hands up to {@code crafts} crafts of {@code pattern} to the network's providers.
     *
     * @param inputs
     *            the resolved inputs for all {@code crafts} crafts (key to amount); what was handed over is taken out
     *            of it, the rest stays for the caller
     * @return crafts handed over, 0 when no provider took any
     */
    public static int dispatch(NetworkState network, CraftingPattern pattern, int crafts,
            Object2LongMap<StackKey> inputs, DispatchStrategy strategy) {
        if (crafts < 1) {
            return 0;
        }
        List<Target> targets = targets(network.patterns(), pattern);
        boolean returned = hasReturned(pattern);
        int sent = 0;
        // Highest priority first: the holders of one priority split what is left; what they do not take falls through
        // to the next lower priority in the same call.
        for (int start = 0; start < targets.size() && sent < crafts;) {
            int end = start + 1;
            while (end < targets.size() && targets.get(end).priority() == targets.get(start).priority()) {
                end++;
            }
            // A pattern with a returned input goes to one provider only, since the job holds one of it.
            int n = returned ? 1 : end - start;
            int[] load = new int[n];
            int[] ticks = new int[n];
            for (int i = 0; i < n; i++) {
                if (targets.get(start + i).dispatch() instanceof ProviderLogic logic) {
                    load[i] = logic.inFlight();
                    ticks[i] = logic.ticksPerCraft(pattern);
                } else {
                    ticks[i] = ProviderLogic.DEFAULT_TICKS;
                }
            }
            int[] shares = DispatchSplit.split(strategy, crafts - sent, load, ticks);
            for (int i = 0; i < n; i++) {
                int share = shares[i];
                while (share > 0) {
                    if (push(targets.get(start + i).dispatch(), pattern, share, inputs)) {
                        sent += share;
                        break;
                    }
                    share /= 2;
                }
            }
            if (returned) {
                break;
            }
            start = end;
        }
        return sent;
    }

    /**
     * Whether a provider holding {@code pattern} on {@code network} or a linked network is held by its lock mode (B09
     * item 26): a job that could not send the step then waits for the unlock instead of ending, since the provider
     * wakes it. Called only after a dispatch handed over nothing.
     */
    public static boolean locked(NetworkState network, CraftingPattern pattern) {
        List<NetworkPatterns.Entry> entries = network.patterns().allPatternsFor(pattern.mainOutput().key());
        for (int i = 0; i < entries.size(); i++) {
            NetworkPatterns.Entry entry = entries.get(i);
            if (entry.provider() instanceof ProviderLogic logic && logic.locked() != null
                    && entry.pattern().equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    /** One provider that may take a step, with the highest priority it holds the pattern at. */
    record Target(PatternDispatch dispatch, int priority) {
    }

    /**
     * The providers that have {@code pattern} and accept it now, highest pattern priority first (B09 item 29: a crafter
     * and a provider with an assembler holding the same pattern), and within one priority the one sent work longest ago
     * first (the order round robin serves them in).
     */
    static List<Target> targets(NetworkPatterns patterns, CraftingPattern pattern) {
        List<NetworkPatterns.Entry> entries = patterns.patternsFor(pattern.mainOutput().key());
        List<Target> targets = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            NetworkPatterns.Entry entry = entries.get(i);
            if (!entry.pattern().equals(pattern)) {
                continue;
            }
            PatternDispatch target = entry.provider() instanceof PatternDispatch dispatch
                    ? dispatch
                    : new Foreign(entry.provider());
            int known = indexOf(targets, target);
            if (known >= 0) {
                if (targets.get(known).priority() < entry.priority()) {
                    targets.set(known, new Target(target, entry.priority()));
                }
            } else if (target.canDispatch(pattern)) {
                targets.add(new Target(target, entry.priority()));
            }
        }
        targets.sort(Comparator.comparingInt(Target::priority).reversed()
                .thenComparingLong(target -> lastDispatch(target.dispatch())));
        return targets;
    }

    private static int indexOf(List<Target> targets, PatternDispatch dispatch) {
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).dispatch().equals(dispatch)) {
                return i;
            }
        }
        return -1;
    }

    private static long lastDispatch(PatternDispatch target) {
        return target instanceof ProviderLogic logic ? logic.lastDispatch() : 0;
    }

    private static boolean hasReturned(CraftingPattern pattern) {
        return PatternReturns.any(pattern);
    }

    /** Pushes {@code crafts} crafts with their share of {@code inputs}, taking the share out on success. */
    private static boolean push(PatternDispatch target, CraftingPattern pattern, int crafts,
            Object2LongMap<StackKey> inputs) {
        StackKey[] keys = InputAssignment.assign(pattern, crafts, inputs);
        if (keys == null) {
            return false;
        }
        Object2LongOpenHashMap<StackKey> share = InputAssignment.share(pattern, crafts, keys);
        if (!target.dispatch(pattern, crafts, share)) {
            return false;
        }
        for (Object2LongMap.Entry<StackKey> entry : share.object2LongEntrySet()) {
            long left = inputs.getLong(entry.getKey()) - entry.getLongValue();
            if (left > 0) {
                inputs.put(entry.getKey(), left);
            } else {
                inputs.removeLong(entry.getKey());
            }
        }
        return true;
    }

    /** A provider from another mod: dispatch goes straight to it. */
    private record Foreign(PatternProvider provider) implements PatternDispatch {
        @Override
        public long nodePos() {
            return Long.MIN_VALUE;
        }
    }
}
