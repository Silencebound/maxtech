package dev.silencebound.maxtech.storage.terminal.filter;

import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.storage.terminal.TerminalLayout;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * The view filter slots of an item terminal opened from a carried slot (#322): a wireless terminal or portable cell,
 * whose filters live in its {@link CarriedFilters} component. The slots are read from the item once, when the terminal
 * menu opens, and every change is written back to the item at once, so the item always holds what the slots show.
 *
 * <p>
 * The slots stay bound to the very stack they were read from: once the slot holds another stack (the hands were
 * swapped, the item was dropped, picked up or replaced by a copy) they refuse every click and write nothing, and the
 * terminal's source closes the menu. A filter is therefore never in the slots and in the item twice, and never written
 * into another item. Server thread; the client's menu uses a plain {@link SimpleContainer} in their place.
 */
public final class CarriedViewFilters extends SimpleContainer {
    private final CarriedSlot slot;
    private final ItemStack stack;
    /** Entries that did not fit or did not make a stack, written back unchanged. */
    private final List<CarriedFilters.Entry> unplaced = new ArrayList<>();
    private final CarriedFilters loaded;

    /** The filters of the stack in {@code slot} now. */
    public CarriedViewFilters(CarriedSlot slot) {
        super(TerminalLayout.FILTER_SLOTS);
        this.slot = slot;
        this.stack = slot.get();
        this.loaded = stack.getOrDefault(ViewFilterContent.CARRIED.get(), CarriedFilters.EMPTY);
        for (CarriedFilters.Entry entry : loaded.entries()) {
            ItemStack filter = entry.stack().create();
            int at = entry.slot() < getContainerSize() && getItems().get(entry.slot()).isEmpty()
                    ? entry.slot()
                    : firstFree();
            if (filter.isEmpty() || at < 0) {
                unplaced.add(entry);
            } else {
                getItems().set(at, filter);
            }
        }
    }

    private int firstFree() {
        for (int i = 0; i < getContainerSize(); i++) {
            if (getItems().get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** Whether the slot still holds the stack the filters were read from; only then do the slots take clicks. */
    @SuppressWarnings("ReferenceEquality") // the very stack the filters were read from, not an equal copy
    public boolean usable() {
        ItemStack now = slot.get();
        return now == stack && !now.isEmpty();
    }

    @Override
    public boolean stillValid(Player player) {
        return usable();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack filter) {
        return ViewFilterItem.is(filter) && usable();
    }

    /** Writes the slots back to the item; nothing once the item left its slot. */
    @Override
    public void setChanged() {
        super.setChanged();
        if (!usable()) {
            return;
        }
        List<CarriedFilters.Entry> entries = new ArrayList<>(unplaced);
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack filter = getItem(i);
            if (!filter.isEmpty()) {
                entries.add(new CarriedFilters.Entry(i, ItemStackTemplate.fromNonEmptyStack(filter)));
            }
        }
        CarriedFilters next = new CarriedFilters(entries, loaded.kept());
        if (next.isEmpty()) {
            stack.remove(ViewFilterContent.CARRIED.get());
        } else {
            stack.set(ViewFilterContent.CARRIED.get(), next);
        }
    }

    /** The filters {@code stack} carries; empty for one without any. */
    public static CarriedFilters of(ItemStack stack) {
        return stack.getOrDefault(ViewFilterContent.CARRIED.get(), CarriedFilters.EMPTY);
    }
}
