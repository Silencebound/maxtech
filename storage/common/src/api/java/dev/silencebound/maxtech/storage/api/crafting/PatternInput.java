package dev.silencebound.maxtech.storage.api.crafting;

import java.util.Optional;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.ApiStatus;

/**
 * One input of a pattern, per craft.
 *
 * <p>
 * Immutable; safe on any thread.
 * <p>
 * Experimental (Storage 0.5.0): may change in any Storage minor version, also after 1.0.
 *
 * @param ingredient
 *            what is accepted
 * @param amount
 *            items, or millibuckets for fluids, per craft; at least 1
 * @param face
 *            the face of the target machine the input goes into (a furnace takes ore on top, fuel from the side); empty
 *            means the face the provider touches
 * @param returned
 *            whether the input comes back after the craft (a tool, a bucket), so the planner needs one, not one per
 *            craft
 */
@ApiStatus.Experimental
public record PatternInput(PatternIngredient ingredient, long amount, Optional<Direction> face, boolean returned) {
    /** Throws {@link IllegalArgumentException} if {@code amount} is below 1. */
    public PatternInput {
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be at least 1: " + amount);
        }
    }
}
