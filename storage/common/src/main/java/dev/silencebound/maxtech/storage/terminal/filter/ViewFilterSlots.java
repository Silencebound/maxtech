package dev.silencebound.maxtech.storage.terminal.filter;

import dev.silencebound.maxtech.storage.terminal.TerminalLayout;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A terminal block's or part's view filter slots (B09 item 24): {@link TerminalLayout#FILTER_SLOTS} slots that hold one
 * view filter each, saved with the host by slot and dropped when it is broken. The server never reads the filters: the
 * slots reach the client through the terminal menu's slot sync and the client filters its own copy of the list. Server
 * thread; the client's menu uses a plain {@link SimpleContainer} in their place.
 */
public final class ViewFilterSlots extends SimpleContainer {
    private static final String ITEMS = "view_filters";

    private final Runnable changed;

    /**
     * @param changed
     *            marks the host for saving; called after every change
     */
    public ViewFilterSlots(Runnable changed) {
        super(TerminalLayout.FILTER_SLOTS);
        this.changed = changed;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        changed.run();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return ViewFilterItem.is(stack);
    }

    /** Takes every filter out, for dropping them when the terminal is broken. */
    public List<ItemStack> takeAll() {
        List<ItemStack> taken = new ArrayList<>();
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack stack = getItem(i);
            if (!stack.isEmpty()) {
                taken.add(stack);
                getItems().set(i, ItemStack.EMPTY);
            }
        }
        if (!taken.isEmpty()) {
            setChanged();
        }
        return taken;
    }

    public void save(ValueOutput output) {
        if (!isEmpty()) {
            ContainerHelper.saveAllItems(output.child(ITEMS), getItems(), false);
        }
    }

    public void load(ValueInput input) {
        getItems().clear();
        ContainerHelper.loadAllItems(input.childOrEmpty(ITEMS), getItems());
    }

    /**
     * A slot of a terminal menu's filter strip: one view filter, nothing else. It asks its container too, so an item
     * terminal's slots ({@link CarriedViewFilters}) refuse clicks once the item left the slot it was opened from.
     */
    public static final class FilterSlot extends Slot {
        public FilterSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return ViewFilterItem.is(stack) && container.canPlaceItem(getContainerSlot(), stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return container.stillValid(player) && super.mayPickup(player);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
