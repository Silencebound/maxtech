package dev.silencebound.maxtech.storage.controller.client;

import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.controller.NetworkRenamePayload;
import dev.silencebound.maxtech.storage.logic.NetworkRef;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * The controller's rename page (M7L2 row 13): a text field of at most {@link NetworkRef#MAX_NAME} characters, a line of
 * help, Save and Back. It sits over the controller's open menu like the label page; Save (or Enter) sends the name once
 * ({@link NetworkRenamePayload}) and goes back, and the server answers in chat. Client only.
 */
public final class NetworkNameScreen extends Screen {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 96;
    private static final int HELP_Y = 40;
    private static final int BUTTON_Y = HEIGHT - 22;

    private final Screen parent;
    private final AbstractContainerMenu menu;
    private String text;
    private int left;
    private int top;

    NetworkNameScreen(Screen parent, AbstractContainerMenu menu, String name) {
        super(Component.translatable(NetworkRenamePayload.TITLE_KEY));
        this.parent = parent;
        this.menu = menu;
        this.text = name;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = AccentFrame.top(height, HEIGHT);
        EditBox box = new EditBox(font, left + 8, top + 22, WIDTH - 16, 12,
                Component.translatable(NetworkRenamePayload.TITLE_KEY));
        box.setMaxLength(NetworkRef.MAX_NAME);
        box.setValue(text);
        box.setResponder(value -> text = value);
        box.setTextColor(AccentFrame.TEXT);
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(new AccentButton(left + 8, top + BUTTON_Y, 50, 16,
                Component.translatable(NetworkRenamePayload.SAVE_KEY), () -> !text.isBlank(), this::save));
        addRenderableWidget(new AccentButton(left + WIDTH - 58, top + BUTTON_Y, 50, 16,
                Component.translatable(NetworkRenamePayload.BACK_KEY), () -> true, this::back));
    }

    private void save() {
        if (!text.isBlank()) {
            Platform.get().network().sendToServer(new NetworkRenamePayload(menu.containerId, text));
        }
        back();
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft != null
                && (minecraft.player == null || minecraft.player.containerMenu.containerId != menu.containerId)) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation()) {
            save();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, left, top, WIDTH, HEIGHT, title, mouseX, mouseY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        ScreenComponents.fittedLines(graphics, font, Component.translatable(NetworkRenamePayload.HELP_KEY), left + 8,
                top + HELP_Y, WIDTH - 16, 3, 10, AccentFrame.LABEL, mouseX, mouseY);
    }

    /** Escape goes back to the controller's screen instead of closing the menu. */
    @Override
    public void onClose() {
        back();
    }

    private void back() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
