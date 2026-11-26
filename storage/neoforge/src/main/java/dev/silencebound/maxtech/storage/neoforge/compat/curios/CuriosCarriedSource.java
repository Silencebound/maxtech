package dev.silencebound.maxtech.storage.neoforge.compat.curios;

import dev.silencebound.maxtech.lib.api.carried.CarriedItemMatcher;
import dev.silencebound.maxtech.lib.api.carried.CarriedItemSource;
import dev.silencebound.maxtech.lib.api.carried.CarriedItems;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosCapability;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * Lets {@link CarriedItems} find Storage's gadgets in Curios slots: the {@link CuriosSlots#SEARCHED} slot types, in
 * order, skipping slots Curios has switched off. The Curios handler is looked up on every call and nothing is kept per
 * player, so the source holds no state (nothing to clear on logout, respawn or clone, and no thread concerns). The
 * lookup is cheap (Curios 15 sources): NeoForge's entity capability dispatch, two map gets for the entity type's slot
 * types, and one small {@code CurioInventoryCapability} wrapper over the player's {@code curios:inventory} attachment;
 * the slot search after it is one map get per slot type and allocates nothing. Only that wrapper is allocated, only
 * with Curios installed, and only on the lookups Storage makes (a refill pass per player per second, a pickup with a
 * full inventory, a posted notice). Installed once during mod construction, only with Curios loaded. Server thread.
 */
public final class CuriosCarriedSource implements CarriedItemSource {
    private CuriosCarriedSource() {
    }

    /** Registers the source with Lib's finder. */
    public static void install() {
        CarriedItems.register(new CuriosCarriedSource());
    }

    @Override
    public <C> ItemStack find(Player player, CarriedItemMatcher<C> matcher, C context) {
        ICuriosItemHandler handler = handler(player);
        if (handler == null) {
            return ItemStack.EMPTY;
        }
        Map<String, ICurioStacksHandler> curios = handler.getCurios();
        for (int t = 0; t < CuriosSlots.SEARCHED.length; t++) {
            ICurioStacksHandler type = curios.get(CuriosSlots.SEARCHED[t]);
            if (type == null) {
                continue;
            }
            IDynamicStackHandler stacks = type.getStacks();
            NonNullList<Boolean> active = type.getActiveStates();
            int slots = stacks.getSlots();
            for (int i = 0; i < slots; i++) {
                if (i < active.size() && !active.get(i)) {
                    continue;
                }
                ItemStack stack = stacks.getStackInSlot(i);
                if (!stack.isEmpty() && matcher.matches(player, stack, context)) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public <C> Optional<CarriedSlot> locate(Player player, CarriedItemMatcher<C> matcher, C context) {
        ICuriosItemHandler handler = handler(player);
        if (handler == null) {
            return Optional.empty();
        }
        Map<String, ICurioStacksHandler> curios = handler.getCurios();
        for (int t = 0; t < CuriosSlots.SEARCHED.length; t++) {
            ICurioStacksHandler type = curios.get(CuriosSlots.SEARCHED[t]);
            if (type == null) {
                continue;
            }
            IDynamicStackHandler stacks = type.getStacks();
            NonNullList<Boolean> active = type.getActiveStates();
            for (int i = 0; i < stacks.getSlots(); i++) {
                if (i < active.size() && !active.get(i)) {
                    continue;
                }
                ItemStack stack = stacks.getStackInSlot(i);
                if (!stack.isEmpty() && matcher.matches(player, stack, context)) {
                    return Optional.of(new CurioSlot(player, CuriosSlots.SEARCHED[t], i));
                }
            }
        }
        return Optional.empty();
    }

    /** {@code player}'s Curios handler, or null when Curios gives the player no slots. */
    // Curios declares the capability's context as plain Void; NeoForge's no-context overload wants @Nullable Void.
    @SuppressWarnings("NullAway")
    static @Nullable ICuriosItemHandler handler(Player player) {
        return player.getCapability(CuriosCapability.INVENTORY);
    }

    /** Slot {@code index} of {@code player}'s Curios slot type {@code type}, read again on each use. */
    record CurioSlot(Player player, String type, int index) implements CarriedSlot {
        @Override
        public ItemStack get() {
            ICuriosItemHandler handler = handler(player);
            ICurioStacksHandler stacks = handler == null ? null : handler.getCurios().get(type);
            return stacks != null && index < stacks.getStacks().getSlots()
                    ? stacks.getStacks().getStackInSlot(index)
                    : ItemStack.EMPTY;
        }
    }
}
