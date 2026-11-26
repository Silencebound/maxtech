package dev.silencebound.maxtech.lib.neoforge.datagen;

import net.minecraft.data.recipes.RecipeOutput;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.common.conditions.ModLoadedCondition;
import net.neoforged.neoforge.common.conditions.NotCondition;

/**
 * Load conditions shared by every mod's recipe datagen. A fallback recipe for a mod that runs without another addon
 * goes through {@link #withoutMod(RecipeOutput, String)}, so the suite writes that condition one way.
 */
public final class RecipeConditions {
    private RecipeConditions() {
    }

    /** {@code neoforge:not} of {@code neoforge:mod_loaded} on {@code modId}: true only while that mod is absent. */
    public static ICondition withoutMod(String modId) {
        return new NotCondition(new ModLoadedCondition(modId));
    }

    /**
     * {@code output} with {@link #withoutMod(String)} added to every recipe saved through it. Its unlock advancement
     * gets the same condition, since NeoForge's conditional output writes the conditions into both files.
     */
    public static RecipeOutput withoutMod(RecipeOutput output, String modId) {
        return output.withConditions(withoutMod(modId));
    }
}
