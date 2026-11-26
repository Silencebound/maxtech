package dev.silencebound.maxtech.storage.neoforge.compat.viewer;

import dev.silencebound.maxtech.lib.viewer.RecipeViewText;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Which Storage recipe types recipe viewers show, and the text their pages use. Free of JEI classes so the server-side
 * recipe sync and the coverage GameTest can read it without JEI installed; the recipe viewer plugins build one category
 * per entry of {@link #categories()}.
 */
public final class StorageRecipeViews {
    public static final String FE_PER_TICK_KEY = "recipe_viewer.maxtech_storage.fe_per_tick";
    public static final String TOTAL_FE_KEY = "recipe_viewer.maxtech_storage.total_fe";
    public static final String RESEARCH_KEY = "recipe_viewer.maxtech_storage.research";

    private StorageRecipeViews() {
    }

    /** One viewer category: a processing machine and the recipe type it runs. */
    public record Category(MachineKind kind) {
        public Identifier id() {
            return MaxtechStorage.id(kind.id());
        }

        public RecipeType<?> recipeType() {
            return MachineContent.recipeType(kind);
        }

        public String titleKey() {
            return RecipeViewText.titleKey(id());
        }
    }

    /** Every category, in machine order; after registration. */
    public static List<Category> categories() {
        List<Category> all = new ArrayList<>();
        for (MachineKind kind : MachineKind.values()) {
            if (kind.processes()) {
                all.add(new Category(kind));
            }
        }
        return all;
    }

    /** The English title of a category's page, for datagen. */
    public static String englishTitle(MachineKind kind) {
        return switch (kind) {
            case LITHOGRAPH -> "Lithography";
            case FABRICATOR -> "Fabricating";
            case ASSEMBLER -> "Assembling";
            case FUEL_GENERATOR -> "Fuel Generator";
        };
    }
}
