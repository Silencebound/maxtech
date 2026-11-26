package dev.silencebound.maxtech.lib.neoforge.compat.kubejs;

import dev.latvian.mods.kubejs.recipe.RecipeKey;
import dev.latvian.mods.kubejs.recipe.component.NumberComponent;
import dev.latvian.mods.kubejs.recipe.component.StringComponent;
import dev.silencebound.maxtech.lib.api.recipe.ItemInput;
import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import java.util.List;

/**
 * Recipe keys for the fields machine recipes share, named after their JSON field. A mod's schema lists the keys of its
 * recipe codec in constructor order, required keys first:
 *
 * <pre>
 * new RecipeSchema(MachineRecipeKeys.itemOutputs("outputs"), MachineRecipeKeys.itemInputs("inputs"),
 *         MachineRecipeKeys.energyPerTick("energy_per_tick"), MachineRecipeKeys.duration("duration"),
 *         MachineRecipeKeys.optionalString("mode"))
 * </pre>
 *
 * Every key reads and writes the field's own codec, so the mod's serializer checks the finished recipe. A new key is
 * made per call, since KubeJS keys are mutable.
 */
public final class MachineRecipeKeys {
    private MachineRecipeKeys() {
    }

    /** A list of item inputs; a script may pass a single input in place of the list. */
    public static RecipeKey<List<ItemInput>> itemInputs(String name) {
        return MachineRecipeComponents.ITEM_INPUT.asListOrSelf().inputKey(name);
    }

    /** A list of item outputs; a script may pass a single output in place of the list. */
    public static RecipeKey<List<ItemOutput>> itemOutputs(String name) {
        return MachineRecipeComponents.ITEM_OUTPUT.asListOrSelf().outputKey(name);
    }

    /** FE drawn per tick, at least 0. */
    public static RecipeKey<Integer> energyPerTick(String name) {
        return NumberComponent.NON_NEGATIVE_INT.otherKey(name);
    }

    /** Ticks per run, at least 1. */
    public static RecipeKey<Integer> duration(String name) {
        return NumberComponent.POSITIVE_INT.otherKey(name);
    }

    /** An optional string such as a machine mode; left out of the JSON when a script doesn't set it. */
    public static RecipeKey<String> optionalString(String name) {
        return StringComponent.STRING.otherKey(name).defaultOptional();
    }

    /** An optional identifier such as a research unlock; left out of the JSON when a script doesn't set it. */
    public static RecipeKey<String> optionalId(String name) {
        return StringComponent.ID.otherKey(name).defaultOptional();
    }
}
