/**
 * Recipe transfer into crafting grids: a menu implements
 * {@link dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenu} and registers with
 * {@link dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenus}; Lib's JEI plugin then offers its "+" button for
 * crafting recipes and the server fills the grid from the menu's
 * {@link dev.silencebound.maxtech.lib.gui.transfer.IngredientSource} and the player's inventory. Server-safe.
 */
@NullMarked
package dev.silencebound.maxtech.lib.gui.transfer;

import org.jspecify.annotations.NullMarked;
