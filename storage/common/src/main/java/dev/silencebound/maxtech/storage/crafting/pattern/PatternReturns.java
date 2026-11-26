package dev.silencebound.maxtech.storage.crafting.pattern;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;

/**
 * Which pattern inputs come back after a craft (M7C item 3), for the planner, the executor and the provider alike. An
 * input comes back when the player marked it returned, or, in a processing pattern, when it is a catalyst: a guaranteed
 * byproduct (any output but the main one) is something the input accepts, in at least the input's amount. Recipe
 * viewers list such a tool or catalyst on both sides, so patterns encoded from them work without marking it. A returned
 * input is needed once per step, not once per craft, and comes back to the job afterwards. Only an exact input of the
 * byproduct's key counts as a catalyst. Allocation-free; any thread.
 */
public final class PatternReturns {
    private PatternReturns() {
    }

    /** Whether input {@code index} of {@code pattern} comes back after the craft. */
    public static boolean returned(CraftingPattern pattern, int index) {
        return pattern.inputs().get(index).returned() || catalystOutput(pattern, index) >= 0;
    }

    /** Whether any input of {@code pattern} comes back. */
    public static boolean any(CraftingPattern pattern) {
        for (int i = 0; i < pattern.inputs().size(); i++) {
            if (returned(pattern, i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The index of the byproduct that gives input {@code index} back (a catalyst listed as an output too), or -1: only
     * for processing patterns, exact inputs, guaranteed byproducts of the same key and at least the input's amount.
     */
    public static int catalystOutput(CraftingPattern pattern, int index) {
        if (pattern.kind() != CraftingPattern.Kind.PROCESSING) {
            return -1;
        }
        PatternInput input = pattern.inputs().get(index);
        for (int o = 1; o < pattern.outputs().size(); o++) {
            PatternOutput output = pattern.outputs().get(o);
            // Exact inputs only: a tag input that happens to accept a different byproduct is no catalyst.
            if (output.guaranteed() && output.amount() >= input.amount()
                    && input.ingredient() instanceof PatternIngredient.Exact(StackKey key)
                    && key.equals(output.key())) {
                return o;
            }
        }
        return -1;
    }
}
