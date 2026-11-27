package dev.silencebound.maxtech.storage.iface.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.iface.InterfaceMenu;
import dev.silencebound.maxtech.storage.iface.InterfaceWorker;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The interface's GUI in the Accent Frame style: the item stock row with the held row under it, the three fluid stock
 * slots with their tank amounts, and a hint over the ghost slots; side tabs for the redstone mode, the crafting card
 * and the label (B09 item 21b). Everything it shows comes from the menu. Client only.
 */
public final class InterfaceScreen extends DeviceScreen<InterfaceMenu> {
    public InterfaceScreen(InterfaceMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, InterfaceMenu.WIDTH, InterfaceMenu.HEIGHT);
        this.inventoryLabelY = InterfaceMenu.INVENTORY_Y - 11;
        this.titleLabelY = -100;
        tabs.redstone(menu::redstone, InterfaceMenu.CYCLE_REDSTONE, InterfaceMenu.REDSTONE_BACK);
        tabs.upgrades(menu.slots.subList(InterfaceMenu.CARD_SLOT, InterfaceMenu.CARD_SLOT + 1), menu.cardShown(), null,
                () -> AccentFrame.LABEL);
    }

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                AccentFrame.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        TextScale.text(graphics, font, Component.translatable(InterfaceContent.STOCK_KEY), InterfaceMenu.STOCK_X,
                InterfaceMenu.STOCK_Y - 11, AccentFrame.LABEL, false);
        TextScale.text(graphics, font, Component.translatable(InterfaceContent.FLUIDS_KEY), InterfaceMenu.FLUID_X,
                InterfaceMenu.FLUID_Y - 11, AccentFrame.LABEL, false);
        for (int i = 0; i < InterfaceWorker.FLUID_SLOTS; i++) {
            int buckets = menu.slots.get(InterfaceWorker.ITEM_SLOTS + i).getItem().getCount();
            if (buckets > 0 || menu.fluidAmount(i) > 0) {
                TextScale.text(graphics, font, String.valueOf(menu.fluidAmount(i)),
                        InterfaceMenu.FLUID_X + i * InterfaceMenu.FLUID_SPACING + 19, InterfaceMenu.FLUID_Y + 5,
                        AccentFrame.TEXT, false);
            }
        }
        TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1, AccentFrame.LABEL,
                false);
    }

    /** Over the ghost slots, says how to set them; over a fluid slot, the tank's amount and target. */
    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (!menu.getCarried().isEmpty()) {
            return;
        }
        for (int i = 0; i < InterfaceWorker.FLUID_SLOTS; i++) {
            int x = leftPos + InterfaceMenu.FLUID_X + i * InterfaceMenu.FLUID_SPACING;
            if (ScreenComponents.isHovering(x, topPos + InterfaceMenu.FLUID_Y, 16, 16, mouseX, mouseY)) {
                int buckets = menu.slots.get(InterfaceWorker.ITEM_SLOTS + i).getItem().getCount();
                ScreenComponents.tooltip(graphics, font,
                        List.of(Component.translatable(InterfaceContent.FLUID_AMOUNT_KEY, menu.fluidAmount(i),
                                buckets * InterfaceWorker.BUCKET_MB),
                                Component.translatable(InterfaceContent.HINT_KEY)),
                        mouseX, mouseY);
                return;
            }
        }
        if (ScreenComponents.isHovering(leftPos + InterfaceMenu.STOCK_X, topPos + InterfaceMenu.STOCK_Y,
                InterfaceWorker.ITEM_SLOTS * 18, 16, mouseX, mouseY) && hoveredSlot != null
                && hoveredSlot.getItem().isEmpty()) {
            ScreenComponents.tooltip(graphics, font, List.of(Component.translatable(InterfaceContent.HINT_KEY)), mouseX,
                    mouseY);
        }
    }
}
