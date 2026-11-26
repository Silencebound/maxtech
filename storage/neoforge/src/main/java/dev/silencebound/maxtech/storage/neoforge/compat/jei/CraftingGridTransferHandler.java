package dev.silencebound.maxtech.storage.neoforge.compat.jei;

import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenu;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenus;
import dev.silencebound.maxtech.lib.platform.Platform;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.RecipeTransferResult;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jspecify.annotations.Nullable;

/**
 * JEI's "+" button for crafting recipes on a {@link CraftingGridMenu}: sends each grid cell's acceptable stacks to the
 * server ({@link CraftingGridMenus.Transfer}), which fills the grid. The client cannot see the menu's ingredient
 * source, so the check pass never reports missing items; the server places what it finds.
 */
final class CraftingGridTransferHandler<M extends AbstractContainerMenu & CraftingGridMenu>
        implements
            IRecipeTransferHandler<M, RecipeHolder<CraftingRecipe>> {
    private final CraftingGridMenus.Entry<M> entry;

    CraftingGridTransferHandler(CraftingGridMenus.Entry<M> entry) {
        this.entry = entry;
    }

    @Override
    public Class<? extends M> getContainerClass() {
        return entry.menuClass();
    }

    @Override
    public Optional<MenuType<M>> getMenuType() {
        return Optional.of(entry.menuType().get());
    }

    @Override
    public IRecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
        return RecipeTypes.CRAFTING;
    }

    @Override
    public @Nullable IRecipeTransferError transferRecipe(
            IRecipeTransferContext<RecipeHolder<CraftingRecipe>, M> context, boolean doTransfer) {
        if (doTransfer) {
            send(context.getContainer(), context.getRecipeSlots(), context.isMaxTransfer());
            context.completeRecipeTransfer(RecipeTransferResult.SUCCESS);
        }
        return null;
    }

    @Override
    @Deprecated(forRemoval = true) // JEI still declares it abstract; the context overload above is what JEI calls
    @SuppressWarnings("removal")
    public @Nullable IRecipeTransferError transferRecipe(M container, RecipeHolder<CraftingRecipe> recipe,
            IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer, boolean doTransfer) {
        if (doTransfer) {
            send(container, recipeSlots, maxTransfer);
        }
        return null;
    }

    private static void send(AbstractContainerMenu container, IRecipeSlotsView recipeSlots, boolean maxTransfer) {
        Platform.get().network()
                .sendToServer(new CraftingGridMenus.Transfer(container.containerId, cells(recipeSlots), maxTransfer));
    }

    /** JEI's crafting category lays out all nine input slots, row by row, empty ones included. */
    static List<List<ItemStack>> cells(IRecipeSlotsView recipeSlots) {
        List<IRecipeSlotView> inputs = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
        List<List<ItemStack>> cells = new ArrayList<>(CraftingGridMenu.GRID_SIZE);
        for (int cell = 0; cell < CraftingGridMenu.GRID_SIZE; cell++) {
            List<ItemStack> stacks = new ArrayList<>();
            if (cell < inputs.size()) {
                IRecipeSlotView view = inputs.get(cell);
                // The shown stack first, so the player's visible choice wins.
                view.getDisplayedItemStack().ifPresent(stack -> stacks.add(stack.copyWithCount(1)));
                view.getItemStacks().forEach(stack -> {
                    if (stacks.size() < CraftingGridMenus.MAX_CANDIDATES && !stack.isEmpty()) {
                        stacks.add(stack.copyWithCount(1));
                    }
                });
            }
            cells.add(stacks);
        }
        return cells;
    }
}
