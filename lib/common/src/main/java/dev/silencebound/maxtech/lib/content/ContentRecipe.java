package dev.silencebound.maxtech.lib.content;

import net.minecraft.world.item.Item;

/**
 * A recipe declared with an item, built with vanilla's recipe builders. It runs only in datagen, so it may look up
 * material tags ({@code MaterialDatagen}) that are not ready at class init.
 */
@FunctionalInterface
public interface ContentRecipe<I extends Item> {
    void build(RecipeContext recipes, I item);
}
