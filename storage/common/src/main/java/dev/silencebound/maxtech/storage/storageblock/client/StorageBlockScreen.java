package dev.silencebound.maxtech.storage.storageblock.client;

import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockMenu;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockTexts;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The storage block's screen in the Accent Frame style: a fill bar with the bytes used of the capacity, the kinds held,
 * and the device side tabs (priority, label). Everything comes from the menu. Client only.
 */
public final class StorageBlockScreen extends DeviceScreen<StorageBlockMenu> {
    private static final int ROW_BAR = 18;
    private static final int BAR_HEIGHT = 6;
    private static final int ROW_BYTES = 30;
    private static final int ROW_KINDS = 44;

    public StorageBlockScreen(StorageBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, StorageBlockMenu.WIDTH, StorageBlockMenu.HEIGHT);
        tabs.priority(menu, () -> true);
        this.titleLabelY = -100;
        this.inventoryLabelY = -100;
    }

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        long total = menu.totalBytes();
        float fraction = total <= 0 ? 0 : (float) Math.min(1.0, (double) menu.usedBytes() / total);
        AccentFrame.horizontalBar(graphics, leftPos + 8, topPos + ROW_BAR, imageWidth - 16, BAR_HEIGHT, fraction);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        AccentFrame.text(graphics, font, StorageBlockTexts.bytes(menu.usedBytes(), menu.totalBytes()), 8, ROW_BYTES,
                imageWidth - 16, AccentFrame.TEXT, xm, ym);
        AccentFrame.text(graphics, font, StorageBlockTexts.kinds(menu.kinds()), 8, ROW_KINDS, imageWidth - 16,
                AccentFrame.LABEL, xm, ym);
    }
}
