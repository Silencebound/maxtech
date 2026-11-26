package dev.silencebound.maxtech.lib.gui.transfer;

import net.minecraft.world.item.ItemStack;

/**
 * Where a recipe transfer takes ingredients from besides the player's inventory, and where items cleared out of the
 * grid go first: a storage terminal hands out its network. Server thread; implementations move real items (inside the
 * network's own transaction handling) and report only what actually moved.
 */
public interface IngredientSource {
    /** A source with nothing in it and no room. */
    IngredientSource NONE = new IngredientSource() {
        @Override
        public ItemStack extract(ItemStack like, int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insert(ItemStack stack) {
            return stack;
        }
    };

    /**
     * Takes up to {@code amount} items of the same item and components as {@code like}; returns what it took (empty if
     * none). Must not change {@code like}.
     */
    ItemStack extract(ItemStack like, int amount);

    /** Stores {@code stack}; returns what did not fit (empty if all did). May not keep a reference to the argument. */
    ItemStack insert(ItemStack stack);
}
