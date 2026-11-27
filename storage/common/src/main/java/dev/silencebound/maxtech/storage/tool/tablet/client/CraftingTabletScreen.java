package dev.silencebound.maxtech.storage.tool.tablet.client;

import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletMenu;
import dev.silencebound.maxtech.storage.tool.tablet.TabletJobs;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The crafting tablet's screen in the Accent Frame style: the vanilla grid and result, an FE gauge beside the grid and
 * the "request missing" button under the result and the jobs button above it. Everything it shows comes from the menu's
 * slots and data slots. Client only.
 */
public final class CraftingTabletScreen extends AbstractContainerScreen<CraftingTabletMenu> {
    private static final int ENERGY_X = 12;
    private static final int ENERGY_Y = CraftingTabletMenu.GRID_Y;
    private static final int ENERGY_WIDTH = 8;
    private static final int ENERGY_HEIGHT = 54;
    private static final int BUTTON_X = 108;
    private static final int BUTTON_Y = 58;
    private static final int JOBS_Y = 15;
    private static final int BUTTON_WIDTH = 60;
    private static final int BUTTON_HEIGHT = 14;
    private static final int ARROW_X = 90;
    private static final int ARROW_Y = 41;

    public CraftingTabletScreen(CraftingTabletMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, CraftingTabletMenu.WIDTH, CraftingTabletMenu.HEIGHT);
        this.titleLabelY = -100;
        this.inventoryLabelY = CraftingTabletMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(MenuClick.button(leftPos + BUTTON_X, topPos + BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT,
                () -> Component.translatable(CraftingTabletContent.REQUEST_KEY), menu, CraftingTabletMenu.REQUEST)
                .enabled(menu::reaches));
        addRenderableWidget(MenuClick
                .button(leftPos + BUTTON_X, topPos + JOBS_Y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        () -> Component.translatable(TabletJobs.JOBS_KEY), menu, CraftingTabletMenu.JOBS)
                .enabled(menu::reaches).tooltip(Component.translatable(TabletJobs.JOBS_TOOLTIP_KEY)));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                AccentFrame.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
        AccentFrame.horizontalBar(graphics, leftPos + ARROW_X, topPos + ARROW_Y, 24, 6,
                menu.result().isEmpty() ? 0.0F : 1.0F);
        AccentFrame.verticalBar(graphics, leftPos + ENERGY_X, topPos + ENERGY_Y, ENERGY_WIDTH, ENERGY_HEIGHT,
                menu.capacity() > 0 ? (float) menu.energy() / menu.capacity() : 0.0F);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1, AccentFrame.LABEL,
                false);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (hoveredSlot != null || !menu.getCarried().isEmpty()) {
            return;
        }
        if (ScreenComponents.isHovering(leftPos + ENERGY_X, topPos + ENERGY_Y, ENERGY_WIDTH, ENERGY_HEIGHT, mouseX,
                mouseY)) {
            ScreenComponents.tooltip(graphics, font,
                    List.of(Component.translatable(CraftingTabletContent.ENERGY_KEY, menu.energy(), menu.capacity())),
                    mouseX, mouseY);
        } else if (ScreenComponents.isHovering(leftPos + BUTTON_X, topPos + BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT,
                mouseX, mouseY)) {
            // The label first: the narrow button shows it shortened.
            ScreenComponents.tooltip(graphics, font, List.of(Component.translatable(CraftingTabletContent.REQUEST_KEY),
                    Component.translatable(CraftingTabletContent.REQUEST_TOOLTIP_KEY)), mouseX, mouseY);
        }
    }
}
