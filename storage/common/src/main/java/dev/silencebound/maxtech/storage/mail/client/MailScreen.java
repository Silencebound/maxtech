package dev.silencebound.maxtech.storage.mail.client;

import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.mail.MailContent;
import dev.silencebound.maxtech.storage.mail.MailMenu;
import dev.silencebound.maxtech.storage.mail.MailSendPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * A mail port's screen in the Accent Frame style: the recipient's name, Send, the outbox slots, the inbox count with
 * Collect, and the player's inventory. Client only.
 */
public final class MailScreen extends DeviceScreen<MailMenu> {
    private static final int ROW_NAME = 18;
    private static final int ROW_INBOX = 80;
    private static final int BUTTON_HEIGHT = 14;

    private @Nullable EditBox name;

    public MailScreen(MailMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, MailMenu.WIDTH, MailMenu.HEIGHT);
        this.titleLabelY = -100;
        this.inventoryLabelY = MailMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        EditBox box = new EditBox(font, leftPos + 30, topPos + ROW_NAME + 3, 86, 10,
                Component.translatable(MailContent.TO_KEY));
        box.setBordered(false);
        box.setMaxLength(MailMenu.MAX_NAME);
        addRenderableWidget(box);
        name = box;
        addRenderableWidget(new AccentButton(leftPos + 122, topPos + ROW_NAME, 46, BUTTON_HEIGHT,
                Component.translatable(MailContent.SEND_KEY), this::send));
        addRenderableWidget(new AccentButton(leftPos + 122, topPos + ROW_INBOX - 3, 46, BUTTON_HEIGHT,
                Component.translatable(MailContent.COLLECT_KEY), this::collect));
    }

    private void send() {
        EditBox box = name;
        if (box != null && !box.getValue().isBlank()) {
            Platform.get().network().sendToServer(new MailSendPayload(menu.containerId, box.getValue().trim()));
        }
    }

    private void collect() {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, MailMenu.COLLECT);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        EditBox box = name;
        if (box != null && box.isFocused() && !event.isEscape()) {
            // Printable keys arrive through charTyped; this keeps "e" and the hotbar keys from acting on the screen.
            box.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        graphics.fill(leftPos + 27, topPos + ROW_NAME, leftPos + 118, topPos + ROW_NAME + BUTTON_HEIGHT,
                AccentFrame.SLOT_EDGE);
        graphics.fill(leftPos + 28, topPos + ROW_NAME + 1, leftPos + 117, topPos + ROW_NAME + BUTTON_HEIGHT - 1,
                AccentFrame.SLOT);
        for (int i = 0; i < MailMenu.OUTBOX_SLOTS; i++) {
            AccentFrame.slot(graphics, leftPos + 8 + i * 18, topPos + MailMenu.OUTBOX_Y);
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                AccentFrame.slot(graphics, leftPos + 8 + col * 18, topPos + MailMenu.INVENTORY_Y + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            AccentFrame.slot(graphics, leftPos + 8 + col * 18, topPos + MailMenu.INVENTORY_Y + 58);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        TextScale.text(graphics, font, Component.translatable(MailContent.TO_KEY), 8, ROW_NAME + 3, AccentFrame.LABEL,
                false);
        TextScale.text(graphics, font,
                Component.translatable(MailContent.COST_KEY, Integer.toString(menu.muPerStack())), 8, ROW_NAME + 22,
                AccentFrame.LABEL, false);
        TextScale.text(graphics, font, Component.translatable(MailContent.INBOX_KEY, Integer.toString(menu.inboxSize()),
                Integer.toString(menu.inboxCapacity())), 8, ROW_INBOX, AccentFrame.TEXT, false);
        TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, AccentFrame.LABEL,
                false);
    }
}
