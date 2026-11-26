package dev.silencebound.maxtech.storage.api.crafting;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * Something on a storage network that runs patterns: Storage's pattern provider, and from M7b routed crafting pipes.
 * The network collects the patterns of every provider; a crafting job sends each step to a provider that has the step's
 * pattern and is free.
 *
 * <p>
 * Outputs need no callback: whatever the provider pulls from the machine, or a machine pushes into an interface, enters
 * the network, and running jobs count it against what they expect.
 *
 * <p>
 * Server thread only, never per tick.
 * <p>
 * Experimental (Storage 0.5.0): may change in any Storage minor version, also after 1.0.
 */
@ApiStatus.Experimental
public interface PatternProvider {
    /** The patterns this provider runs; empty when it has none. */
    List<CraftingPattern> patterns();

    /**
     * Whether the provider can take a batch of {@code pattern} now: it has the pattern, its target accepts input and,
     * in blocking mode, its target is empty.
     */
    boolean canAccept(CraftingPattern pattern);

    /**
     * Hands over the inputs for {@code crafts} crafts of {@code pattern}. All or nothing: either the provider takes
     * every input and returns true, or takes nothing and returns false, so the caller keeps the inputs.
     *
     * @param inputs
     *            the resolved key for each input with its total amount for all crafts; the caller owns the map
     */
    boolean push(CraftingPattern pattern, int crafts, Object2LongMap<StackKey> inputs);
}
