package dev.silencebound.maxtech.lib.content;

import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import java.util.List;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * What an item chain, or the item of a block chain, declared for datagen. Block items have no model and no lang: they
 * use their block's. Any other item with a {@code null} model gets one written by hand in the model provider.
 */
public record ItemDeclaration<I extends Item>(RegistryEntry<Item, I> entry, @Nullable ItemModel model,
        @Nullable String lang, List<TagKey<Item>> tags, List<ContentRecipe<? super I>> recipes) {
    /** Runs the declared recipes; datagen only, the item registry must be populated. */
    public void buildRecipes(RecipeContext context) {
        I item = entry.get();
        for (ContentRecipe<? super I> recipe : recipes) {
            recipe.build(context, item);
        }
    }
}
