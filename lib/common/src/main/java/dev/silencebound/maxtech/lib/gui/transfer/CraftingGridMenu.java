package dev.silencebound.maxtech.lib.gui.transfer;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * A container menu with a 3x3 crafting grid that recipe viewers can fill (JEI's "+" button). Implement it on an
 * {@code AbstractContainerMenu} and list the menu with {@link CraftingGridMenus#register}; Lib's JEI plugin then sends
 * the clicked crafting recipe's ingredients, and the server hands them to {@link #receiveRecipe}. The default fills the
 * grid with real items from {@link #ingredientSource} and the player's inventory ({@link CraftingGridFill}); a pattern
 * terminal overrides it to write the stacks as a pattern instead.
 */
public interface CraftingGridMenu {
    /** Number of grid slots: 3 by 3, row by row. */
    int GRID_SIZE = 9;

    /** The menu slot index of grid cell {@code cell} (0 to 8, row by row). */
    int gridSlot(int cell);

    /** Where ingredients come from first and cleared grid items go first; {@link IngredientSource#NONE} for none. */
    default IngredientSource ingredientSource(ServerPlayer player) {
        return IngredientSource.NONE;
    }

    /**
     * Server: a recipe viewer asks for these ingredients, one list of acceptable stacks per grid cell (empty for an
     * empty cell; the stacks come from the client, so treat them as wishes). {@code maxTransfer} asks for as many sets
     * as fit. Called only for the player's open, valid menu.
     */
    default void receiveRecipe(ServerPlayer player, List<List<ItemStack>> cells, boolean maxTransfer) {
        CraftingGridFill.fill(this, player, cells, maxTransfer);
    }
}
