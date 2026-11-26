package dev.silencebound.maxtech.storage.neoforge.compat.viewer;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.StorageMachineRecipe;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;

/**
 * The Storage machine recipes the server sent this client ({@link StorageRecipeSync}), per machine. Replaced on each
 * sync, cleared on logout. Client thread.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
public final class ClientStorageRecipes {
    private static volatile Map<MachineKind, List<RecipeHolder<StorageMachineRecipe>>> recipes = Map.of();

    private ClientStorageRecipes() {
    }

    /** The recipes of {@code kind}; empty before the first sync. */
    public static List<RecipeHolder<StorageMachineRecipe>> of(MachineKind kind) {
        return recipes.getOrDefault(kind, List.of());
    }

    // Highest priority so the list is current before a recipe viewer reloads on the same event.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onRecipesReceived(RecipesReceivedEvent event) {
        Map<MachineKind, List<RecipeHolder<StorageMachineRecipe>>> received = new EnumMap<>(MachineKind.class);
        for (StorageRecipeViews.Category category : StorageRecipeViews.categories()) {
            RecipeType<StorageMachineRecipe> type = MachineContent.recipeType(category.kind());
            if (event.getRecipeTypes().contains(type)) {
                received.put(category.kind(), List.copyOf(event.getRecipeMap().byType(type)));
            }
        }
        recipes = received;
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        recipes = Map.of();
    }
}
