package dev.silencebound.maxtech.storage.ioport.client;

import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.lib.client.gui.accent.Accent;
import dev.silencebound.maxtech.storage.devicetab.DeviceTabTexts;
import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.ioport.IoPortBlockEntity;
import dev.silencebound.maxtech.storage.ioport.IoPortContent;
import dev.silencebound.maxtech.storage.ioport.IoPortMenu;
import dev.silencebound.maxtech.storage.ioport.IoPortMode;
import dev.silencebound.maxtech.storage.ioport.IoPortOutput;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The IO port's screen in the Accent Frame style: input cells on the left, output cells on the right and, between them,
 * the mode, when cells move out and the operations per tick. Side tabs (B09 item 21b): mode (unload or load, when cells
 * move out, redstone), upgrades (the speed cards) and label. Everything it shows comes from the menu. Client only.
 */
public final class IoPortScreen extends DeviceScreen<IoPortMenu> {
    private static final int TEXT_Y = IoPortMenu.MODE_Y + 2;

    public IoPortScreen(IoPortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, IoPortMenu.WIDTH, IoPortMenu.HEIGHT);
        this.titleLabelY = -100;
        this.inventoryLabelX = IoPortMenu.INVENTORY_X;
        this.inventoryLabelY = IoPortMenu.INVENTORY_Y - 11;
        tabs.mode()
                .setting(Component.translatable(DeviceTabTexts.MODE_CAPTION_KEY),
                        () -> Component.translatable(menu.mode().translationKey()),
                        () -> MenuClick.send(menu, IoPortMenu.CYCLE_MODE), null, () -> menu.mode().id(),
                        id -> CommonComponents.joinLines(Component.translatable(IoPortMode.byId(id).translationKey()),
                                Component.translatable(IoPortContent.MODE_HINT_KEY).withColor(Accent.LABEL)))
                .setting(Component.translatable(IoPortContent.OUTPUT_CAPTION_KEY),
                        () -> Component.translatable(menu.output().translationKey()),
                        () -> MenuClick.send(menu, IoPortMenu.CYCLE_OUTPUT), null, () -> menu.output().id(),
                        id -> CommonComponents.joinLines(Component.translatable(IoPortOutput.byId(id).translationKey()),
                                Component.translatable(IoPortContent.OUTPUT_HINT_KEY).withColor(Accent.LABEL)));
        tabs.modeChanged(() -> menu.mode() != IoPortMode.UNLOAD || menu.output() != IoPortOutput.WORK_DONE);
        tabs.redstone(menu::redstone, IoPortMenu.CYCLE_REDSTONE, IoPortMenu.REDSTONE_BACK);
        tabs.upgrades(menu.cardSlots(), menu.cardsShown(),
                () -> Component.translatable(IoPortContent.SPEED_KEY, menu.operations()), () -> Accent.LABEL);
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
        int labelY = IoPortMenu.SLOTS_Y + 3 * 18 + 2;
        TextScale.text(graphics, font, Component.translatable(IoPortContent.INPUT_KEY), IoPortMenu.INPUT_X, labelY,
                AccentFrame.LABEL, false);
        Component out = Component.translatable(IoPortContent.OUTPUT_KEY);
        TextScale.text(graphics, font, out, IoPortMenu.slotX(IoPortBlockEntity.INPUTS + 1) + 16 - font.width(out),
                labelY, AccentFrame.LABEL, false);
        int line = TextScale.lineHeight(font) + 2;
        centred(graphics, Component.translatable(menu.mode().translationKey()), TEXT_Y, AccentFrame.TEXT, xm, ym);
        centred(graphics, Component.translatable(menu.output().translationKey()), TEXT_Y + line, AccentFrame.LABEL, xm,
                ym);
        centred(graphics, Component.translatable(IoPortContent.SPEED_KEY, menu.operations()), labelY, AccentFrame.TEXT,
                xm, ym);
        TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1, AccentFrame.LABEL,
                false);
    }

    /** Draws {@code text} centred between the input and output slots. */
    private void centred(GuiGraphicsExtractor graphics, Component text, int y, int colour, int xm, int ym) {
        int width = Math.min(font.width(text), IoPortMenu.BUTTON_WIDTH);
        AccentFrame.text(graphics, font, text, IoPortMenu.BUTTON_X + (IoPortMenu.BUTTON_WIDTH - width) / 2, y, width,
                colour, xm, ym);
    }
}
