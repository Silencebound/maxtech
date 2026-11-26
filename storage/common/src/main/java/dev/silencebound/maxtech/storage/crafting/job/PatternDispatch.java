package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternProvider;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

/**
 * Where an executor sends a crafting step: a pattern provider (M7c area B) with the api {@link PatternProvider} behind
 * it. The default methods forward to the provider; a provider overrides them for machine-aware dispatch (choosing among
 * machines, splitting batches). Outputs come back through the network on their own (the provider pulls them, or a
 * machine pushes them into an interface), so dispatch has no completion callback. Server thread only.
 */
public interface PatternDispatch {
    /** The provider behind this target. */
    PatternProvider provider();

    /** The graph node of the target, for the executor's route checks. */
    long nodePos();

    /** Whether the target can take a batch of {@code pattern} now. */
    default boolean canDispatch(CraftingPattern pattern) {
        return provider().canAccept(pattern);
    }

    /**
     * Hands over {@code crafts} crafts of {@code pattern} with their resolved inputs, all or nothing. False leaves the
     * inputs with the caller.
     */
    default boolean dispatch(CraftingPattern pattern, int crafts, Object2LongMap<StackKey> inputs) {
        return provider().push(pattern, crafts, inputs);
    }
}
