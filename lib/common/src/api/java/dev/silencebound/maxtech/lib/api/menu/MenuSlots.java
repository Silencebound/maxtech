package dev.silencebound.maxtech.lib.api.menu;

/**
 * Handle of a run of menu slots over one {@link MenuItems}: slots {@link #from} (inclusive) to {@link #to} (exclusive),
 * declared with {@link MenuDefinition.Builder#slots}. A {@link Layout#slots} node places it, and
 * {@link MenuDefinition.Builder#shiftClick} names it as a target of shift-clicks; roles come from the inventory's
 * {@link dev.silencebound.maxtech.lib.api.inventory.SlotRole}s. Immutable; any thread.
 */
public final class MenuSlots {
    private final int index;
    private final MenuItems<?> items;
    private final int from;
    private final int to;

    MenuSlots(int index, MenuItems<?> items, int from, int to) {
        this.index = index;
        this.items = items;
        this.from = from;
        this.to = to;
    }

    /** Position among the definition's slot groups, from 0 in declaration order; the menu adds them in this order. */
    public int index() {
        return index;
    }

    /** The inventory the slots show. */
    public MenuItems<?> items() {
        return items;
    }

    /** The first slot of the inventory in the group. */
    public int from() {
        return from;
    }

    /** One past the last slot of the inventory in the group. */
    public int to() {
        return to;
    }

    /** The number of slots. */
    public int size() {
        return to - from;
    }
}
