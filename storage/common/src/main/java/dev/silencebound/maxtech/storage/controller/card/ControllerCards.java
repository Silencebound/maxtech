package dev.silencebound.maxtech.storage.controller.card;

import dev.silencebound.maxtech.storage.controller.ClusterStats;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The upgrade card slots of a controller's primary block (M7R item 2): {@link ClusterStats.Cards#MOST} slots of one
 * card each, of which the first {@link #usable()} work. The rest only hold cards until {@link #rehome} moves them into
 * free usable slots or drops them, so a card is never lost or doubled when the multiblock shrinks. {@link #setChanged}
 * tells the owner, which recounts its stats; nothing here runs per tick. Server thread for the primary's slots; a
 * client menu has a plain copy.
 */
public final class ControllerCards extends SimpleContainer {
    private static final String CARDS = "cards";
    private final Runnable changed;
    private int usable;

    /** Slots that call {@code changed} whenever a card goes in or out. */
    public ControllerCards(Runnable changed) {
        super(ClusterStats.Cards.MOST);
        this.changed = changed;
    }

    /** The slots that work now, from 0 to {@link ClusterStats.Cards#MOST}. */
    public int usable() {
        return usable;
    }

    /** Sets the slots that work; call {@link #rehome} next so no card stays in a slot that does not. */
    public void setUsable(int slots) {
        usable = Math.max(0, Math.min(getContainerSize(), slots));
    }

    /** Installed cards of {@code kind} in the usable slots, the {@code except} slot left out (-1 for none). */
    public int count(CardKind kind, int except) {
        int count = 0;
        for (int i = 0; i < usable; i++) {
            if (i != except && CardContent.kind(getItem(i)) == kind) {
                count++;
            }
        }
        return count;
    }

    /** Installed cards in the usable slots. */
    public int installed() {
        int count = 0;
        for (int i = 0; i < usable; i++) {
            if (!getItem(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether slot {@code slot} of {@code usable} working slots takes {@code stack}: a card, in a working slot, while
     * fewer than {@code limit} cards of its kind sit in the other working slots.
     */
    public static boolean allows(Container slots, int usable, int slot, ItemStack stack,
            ToIntFunction<CardKind> limit) {
        CardKind kind = CardContent.kind(stack);
        if (kind == null || slot < 0 || slot >= usable) {
            return false;
        }
        int count = 0;
        for (int i = 0; i < usable && i < slots.getContainerSize(); i++) {
            if (i != slot && CardContent.kind(slots.getItem(i)) == kind) {
                count++;
            }
        }
        return count < limit.applyAsInt(kind);
    }

    /** {@link #allows} for these slots with the config's limits. */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        ClusterStats.Cards limits = ClusterStats.Cards.fromConfig();
        return allows(this, usable, slot, stack, limits::limit);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        changed.run();
    }

    /**
     * Puts one card of {@code stack} into the first usable slot that takes it under {@code limits} and returns what is
     * left: {@code stack} less one card, or {@code stack} itself when no slot took it.
     */
    public ItemStack insert(ItemStack stack, ClusterStats.Cards limits) {
        for (int i = 0; i < usable; i++) {
            if (getItem(i).isEmpty() && allows(this, usable, i, stack, limits::limit)) {
                setItem(i, stack.split(1));
                return stack;
            }
        }
        return stack;
    }

    /**
     * Moves every card in a slot that does not work into a free usable slot that takes it, and hands the ones no slot
     * takes to {@code drop}; each card goes exactly one way. Calls {@link #setChanged} once when anything moved.
     */
    public void rehome(ClusterStats.Cards limits, Consumer<ItemStack> drop) {
        boolean moved = false;
        for (int i = usable; i < getContainerSize(); i++) {
            ItemStack stack = getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            moved = true;
            getItems().set(i, ItemStack.EMPTY);
            int to = -1;
            for (int j = 0; j < usable && to < 0; j++) {
                if (getItem(j).isEmpty() && allows(this, usable, j, stack, limits::limit)) {
                    to = j;
                }
            }
            if (to >= 0) {
                getItems().set(to, stack);
            } else {
                drop.accept(stack);
            }
        }
        if (moved) {
            setChanged();
        }
    }

    /**
     * Takes every card out (for a drop or a handover) and hands each to {@code out}; the slots are empty afterwards.
     * Calls {@link #setChanged} once when there was any.
     */
    public void takeAll(Consumer<ItemStack> out) {
        boolean any = false;
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack stack = getItem(i);
            if (!stack.isEmpty()) {
                any = true;
                getItems().set(i, ItemStack.EMPTY);
                out.accept(stack);
            }
        }
        if (any) {
            setChanged();
        }
    }

    /** Saves the cards with their slots as a child of {@code output}; nothing when there are none. */
    public void save(ValueOutput output) {
        if (!isEmpty()) {
            ContainerHelper.saveAllItems(output.child(CARDS), getItems(), false);
        }
    }

    /** Loads the cards saved by {@link #save}; no {@link #setChanged}. */
    public void load(ValueInput input) {
        getItems().clear();
        input.child(CARDS).ifPresent(cards -> ContainerHelper.loadAllItems(cards, getItems()));
    }
}
