package dev.silencebound.maxtech.storage.crafting.pattern;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.jspecify.annotations.Nullable;

/**
 * Stonecutter and smithing-table recipes for patterns (B09 item 16): finding the recipe a pattern terminal grid or a
 * pattern's inputs match, and one craft of it for the crafting assembler. A pattern names its recipe; when that recipe
 * is gone or no longer matches (a datapack changed), the inputs are matched again against every recipe of the type, and
 * for the stonecutter the one giving the pattern's main output wins. Called on encode and per batch, never per tick.
 * Server thread.
 */
public final class TableRecipes {
    /** The smithing slots a pattern's inputs may fill, in order, by input count: an empty slot is left out. */
    private static final int[][][] SMITHING_PLACES = {{}, {{1}}, {{0, 1}, {1, 2}}, {{0, 1, 2}}};

    private TableRecipes() {
    }

    /** One craft, resolved: each call gives a fresh result stack. */
    @FunctionalInterface
    public interface Craft {
        ItemStack assemble();
    }

    /**
     * One stonecutting craft of {@code pattern} with {@code keys} as its inputs, or null when its single one-item input
     * matches no stonecutter recipe that gives the pattern's main output ({@link #gives}).
     */
    public static @Nullable Craft stonecutting(ServerLevel level, CraftingPattern pattern, StackKey[] keys) {
        if (keys.length != 1 || !(keys[0] instanceof ItemKey item) || pattern.inputs().getFirst().amount() != 1) {
            return null;
        }
        SingleRecipeInput input = new SingleRecipeInput(item.toStack(1));
        StonecutterRecipe named = byId(level, pattern.recipe(), StonecutterRecipe.class);
        if (named != null && named.matches(input, level) && gives(pattern, named.assemble(input))) {
            return () -> named.assemble(input);
        }
        for (RecipeHolder<StonecutterRecipe> holder : stonecutting(level, input.item())) {
            StonecutterRecipe recipe = holder.value();
            if (gives(pattern, recipe.assemble(input))) {
                return () -> recipe.assemble(input);
            }
        }
        return null;
    }

    /**
     * One smithing craft of {@code pattern} with {@code keys} as its inputs (template, base, addition, an empty one
     * left out), or null when no placement of them matches a smithing recipe (the pattern's own first) that gives the
     * pattern's main output ({@link #gives}): after a datapack change the assembler refuses rather than make something
     * else.
     */
    public static @Nullable Craft smithing(ServerLevel level, CraftingPattern pattern, StackKey[] keys) {
        if (keys.length < 1 || keys.length > 3) {
            return null;
        }
        ItemStack[] stacks = new ItemStack[keys.length];
        for (int i = 0; i < keys.length; i++) {
            if (!(keys[i] instanceof ItemKey item) || pattern.inputs().get(i).amount() != 1) {
                return null;
            }
            stacks[i] = item.toStack(1);
        }
        SmithingRecipe named = byId(level, pattern.recipe(), SmithingRecipe.class);
        for (int[] place : SMITHING_PLACES[keys.length]) {
            ItemStack[] slots = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
            for (int i = 0; i < place.length; i++) {
                slots[place[i]] = stacks[i];
            }
            SmithingRecipeInput input = new SmithingRecipeInput(slots[0], slots[1], slots[2]);
            if (named != null && named.matches(input, level) && gives(pattern, named.assemble(input))) {
                return () -> named.assemble(input);
            }
            SmithingRecipe found = smithing(level, input).map(RecipeHolder::value).orElse(null);
            if (found != null && gives(pattern, found.assemble(input))) {
                return () -> found.assemble(input);
            }
        }
        return null;
    }

    /** Every stonecutter recipe that takes {@code input}, in the stonecutter's own order. */
    public static List<RecipeHolder<StonecutterRecipe>> stonecutting(ServerLevel level, ItemStack input) {
        List<RecipeHolder<StonecutterRecipe>> out = new ArrayList<>();
        if (input.isEmpty()) {
            return out;
        }
        for (SelectableRecipe.SingleInputEntry<StonecutterRecipe> entry : level.recipeAccess().stonecutterRecipes()
                .selectByInput(input).entries()) {
            entry.recipe().recipe().ifPresent(out::add);
        }
        return out;
    }

    /** The smithing recipe {@code input} matches, if any. */
    public static Optional<RecipeHolder<SmithingRecipe>> smithing(ServerLevel level, SmithingRecipeInput input) {
        return level.recipeAccess().getRecipeFor(RecipeType.SMITHING, input, level);
    }

    /** The recipe with id {@code id} if it is a {@code type}. */
    private static <T> @Nullable T byId(ServerLevel level, Optional<Identifier> id, Class<T> type) {
        if (id.isEmpty()) {
            return null;
        }
        Optional<RecipeHolder<?>> holder = level.recipeAccess().byKey(ResourceKey.create(Registries.RECIPE, id.get()));
        return holder.isPresent() && type.isInstance(holder.get().value()) ? type.cast(holder.get().value()) : null;
    }

    /**
     * Whether {@code result} is exactly the pattern's main output: the same item with the same components, and the same
     * count.
     */
    public static boolean gives(CraftingPattern pattern, ItemStack result) {
        return !result.isEmpty() && result.getCount() == pattern.mainOutput().amount()
                && pattern.mainOutput().key().equals(ItemKey.of(result));
    }
}
