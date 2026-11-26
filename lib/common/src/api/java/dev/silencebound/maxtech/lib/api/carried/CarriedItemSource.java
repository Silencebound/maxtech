package dev.silencebound.maxtech.lib.api.carried;

import java.util.Optional;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Slots a player carries items in besides the vanilla inventory, such as an accessory mod's. Register one with
 * {@link CarriedItems#register}; {@link CarriedItems} asks each source after the inventory. Both methods are called on
 * the server thread (see {@link CarriedItems}) and may run often (once per refill pass or picked-up item): look only at
 * the slots, keep no per-player state, allocate as little as the other mod's API allows in {@link #find}, and never
 * change a stack. Experimental (Lib 0.4.0): may change in any Lib minor version, also after 1.0.
 */
@ApiStatus.Experimental
public interface CarriedItemSource {
    /**
     * The first non-empty stack in this source's slots of {@code player} that {@code matcher} takes, or
     * {@link ItemStack#EMPTY}; never null. The stack is the live one: changes to it change the carried item.
     */
    <C> ItemStack find(Player player, CarriedItemMatcher<C> matcher, C context);

    /**
     * Where the stack {@link #find} would return sits, or empty when there is none; never null. May allocate; it runs
     * when a GUI opens.
     */
    <C> Optional<CarriedSlot> locate(Player player, CarriedItemMatcher<C> matcher, C context);
}
