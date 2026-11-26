package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * A port's cards: real items, one per slot, speed cards and at most one crafting card (M7 C21). It always has
 * {@link #MAX_SLOTS} slots; the GUI shows the first {@code ports.speedCards.slots}, and only cards there count, so
 * lowering the config keeps the other cards in the port (and in its item) without letting them work. Server thread for
 * a port's own cards.
 */
public final class PortCards extends SimpleContainer {
    /** The most slots {@code ports.speedCards.slots} may give. */
    public static final int MAX_SLOTS = 4;

    private final Runnable onChanged;
    private int held;
    private boolean crafting;

    PortCards(Runnable onChanged) {
        super(MAX_SLOTS);
        this.onChanged = onChanged;
    }

    /** The slots a port has now ({@code ports.speedCards.slots}). */
    public static int slots() {
        return Math.min(MAX_SLOTS, Math.max(0, StorageServerConfig.PORT_SPEED_CARD_SLOTS.getAsInt()));
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return stack.is(PortContent.SPEED_CARD.get())
                || (stack.is(PortContent.CRAFTING_CARD.get()) && !holdsCraftingCardBesides(slot));
    }

    private boolean holdsCraftingCardBesides(int slot) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (i != slot && getItem(i).is(PortContent.CRAFTING_CARD.get())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        recount();
        onChanged.run();
    }

    /** Re-reads the card count from the slots; also after loading. */
    void recount() {
        int count = 0;
        boolean card = false;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (getItem(i).is(PortContent.SPEED_CARD.get())) {
                count++;
            } else if (getItem(i).is(PortContent.CRAFTING_CARD.get())) {
                card = true;
            }
        }
        held = count;
        crafting = card;
    }

    /** Whether the port holds a crafting card, counted or not; what its item keeps. */
    public boolean heldCraftingCard() {
        return crafting;
    }

    /** Whether a crafting card sits in one of the first {@link #slots()} slots, where it works. */
    public boolean craftingCard() {
        if (!crafting) {
            return false;
        }
        int slots = slots();
        for (int i = 0; i < slots; i++) {
            if (getItem(i).is(PortContent.CRAFTING_CARD.get())) {
                return true;
            }
        }
        return false;
    }

    /** Every speed card the port holds, counted or not; what its item keeps. */
    public int held() {
        return held;
    }

    /** The cards that speed the port up: those in its first {@link #slots()} slots. */
    public int active() {
        int slots = slots();
        if (held == 0 || slots == 0) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < slots; i++) {
            if (getItem(i).is(PortContent.SPEED_CARD.get())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Fills the slots with {@code count} speed cards and, when {@code craftingCard}, a crafting card after them, at
     * most {@link #MAX_SLOTS} cards, without saving or waking.
     */
    void fill(int count, boolean craftingCard) {
        getItems().clear();
        int speed = Math.min(count, MAX_SLOTS);
        for (int i = 0; i < speed; i++) {
            getItems().set(i, new ItemStack(PortContent.SPEED_CARD.get()));
        }
        if (craftingCard && speed < MAX_SLOTS) {
            getItems().set(speed, new ItemStack(PortContent.CRAFTING_CARD.get()));
        }
        recount();
    }
}
