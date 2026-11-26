package dev.silencebound.maxtech.storage.api.crafting;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import org.jetbrains.annotations.ApiStatus;

/**
 * One output of a pattern, per craft.
 *
 * <p>
 * Immutable; safe on any thread.
 * <p>
 * Experimental (Storage 0.5.0): may change in any Storage minor version, also after 1.0.
 *
 * @param key
 *            what comes out
 * @param amount
 *            items, or millibuckets for fluids, per craft; at least 1
 * @param chance
 *            how often it comes out, above 0 and at most 1; 1 is guaranteed
 */
@ApiStatus.Experimental
public record PatternOutput(StackKey key, long amount, float chance) {
    /**
     * Throws {@link IllegalArgumentException} if {@code amount} is below 1 or {@code chance} is outside (0, 1].
     */
    public PatternOutput {
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be at least 1: " + amount);
        }
        if (!(chance > 0f && chance <= 1f)) {
            throw new IllegalArgumentException("chance must be in (0, 1]: " + chance);
        }
    }

    /** Whether the output always comes out. */
    public boolean guaranteed() {
        return chance >= 1f;
    }
}
