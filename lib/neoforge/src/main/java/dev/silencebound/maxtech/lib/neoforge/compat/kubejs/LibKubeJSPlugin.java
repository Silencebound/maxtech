package dev.silencebound.maxtech.lib.neoforge.compat.kubejs;

import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.recipe.component.RecipeComponentTypeRegistry;

/**
 * Registers Lib's recipe components with KubeJS, so JSON schemas and typing tools can name them. Listed in Lib's
 * {@code kubejs.plugins.txt}; KubeJS instantiates it, nothing else does.
 */
public final class LibKubeJSPlugin implements KubeJSPlugin {
    @Override
    public void registerRecipeComponents(RecipeComponentTypeRegistry registry) {
        registry.unit(MachineRecipeComponents.ITEM_INPUT);
        registry.unit(MachineRecipeComponents.ITEM_OUTPUT);
    }
}
