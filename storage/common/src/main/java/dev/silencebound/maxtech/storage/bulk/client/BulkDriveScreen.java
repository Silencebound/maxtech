package dev.silencebound.maxtech.storage.bulk.client;

import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.bulk.BulkContents;
import dev.silencebound.maxtech.storage.bulk.BulkDriveMenu;
import dev.silencebound.maxtech.storage.bulk.BulkTexts;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The bulk drive's screen in the Accent Frame style: the held kind's icon with the count, a line on taking items out,
 * and the priority tab. Everything comes from the menu. Client only.
 */
public final class BulkDriveScreen extends DeviceScreen<BulkDriveMenu> {
    private static final int ROW_CONTENTS = 18;
    private static final int ROW_HELP = 44;

    public BulkDriveScreen(BulkDriveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BulkDriveMenu.WIDTH, BulkDriveMenu.HEIGHT);
        tabs.priority(menu, () -> true);
        this.titleLabelY = -100;
        this.inventoryLabelY = -100;
    }

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        AccentFrame.slot(graphics, leftPos + 8, topPos + ROW_CONTENTS);
        BulkContents contents = menu.contents();
        if (contents != null) {
            graphics.item(contents.key().toStack(1), leftPos + 8, topPos + ROW_CONTENTS);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        AccentFrame.text(graphics, font, BulkTexts.contents(menu.contents()), 30, ROW_CONTENTS + 4, imageWidth - 38,
                AccentFrame.TEXT, xm, ym);
        AccentFrame.text(graphics, font, Component.translatable(BulkTexts.HELP), 8, ROW_HELP, imageWidth - 16,
                AccentFrame.LABEL, xm, ym);
    }
}
