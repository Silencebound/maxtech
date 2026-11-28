package dev.silencebound.maxtech.lib.api.recipe;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;

/**
 * What a machine has room for, so its recipe type refuses recipes it could never run: slots and tanks per side, and its
 * modes. Recipe viewers draw a type's page from it. A recipe needs at least one input and one output of either sort;
 * every count is an upper bound. Immutable; safe from any thread.
 *
 * @param itemInputs
 *            input slots, so at most this many consumed item inputs
 * @param catalysts
 *            catalyst slots, so at most this many unconsumed item inputs (a mask, a lens)
 * @param itemOutputs
 *            output slots, so at most this many item outputs
 * @param tankInputs
 *            input tanks, so at most this many {@link KindInput}s
 * @param tankOutputs
 *            output tanks, so at most this many {@link KindOutput}s
 * @param modes
 *            the machine's mode ids; empty for a machine without modes, whose recipes have none, while a machine with
 *            modes needs one of these on every recipe
 */
@ApiStatus.Experimental
public record MachineRecipeShape(int itemInputs, int catalysts, int itemOutputs, int tankInputs, int tankOutputs,
        List<String> modes) {
    /** Throws {@link IllegalArgumentException} for a negative count, a blank or repeated mode. */
    public MachineRecipeShape {
        if (itemInputs < 0 || catalysts < 0 || itemOutputs < 0 || tankInputs < 0 || tankOutputs < 0) {
            throw new IllegalArgumentException("Negative slot or tank count in a machine recipe shape");
        }
        modes = List.copyOf(modes);
        for (int i = 0; i < modes.size(); i++) {
            if (modes.get(i).isBlank() || modes.indexOf(modes.get(i)) != i) {
                throw new IllegalArgumentException("Blank or repeated mode " + modes.get(i));
            }
        }
    }

    /** {@code inputs} input slots and {@code outputs} output slots; no catalysts, tanks or modes. */
    public static MachineRecipeShape items(int inputs, int outputs) {
        return new MachineRecipeShape(inputs, 0, outputs, 0, 0, List.of());
    }

    /** This shape with {@code inputs} input tanks and {@code outputs} output tanks. */
    public MachineRecipeShape withTanks(int inputs, int outputs) {
        return new MachineRecipeShape(itemInputs, catalysts, itemOutputs, inputs, outputs, modes);
    }

    /** This shape with {@code slots} catalyst slots. */
    public MachineRecipeShape withCatalysts(int slots) {
        return new MachineRecipeShape(itemInputs, slots, itemOutputs, tankInputs, tankOutputs, modes);
    }

    /** This shape with these modes. */
    public MachineRecipeShape withModes(String... ids) {
        return new MachineRecipeShape(itemInputs, catalysts, itemOutputs, tankInputs, tankOutputs, List.of(ids));
    }

    /** Why {@code recipe} does not fit this shape; empty when it does. */
    public Optional<String> check(MachineRecipe recipe) {
        int consumed = 0;
        for (ItemInput input : recipe.inputs()) {
            if (input.consumed()) {
                consumed++;
            }
        }
        return check(consumed, recipe.inputs().size() - consumed, recipe.outputs().size(), recipe.tankInputs().size(),
                recipe.tankOutputs().size(), recipe.mode());
    }

    /** {@link #check(MachineRecipe)} over counts, which unit tests reach without items. */
    Optional<String> check(int consumed, int catalystInputs, int outputs, int tankIns, int tankOuts,
            Optional<String> mode) {
        if (consumed > itemInputs || catalystInputs > catalysts || outputs > itemOutputs || tankIns > tankInputs
                || tankOuts > tankOutputs) {
            return Optional.of("Recipe needs " + consumed + " input, " + catalystInputs + " catalyst, " + outputs
                    + " output slots and " + tankIns + " input, " + tankOuts + " output tanks; the machine has "
                    + itemInputs + ", " + catalysts + ", " + itemOutputs + " and " + tankInputs + ", " + tankOutputs);
        }
        if (consumed + catalystInputs + tankIns == 0) {
            return Optional.of("Recipe has no inputs");
        }
        if (outputs + tankOuts == 0) {
            return Optional.of("Recipe has no outputs");
        }
        if (modes.isEmpty() ? mode.isPresent() : !modes.contains(mode.orElse(""))) {
            return Optional.of("Recipe needs a mode out of " + modes + ", not " + mode.orElse("none"));
        }
        return Optional.empty();
    }
}
