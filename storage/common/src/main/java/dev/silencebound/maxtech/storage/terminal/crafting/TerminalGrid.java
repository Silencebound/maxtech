package dev.silencebound.maxtech.storage.terminal.crafting;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The crafting terminal's 3 x 3 grid: real items the terminal holds, saved with the block or part and dropped when it
 * is broken. Every open menu shows the same grid; a change raises {@link #version()}, which each menu compares in its
 * broadcast to work out its result again, so the grid keeps no list of menus that could outlive them. On the client a
 * grid without a host mirrors the server's through the menu's slots. Server or client thread, never both.
 */
public final class TerminalGrid implements CraftingContainer {
    /** Cells: 3 by 3, row by row. */
    public static final int SIZE = 9;
    private static final String ITEMS = "craft_grid";

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private final Runnable changed;
    private int version;

    /**
     * @param changed
     *            marks the host for saving; called after every change
     */
    public TerminalGrid(Runnable changed) {
        this.changed = changed;
    }

    /** A grid without a host: the client's mirror. */
    public static TerminalGrid detached() {
        return new TerminalGrid(() -> {
        });
    }

    /** Raised on every change. */
    public int version() {
        return version;
    }

    @Override
    public int getWidth() {
        return 3;
    }

    @Override
    public int getHeight() {
        return 3;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < SIZE; i++) {
            if (!items.get(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot < 0 || slot >= SIZE ? ItemStack.EMPTY : items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, count);
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = ContainerHelper.takeItem(items, slot);
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        setChanged();
    }

    /** Also called by slots after they changed a stack in place. */
    @Override
    public void setChanged() {
        version++;
        changed.run();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    @Override
    public List<ItemStack> getItems() {
        return List.copyOf(items);
    }

    @Override
    public void fillStackedContents(StackedItemContents contents) {
        for (ItemStack stack : items) {
            contents.accountSimpleStack(stack);
        }
    }

    /** Takes every stack out, for dropping them when the terminal is broken. */
    public List<ItemStack> takeAll() {
        List<ItemStack> taken = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                taken.add(stack);
                items.set(i, ItemStack.EMPTY);
            }
        }
        if (!taken.isEmpty()) {
            setChanged();
        }
        return taken;
    }

    public void save(ValueOutput output) {
        if (!isEmpty()) {
            ContainerHelper.saveAllItems(output.child(ITEMS), items, false);
        }
    }

    public void load(ValueInput input) {
        items.clear();
        ContainerHelper.loadAllItems(input.childOrEmpty(ITEMS), items);
        version++;
    }
}
