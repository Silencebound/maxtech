package dev.silencebound.maxtech.storage.display.client;

import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.crafting.order.ListCode;
import dev.silencebound.maxtech.storage.crafting.order.OrderList;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayListSave;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.material.MaterialTexts;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The list editor of a screen showing {@link dev.silencebound.maxtech.storage.display.DisplaySourceKind#MATERIAL_LIST},
 * opened from the settings GUI over the still open display menu: a text box for a {@code mtlist1:} code or a typed
 * list, a paste button for the clipboard, and what it understood. Saving sends {@link DisplayListSave}; the server
 * reads the list again. Escape goes back to the settings. Client only.
 */
public final class MaterialListScreen extends Screen {
    private static final int WIDTH = 248;
    private static final int HEIGHT = 128;
    private static final int PAD = 8;
    private static final int INNER = WIDTH - 2 * PAD;
    private static final int ROW_BOX = 20;
    private static final int BOX_HEIGHT = 62;
    private static final int ROW_STATUS = 88;
    private static final int ROW_BUTTONS = 102;

    private final DisplayMenu menu;
    private final Screen parent;
    private String text = "";
    private int left;
    private int top;
    private @Nullable MultiLineEditBox box;

    public MaterialListScreen(DisplayMenu menu, Screen parent) {
        super(Component.translatable(MaterialTexts.EDIT));
        this.menu = menu;
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = AccentFrame.top(height, HEIGHT);
        if (minecraft != null && minecraft.level != null && box == null) {
            DisplaySettings settings = menu.clientSettings(minecraft.level);
            text = settings.list();
        }
        MultiLineEditBox editor = MultiLineEditBox.builder().setX(left + PAD).setY(top + ROW_BOX)
                .setTextColor(AccentFrame.TEXT).setCursorColor(AccentFrame.TEXT)
                .build(font, INNER, BOX_HEIGHT, Component.translatable(MaterialTexts.EDITOR_HINT));
        editor.setCharacterLimit(ListCode.MAX_LENGTH);
        editor.setValue(text, true);
        editor.setValueListener(value -> text = value);
        box = addRenderableWidget(editor);
        setInitialFocus(editor);
        add(PAD, () -> Component.translatable(MaterialTexts.PASTE), this::paste);
        add(WIDTH - PAD - 124, () -> Component.translatable(DisplayContent.SAVE_KEY), this::save);
        add(WIDTH - PAD - 60, () -> Component.translatable(DisplayContent.CANCEL_KEY), this::back);
    }

    private void add(int x, Supplier<Component> label, Runnable action) {
        addRenderableWidget(new AccentButton(left + x, top + ROW_BUTTONS, 60, 14, label.get(), action).label(label));
    }

    private void paste() {
        MultiLineEditBox editor = box;
        if (editor != null && minecraft != null) {
            editor.setValue(minecraft.keyboardHandler.getClipboard(), true);
        }
    }

    private void save() {
        Platform.get().network().sendToServer(new DisplayListSave(menu.containerId, text));
        back();
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft != null && (minecraft.player == null || minecraft.player.containerMenu != menu)) {
            minecraft.setScreen(null);
        }
    }

    /** Escape goes back to the settings instead of closing the menu. */
    @Override
    public void onClose() {
        back();
    }

    private void back() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, left, top, WIDTH, HEIGHT, title, mouseX, mouseY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        OrderList.Resolved resolved = OrderList.resolve(text);
        boolean bad = !resolved.errors().isEmpty();
        Component status = bad
                ? Component.translatable(OrderList.UNKNOWN_KEY, String.join(", ", resolved.errors()))
                : Component.translatable(MaterialTexts.UNDERSTOOD, resolved.roots().size());
        AccentFrame.text(graphics, font, status, left + PAD, top + ROW_STATUS, INNER,
                bad ? 0xFFE0A040 : AccentFrame.LABEL, mouseX, mouseY);
    }
}
