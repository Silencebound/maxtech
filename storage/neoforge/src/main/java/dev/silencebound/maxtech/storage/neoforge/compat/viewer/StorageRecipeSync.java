package dev.silencebound.maxtech.storage.neoforge.compat.viewer;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

/**
 * Sends Storage's machine recipes to clients on login and datapack reload, so recipe viewers can show them; the server
 * keeps recipes to itself otherwise. No viewer classes, so it runs on servers without one.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class StorageRecipeSync {
    private StorageRecipeSync() {
    }

    @SubscribeEvent
    static void onDatapackSync(OnDatapackSyncEvent event) {
        for (StorageRecipeViews.Category category : StorageRecipeViews.categories()) {
            RecipeType<?> type = category.recipeType();
            event.sendRecipes(type);
        }
    }
}
