package dev.silencebound.maxtech.storage.subnet.client;

import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.subnet.SubnetMenu;
import dev.silencebound.maxtech.storage.subnet.SubnetMode;
import dev.silencebound.maxtech.storage.devicetab.DeviceTabTexts;
import dev.silencebound.maxtech.storage.subnet.SubnetTexts;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The subnet controller's screen in the Accent Frame style: the mode button with a line on what it does, the filter
 * slots, the inventory and the priority tab. Everything comes from the menu; every button is a menu button click.
 * Client only.
 */
public final class SubnetScreen extends DeviceScreen<SubnetMenu> {
    private static final int ROW_MODE = 16;
    private static final int ROW_HELP = 34;

    public SubnetScreen(SubnetMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, SubnetMenu.WIDTH, SubnetMenu.HEIGHT);
        this.titleLabelY = -100;
        this.inventoryLabelY = SubnetMenu.INVENTORY_Y - 11;
        tabs.mode().setting(Component.translatable(DeviceTabTexts.MODE_CAPTION_KEY),
                () -> Component.translatable(menu.mode().translationKey()),
                () -> MenuClick.send(menu, SubnetMenu.CYCLE_MODE), null, () -> menu.mode().id(),
                id -> Component.translatable(SubnetTexts.help(SubnetMode.byId(id))));
        tabs.modeChanged(() -> menu.mode() != SubnetMode.MERGED);
        tabs.priority(menu, () -> true);
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
        AccentFrame.text(graphics, font,
                Component.translatable(SubnetTexts.GUI_MODE, Component.translatable(menu.mode().translationKey())), 8,
                ROW_MODE + 4, imageWidth - 16, AccentFrame.TEXT, xm, ym);
        AccentFrame.text(graphics, font, Component.translatable(SubnetTexts.help(menu.mode())), 8, ROW_HELP,
                imageWidth - 16, AccentFrame.LABEL, xm, ym);
        AccentFrame.text(graphics, font, Component.translatable(SubnetTexts.GUI_FILTER), 8, SubnetMenu.FILTER_Y - 10,
                imageWidth - 16, AccentFrame.LABEL, xm, ym);
        TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1, AccentFrame.LABEL,
                false);
    }
}
