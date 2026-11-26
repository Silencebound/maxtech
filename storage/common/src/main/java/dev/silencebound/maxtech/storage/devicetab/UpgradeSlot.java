package dev.silencebound.maxtech.storage.devicetab;

import java.util.function.BooleanSupplier;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A real slot for one upgrade card that the screen shows in its upgrades flyout (B09 item 21b), at
 * {@link DeviceTabLayout#slotX}/{@link DeviceTabLayout#slotY}. It is active (drawn, hovered and clickable) only while
 * the flyout shows it; {@code shown} is a client-side flag the screen sets, and the server never asks it, so
 * shift-clicks still move cards in and out with the flyout closed. Takes what its container's {@code canPlaceItem}
 * allows, one item at a time.
 */
public class UpgradeSlot extends Slot {
    private final BooleanSupplier shown;

    public UpgradeSlot(Container container, int index, int x, int y, BooleanSupplier shown) {
        super(container, index, x, y);
        this.shown = shown;
    }

    @Override
    public boolean isActive() {
        return shown.getAsBoolean();
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return container.canPlaceItem(getContainerSlot(), stack);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    /** The flag a menu's upgrade slots read: whether the screen shows them. Client side; false on the server. */
    public static final class Shown implements BooleanSupplier {
        private boolean shown;

        @Override
        public boolean getAsBoolean() {
            return shown;
        }

        /** Shows or hides the slots; the screen calls this every frame. */
        public void set(boolean shown) {
            this.shown = shown;
        }
    }
}
