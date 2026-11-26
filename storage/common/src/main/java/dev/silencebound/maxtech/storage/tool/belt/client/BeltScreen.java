package dev.silencebound.maxtech.storage.tool.belt.client;

import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.tool.belt.BeltContent;
import dev.silencebound.maxtech.storage.tool.belt.BeltMarks;
import dev.silencebound.maxtech.storage.tool.belt.BeltMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The restock belt's GUI in the Accent Frame style: the player's inventory with each marked slot framed in cyan, its
 * item shown faintly when the slot is empty and the count to keep in its corner. Clicks go to the menu, which marks and
 * clears on the server; the wheel over a marked slot changes its count by one (16 with shift). Client only.
 */
public final class BeltScreen extends AbstractContainerScreen<BeltMenu> {
    private static final int GHOST_SHADE = 0xA015181B;

    public BeltScreen(BeltMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BeltMenu.WIDTH, BeltMenu.HEIGHT);
        this.inventoryLabelY = -100;
        this.titleLabelY = -100;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        BeltMarks marks = menu.marks();
        for (Slot slot : menu.slots) {
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            BeltMarks.Mark mark = marks.at(slot.getContainerSlot());
            if (mark != null) {
                graphics.fill(x - 2, y - 2, x + 18, y + 18, AccentFrame.ACCENT);
            }
            AccentFrame.slot(graphics, x, y);
            if (mark != null && !slot.hasItem()) {
                graphics.item(mark.key().toStack(1), x, y);
                graphics.fill(x, y, x + 16, y + 16, GHOST_SHADE);
            }
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        AccentFrame.text(graphics, font, Component.translatable(BeltContent.HINT_KEY), 8, 8, imageWidth - 16,
                AccentFrame.LABEL, xm, ym);
        AccentFrame.text(graphics, font, Component.translatable(BeltContent.HINT_COUNT_KEY), 8, 19, imageWidth - 16,
                AccentFrame.LABEL, xm, ym);
        BeltMarks marks = menu.marks();
        for (Slot slot : menu.slots) {
            BeltMarks.Mark mark = marks.at(slot.getContainerSlot());
            if (mark != null) {
                String label = Integer.toString(mark.count());
                graphics.pose().pushMatrix();
                graphics.pose().translate(slot.x, slot.y - 1);
                graphics.pose().scale(0.5F, 0.5F);
                TextScale.text(graphics, font, label, 0, 0, AccentFrame.TEXT, true);
                graphics.pose().popMatrix();
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Slot slot = hoveredSlot;
        if (slot != null && scrollY != 0 && minecraft != null && minecraft.gameMode != null
                && menu.marks().at(slot.getContainerSlot()) != null) {
            int step = minecraft.hasShiftDown() ? 16 : 1;
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                    BeltMenu.button(slot.getContainerSlot(), scrollY > 0 ? step : -step));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
