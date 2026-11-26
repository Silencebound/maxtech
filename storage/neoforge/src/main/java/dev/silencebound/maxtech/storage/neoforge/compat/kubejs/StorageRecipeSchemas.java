package dev.silencebound.maxtech.storage.neoforge.compat.kubejs;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.List;
import net.minecraft.resources.Identifier;

/**
 * The recipe types {@link StorageKubeJSPlugin} gives a schema, all in the {@code StorageMachineRecipe} JSON form. Kept
 * free of KubeJS classes so a GameTest can hold it against the recipe type registry: a new Storage recipe type without
 * a schema fails {@code kubejs_schemas_cover_recipe_types}.
 */
public final class StorageRecipeSchemas {
    /** Machine recipe types, one per processing machine. */
    public static final List<Identifier> MACHINE_RECIPES = List.of(MaxtechStorage.id("lithograph"),
            MaxtechStorage.id("fabricator"), MaxtechStorage.id("assembler"));

    private StorageRecipeSchemas() {
    }
}
