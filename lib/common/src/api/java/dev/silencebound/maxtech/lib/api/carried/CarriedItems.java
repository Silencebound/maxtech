package dev.silencebound.maxtech.lib.api.carried;

import java.util.Optional;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Finds an item a player carries: first in the vanilla inventory, slot by slot in container order (main slots with the
 * hotbar, then armour and the offhand), then in each registered {@link CarriedItemSource} in the order they were
 * registered. With no source registered this is exactly a walk over the {@link Inventory#getContainerSize()} slots.
 * {@link #find} allocates nothing itself: pass a shared matcher and put what it needs in the context.
 * <p>
 * Threading: lookups run on the server thread only, for server players (sources are not required to be thread-safe; the
 * registry itself is). Sources are registered during mod construction. Experimental (Lib 0.4.0): Storage's gadgets are
 * the one user so far, and the shape may change in any Lib minor version, also after 1.0.
 */
@ApiStatus.Experimental
public final class CarriedItems {
    /** Takes a stack whose item is an instance of the context class. */
    private static final CarriedItemMatcher<Class<?>> ITEM_CLASS = (player, stack, type) -> type
            .isInstance(stack.getItem());

    private static volatile CarriedItemSource[] sources = new CarriedItemSource[0];

    private CarriedItems() {
    }

    /** Adds {@code source}, asked after the inventory and every source registered before it. Any thread. */
    public static synchronized void register(CarriedItemSource source) {
        CarriedItemSource[] old = sources;
        CarriedItemSource[] grown = new CarriedItemSource[old.length + 1];
        System.arraycopy(old, 0, grown, 0, old.length);
        grown[old.length] = source;
        sources = grown;
    }

    /** Removes {@code source} if it is registered; tests use it to take a fake source away again. Any thread. */
    @SuppressWarnings("ReferenceEquality") // a source is removed by identity, as it was registered
    public static synchronized void unregister(CarriedItemSource source) {
        CarriedItemSource[] old = sources;
        for (int i = 0; i < old.length; i++) {
            if (old[i] == source) {
                CarriedItemSource[] shrunk = new CarriedItemSource[old.length - 1];
                System.arraycopy(old, 0, shrunk, 0, i);
                System.arraycopy(old, i + 1, shrunk, i, old.length - i - 1);
                sources = shrunk;
                return;
            }
        }
    }

    /**
     * The first carried stack of {@code player} that {@code matcher} takes, or {@link ItemStack#EMPTY}; never null. The
     * stack is the live one. Allocates nothing itself.
     */
    public static <C> ItemStack find(Player player, CarriedItemMatcher<C> matcher, C context) {
        Inventory inventory = player.getInventory();
        int size = inventory.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && matcher.matches(player, stack, context)) {
                return stack;
            }
        }
        CarriedItemSource[] extra = sources;
        for (int i = 0; i < extra.length; i++) {
            ItemStack stack = extra[i].find(player, matcher, context);
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** The first carried stack of {@code player} whose item is an instance of {@code type}, or empty. */
    public static ItemStack find(Player player, Class<?> type) {
        return find(player, ITEM_CLASS, type);
    }

    /**
     * Where the stack {@link #find} would return sits, or empty when {@code player} carries none. A stack in a hand is
     * named by its hand (the main hand while it is the selected hotbar slot), so a GUI opened for it behaves as one
     * opened by using the item.
     */
    private static <C> Optional<CarriedSlot> locate(Player player, CarriedItemMatcher<C> matcher, C context) {
        assert !player.level().isClientSide() : "carried items are looked up on the server";
        Inventory inventory = player.getInventory();
        int size = inventory.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && matcher.matches(player, stack, context)) {
                if (slot == inventory.getSelectedSlot()) {
                    return Optional.of(CarriedSlot.hand(player, InteractionHand.MAIN_HAND));
                }
                if (slot == Inventory.SLOT_OFFHAND) {
                    return Optional.of(CarriedSlot.hand(player, InteractionHand.OFF_HAND));
                }
                return Optional.of(CarriedSlot.inventory(player, slot));
            }
        }
        CarriedItemSource[] extra = sources;
        for (int i = 0; i < extra.length; i++) {
            Optional<CarriedSlot> found = extra[i].locate(player, matcher, context);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Where the first carried stack of {@code player} whose item is an instance of {@code type} sits, or empty (never
     * null); a stack in a hand is named by its hand (see {@link CarriedSlot#hand}). Allocates the slot; for opening
     * GUIs. Server thread.
     */
    public static Optional<CarriedSlot> locate(Player player, Class<?> type) {
        return locate(player, ITEM_CLASS, type);
    }
}
