package dev.silencebound.maxtech.lib.api.carried;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Picks the stack a lookup wants. The context lets one shared matcher (a {@code static final} field) carry what the
 * lookup needs, such as the stack to file or the network to match, so a lookup allocates nothing. Experimental (Lib
 * 0.4.0): may change in any Lib minor version, also after 1.0.
 *
 * @param <C>
 *            what the matcher needs besides the player and the stack
 */
@ApiStatus.Experimental
@FunctionalInterface
public interface CarriedItemMatcher<C> {
    /**
     * Whether {@code stack}, carried by {@code player}, is the one wanted. Called on the server thread for each
     * non-empty stack until one matches; must not change the stack or the player's inventory.
     */
    boolean matches(Player player, ItemStack stack, C context);
}
