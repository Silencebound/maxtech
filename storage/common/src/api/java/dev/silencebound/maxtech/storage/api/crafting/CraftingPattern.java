package dev.silencebound.maxtech.storage.api.crafting;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * An encoded pattern: what goes in and what comes out of one craft. The pattern terminal and machine export write the
 * same format, and the planner does not care which wrote it.
 *
 * <p>
 * The planner counts only the {@link #mainOutput() main output}; the others are byproducts that go to storage as a
 * bonus. A chance main output repeats its step until enough has come out, up to a configured cap.
 *
 * <p>
 * Immutable; safe on any thread.
 * <p>
 * Experimental (Storage 0.5.0): may change in any Storage minor version, also after 1.0.
 *
 * @param kind
 *            crafting-table or machine recipe
 * @param inputs
 *            inputs per craft, at least one; for {@link Kind#CRAFTING} at most nine, in grid order
 * @param outputs
 *            outputs per craft, at least one; the first is the main output
 * @param recipeType
 *            the recipe type the pattern was taken from, if known (for time estimates and dispatch by machine)
 * @param recipe
 *            the recipe the pattern was taken from, if known
 */
@ApiStatus.Experimental
public record CraftingPattern(Kind kind, List<PatternInput> inputs, List<PatternOutput> outputs,
        Optional<Identifier> recipeType, Optional<Identifier> recipe) {
    /** How a pattern runs. */
    public enum Kind {
        /** A crafting-table recipe, run by an assembler next to a provider. */
        CRAFTING,
        /** A machine recipe: the provider puts the inputs into a machine and pulls the outputs back. */
        PROCESSING
    }

    /**
     * Copies the lists. Throws {@link IllegalArgumentException} without inputs or outputs, or for a crafting pattern
     * with more than nine inputs.
     */
    public CraftingPattern {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        if (inputs.isEmpty() || outputs.isEmpty()) {
            throw new IllegalArgumentException("a pattern needs at least one input and one output");
        }
        if (kind == Kind.CRAFTING && inputs.size() > 9) {
            throw new IllegalArgumentException("a crafting pattern has at most nine inputs: " + inputs.size());
        }
    }

    /** The output the planner counts on. */
    public PatternOutput mainOutput() {
        return outputs.getFirst();
    }
}
