package dev.silencebound.maxtech.storage.neoforge.compat.jei;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternFaces;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTransfer;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import mezz.jei.api.recipe.vanilla.IJeiBrewingRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jspecify.annotations.Nullable;

/**
 * JEI's "+" button on the pattern terminal (its own menu type of {@link TerminalMenu}) for every recipe type: sends the
 * recipe's inputs and outputs, each as its shown item or fluid with its amount and the tag it came from, and the recipe
 * type and id when JEI shows a vanilla recipe holder (brewing gets JEI's brewing id). The server writes them into the
 * grid as a processing draft; nothing is checked on the client, so it never reports an error.
 */
final class PatternTransferHandler implements IUniversalRecipeTransferHandler<TerminalMenu> {
    @Override
    public Class<? extends TerminalMenu> getContainerClass() {
        return TerminalMenu.class;
    }

    @Override
    public Optional<MenuType<TerminalMenu>> getMenuType() {
        return Optional.of(ModMenus.PATTERN_TERMINAL.get());
    }

    @Override
    public @Nullable IRecipeTransferError transferRecipe(TerminalMenu container, Object recipe,
            IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer, boolean doTransfer) {
        if (doTransfer) {
            Optional<Identifier> type = Optional.empty();
            Optional<Identifier> id = Optional.empty();
            if (recipe instanceof RecipeHolder<?> holder) {
                type = Optional.ofNullable(BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()));
                id = Optional.of(holder.id().identifier());
            } else if (recipe instanceof IJeiBrewingRecipe) {
                type = Optional.of(PatternFaces.BREWING);
            }
            Platform.get().network()
                    .sendToServer(new PatternTransfer(container.containerId, type, id,
                            entries(recipeSlots.getSlotViews(RecipeIngredientRole.INPUT)),
                            entries(recipeSlots.getSlotViews(RecipeIngredientRole.OUTPUT))));
        }
        return null;
    }

    private static List<PatternTransfer.Entry> entries(List<IRecipeSlotView> views) {
        List<PatternTransfer.Entry> entries = new ArrayList<>();
        for (IRecipeSlotView view : views) {
            if (entries.size() >= PatternTransfer.MAX_ENTRIES) {
                break;
            }
            Optional<ITypedIngredient<?>> shown = view.getDisplayedIngredient();
            if (shown.isEmpty()) {
                continue;
            }
            Optional<Identifier> tag = view.getTagKey().map(TagKey::location);
            Object ingredient = shown.get().getIngredient();
            if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
                entries.add(new PatternTransfer.Entry(ItemKey.of(stack.copyWithCount(1)), stack.getCount(), tag, 1f));
            } else if (ingredient instanceof FluidStack fluid && !fluid.isEmpty()) {
                entries.add(new PatternTransfer.Entry(FluidKey.of(fluid.getFluid(), fluid.getComponentsPatch()),
                        fluid.getAmount(), tag, 1f));
            }
        }
        return entries;
    }
}
