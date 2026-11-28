package dev.silencebound.maxtech.lib.api.inventory;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * A fixed number of item slots, each with a {@link SlotRole}, an optional filter and a size limit, made by
 * {@link #builder(int)} and implemented by Lib only. Slots hold at most {@link #limit(int)} items, and never more than
 * an item's own stack size.
 * <p>
 * The methods here are the machine's own access: they ignore roles (a machine puts results into its output slots), but
 * {@link #insert} honours filters and limits. Each change is its own committed step, so they must not be called while a
 * NeoForge transfer transaction is open on the thread (an {@link IllegalStateException}). Players and automation go
 * through Lib's menus and {@code lib.neoforge.api.inventory.Handlers}, which follow the roles and take part in
 * transactions. Server thread only, like the block entity that owns the slots; a client menu builds its own empty copy
 * from the same builder.
 * <p>
 * Filters get a stack of one, which they must not change or keep; they should depend on the item and its components
 * only, since the slots reuse the stack for the same item.
 * <p>
 * The change listener gets the index of each slot that changed, once per change and never for a simulation or a
 * transaction that was rolled back: at once for the methods here, when the root transaction commits for changes made
 * through a handler. A change to {@link #overflow()} reports {@code -1}. It typically calls {@code setChanged()} on the
 * block entity.
 */
@ApiStatus.NonExtendable
public interface ItemSlots {
    /** A builder of {@code size} slots, each an {@link SlotRole#INPUT} without a filter until set otherwise. */
    static Builder builder(int size) {
        return new Builder(size);
    }

    /** The number of slots, fixed. */
    int size();

    /** The role of {@code slot}. */
    SlotRole role(int slot);

    /** Whether {@code slot}'s filter takes {@code stack}'s item (role and room aside); false for an empty stack. */
    boolean accepts(int slot, ItemStack stack);

    /** The most items {@code slot} holds of any item, before the item's own stack size. */
    int limit(int slot);

    /**
     * The live stack in {@code slot}, possibly {@link ItemStack#EMPTY}: read it, never change or keep it. Allocates
     * nothing.
     */
    ItemStack stack(int slot);

    /**
     * Replaces {@code slot}'s contents with a copy of {@code stack}, checking neither filter nor limit. Throws
     * {@link IllegalStateException} while the slot is {@link #unknown}.
     */
    void set(int slot, ItemStack stack);

    /**
     * Whether {@code slot} holds a saved item that no longer loads, wholly (its mod was removed, or the mod of one of
     * its components). The slot then reads as empty, takes nothing, is invisible to automation and menus, and saves the
     * item back exactly as it was saved, so it returns with its mod; a menu or tooltip can show it as unknown.
     */
    boolean unknown(int slot);

    /** Throws away {@code slot}'s {@link #unknown} item for good, leaving the slot empty; does nothing otherwise. */
    void discardUnknown(int slot);

    /**
     * Stacks a save held for slots this inventory no longer has (the machine shrank), or more than one for a slot:
     * unmodifiable and live, empty almost always. Automation and menus never see them; the owner hands them out with
     * {@link #takeOverflow}, and {@link #dropContents} drops them with the slots; they are saved until then.
     */
    List<ItemStack> overflow();

    /**
     * Removes and returns {@code index} of {@link #overflow()}, which the caller now owns, and calls the change
     * listener (with {@code -1}), so the next save no longer holds the stack. Refused inside a transaction.
     */
    ItemStack takeOverflow(int index);

    /**
     * Removes and returns every stack in the slots and in {@link #overflow()}, which the caller now owns (to drop or
     * put into the block's item), and calls the change listener for each slot that held one ({@code -1} for overflow).
     * Refused inside a transaction. {@link #unknown} items stay where they are: they cannot be dropped or put into an
     * item, so they vanish when the block is broken. Keeping unknown items applies while the block exists.
     */
    List<ItemStack> contentsToDrop();

    /**
     * Drops {@link #contentsToDrop()} at {@code pos}, as a block does when it is broken (call it from
     * {@code preRemoveSideEffects}), so no slot and no overflow is forgotten. {@link #unknown} items are not dropped
     * and vanish with the block, as do a tank's unknown contents.
     */
    void dropContents(Level level, BlockPos pos);

    /**
     * Puts up to {@code stack.getCount()} of {@code stack}'s item into {@code slot} if its filter takes it and the slot
     * is empty or holds the same item and components; returns how many went in (all of them when simulating, if they
     * fit). {@code stack} itself is not changed.
     */
    int insert(int slot, ItemStack stack, boolean simulate);

    /**
     * Takes up to {@code amount} of what is in {@code slot}; returns how many items came out (read {@link #stack} first
     * to know which).
     */
    int extract(int slot, int amount, boolean simulate);

    /** Saves the slots into {@code output}, a child the owner names (e.g. {@code output.child("slots")}). */
    void save(ValueOutput output);

    /**
     * Loads saved slots from {@code input}, replacing all contents, slot by slot: a stack that no longer loads stays
     * {@link #unknown} in its own slot without moving any other, and stacks for slots this inventory no longer has go
     * to {@link #overflow()}; nothing saved is lost. Does not call the change listener.
     */
    void load(ValueInput input);

    /**
     * The shape of a set of slots: roles, filters and limits, reusable for any number of {@link #build} calls (a block
     * entity's slots on the server and the empty mirror of a client menu). Set up on one thread, then immutable in use.
     */
    final class Builder {
        private final SlotRole[] roles;
        private final @Nullable Predicate<ItemStack>[] filters;
        private final int[] limits;

        @SuppressWarnings("unchecked") // a generic array of filters, only ever filled with filters
        private Builder(int size) {
            if (size < 1) {
                throw new IllegalArgumentException("Need at least one slot, got " + size);
            }
            roles = new SlotRole[size];
            Arrays.fill(roles, SlotRole.INPUT);
            filters = new Predicate[size];
            limits = new int[size];
            Arrays.fill(limits, Item.ABSOLUTE_MAX_STACK_SIZE);
        }

        private Builder(Builder from) {
            roles = from.roles.clone();
            filters = from.filters.clone();
            limits = from.limits.clone();
        }

        /** The number of slots the shape has. */
        public int size() {
            return roles.length;
        }

        /** A builder of the same shape that later changes to this one do not reach. */
        public Builder copy() {
            return new Builder(this);
        }

        /** Slots {@code from} (inclusive) to {@code to} (exclusive) are inputs taking any item. */
        public Builder input(int from, int to) {
            return role(from, to, SlotRole.INPUT, null);
        }

        /** Slots {@code from} (inclusive) to {@code to} (exclusive) are inputs taking what {@code filter} accepts. */
        public Builder input(int from, int to, Predicate<ItemStack> filter) {
            return role(from, to, SlotRole.INPUT, Objects.requireNonNull(filter, "filter"));
        }

        /** Slots {@code from} (inclusive) to {@code to} (exclusive) are outputs. */
        public Builder output(int from, int to) {
            return role(from, to, SlotRole.OUTPUT, null);
        }

        /** Slots {@code from} (inclusive) to {@code to} (exclusive) are display slots. */
        public Builder display(int from, int to) {
            return role(from, to, SlotRole.DISPLAY, null);
        }

        /** Slots {@code from} (inclusive) to {@code to} (exclusive) hold at most {@code limit} items, 1 to 99. */
        public Builder limit(int from, int to, int limit) {
            check(from, to);
            if (limit < 1 || limit > Item.ABSOLUTE_MAX_STACK_SIZE) {
                throw new IllegalArgumentException("Slot limit must be 1 to 99, got " + limit);
            }
            Arrays.fill(limits, from, to, limit);
            return this;
        }

        /**
         * New slots of this shape, all empty, reporting changes to {@code onChange} (the index of the changed slot).
         */
        public ItemSlots build(IntConsumer onChange) {
            Objects.requireNonNull(onChange, "onChange");
            return InventoryFactory.get().items(roles.clone(), filters.clone(), limits.clone(), onChange);
        }

        private Builder role(int from, int to, SlotRole role, @Nullable Predicate<ItemStack> filter) {
            check(from, to);
            Arrays.fill(roles, from, to, role);
            Arrays.fill(filters, from, to, filter);
            return this;
        }

        private void check(int from, int to) {
            if (from < 0 || to > roles.length || from >= to) {
                throw new IndexOutOfBoundsException(
                        "Slots " + from + " to " + to + " outside 0 to " + roles.length + " or empty");
            }
        }
    }
}
