package dev.silencebound.maxtech.storage.label.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.label.LabelContent;
import dev.silencebound.maxtech.storage.label.LabelCopy;
import dev.silencebound.maxtech.storage.label.LabelPayloads;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.DyeColor;
import org.jspecify.annotations.Nullable;

/**
 * The shared label page: a text field (at most {@link DeviceLabel#MAX_LENGTH} characters), the sixteen dye colours as
 * swatches in two rows with a None button beside them, a line of help, Back and "Copy to all" (M7C item 8). Like the
 * priority page it sits over the device's open menu without closing it and sends each change at once
 * ({@link LabelPayloads.Change}); the server trims the text and treats an empty one as no label. Escape, Enter and Back
 * return to the device's screen. Client only.
 */
public final class LabelScreen extends Screen {
    public static final int WIDTH = 176;
    /** Tall enough for three lines of help above the Back button. */
    public static final int HEIGHT = 142;
    private static final int HELP_LINES = 3;
    private static final int BACK_Y = HEIGHT - 22;
    private static final int COPY_WIDTH = 70;
    private static final int SWATCH = 12;
    private static final int SWATCH_STEP = 14;
    private static final int PER_ROW = 8;
    private static final int SWATCH_X = 8;
    private static final int SWATCH_Y = 52;
    private static final int HELP_Y = SWATCH_Y + 2 * SWATCH_STEP + 6;
    private static final DyeColor[] DYES = DyeColor.values();

    private final Screen parent;
    private final AbstractContainerMenu menu;
    private String text;
    private @Nullable DyeColor colour;
    private DeviceLabel sent;
    private int left;
    private int top;

    LabelScreen(Screen parent, AbstractContainerMenu menu, DeviceLabel label) {
        super(Component.translatable(LabelContent.TITLE_KEY));
        this.parent = parent;
        this.menu = menu;
        this.text = label.text();
        this.colour = label.colour();
        this.sent = label;
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
                Component.translatable(LabelContent.TITLE_KEY));
        box.setMaxLength(DeviceLabel.MAX_LENGTH);
        box.setValue(text);
        box.setHint(Component.translatable(LabelContent.HINT_KEY));
        box.setResponder(this::typed);
        box.setTextColor(AccentFrame.TEXT);
        addRenderableWidget(box);
        setInitialFocus(box);
        int noneX = left + SWATCH_X + PER_ROW * SWATCH_STEP + 2;
        addRenderableWidget(new AccentButton(noneX, top + SWATCH_Y, left + WIDTH - 8 - noneX, SWATCH_STEP + SWATCH,
                Component.translatable(LabelContent.NO_COLOUR_KEY), () -> colour != null, () -> pick(null)));
        addRenderableWidget(new AccentButton(left + 8, top + BACK_Y, 50, 16,
                Component.translatable(LabelContent.BACK_KEY), () -> true, this::back));
        // M7C item 8: the server copies the device's settings to the others with its label and says what changed.
        AccentButton copy = new AccentButton(left + WIDTH - 8 - COPY_WIDTH, top + BACK_Y, COPY_WIDTH, 16,
                Component.translatable(LabelCopy.BUTTON_KEY), () -> !sent.isEmpty(),
                () -> Platform.get().network().sendToServer(new LabelCopy.Copy(menu.containerId)));
        copy.setTooltip(Tooltip.create(Component.translatable(LabelCopy.HINT_KEY)));
        addRenderableWidget(copy);
    }

    private void typed(String value) {
        text = value;
        send();
    }

    private void pick(@Nullable DyeColor dye) {
        colour = dye;
        send();
    }

    /** Sends the label when it differs from the last one sent; a colour without text waits for the text. */
    private void send() {
        DeviceLabel next = DeviceLabel.of(text, colour);
        if (next.equals(sent) || minecraft == null) {
            return;
        }
        sent = next;
        Platform.get().network().sendToServer(new LabelPayloads.Change(menu.containerId, next));
    }

    /** The dye of the swatch at the mouse, or null when the mouse is on none. */
    private @Nullable DyeColor swatchAt(double mouseX, double mouseY) {
        double x = mouseX - left - SWATCH_X;
        double y = mouseY - top - SWATCH_Y;
        if (x < 0 || y < 0) {
            return null;
        }
        int column = (int) (x / SWATCH_STEP);
        int row = (int) (y / SWATCH_STEP);
        if (column >= PER_ROW || row >= 2 || x - column * SWATCH_STEP >= SWATCH || y - row * SWATCH_STEP >= SWATCH) {
            return null;
        }
        return DYES[row * PER_ROW + column];
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            DyeColor dye = swatchAt(event.x(), event.y());
            if (dye != null) {
                pick(dye);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
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
            back();
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
        TextScale.text(graphics, font, Component.translatable(LabelContent.COLOUR_KEY), left + 8, top + SWATCH_Y - 11,
                AccentFrame.LABEL, false);
        for (int i = 0; i < DYES.length; i++) {
            DyeColor dye = DYES[i];
            int x = left + SWATCH_X + (i % PER_ROW) * SWATCH_STEP;
            int y = top + SWATCH_Y + (i / PER_ROW) * SWATCH_STEP;
            int edge = dye == colour ? AccentFrame.TEXT : AccentFrame.OUTLINE;
            graphics.fill(x, y, x + SWATCH, y + SWATCH, edge);
            graphics.fill(x + 1, y + 1, x + SWATCH - 1, y + SWATCH - 1, dye.getTextureDiffuseColor());
        }
        ScreenComponents.fittedLines(graphics, font, Component.translatable(LabelContent.HELP_KEY), left + 8,
                top + HELP_Y, WIDTH - 16, HELP_LINES, 10, AccentFrame.LABEL, mouseX, mouseY);

        DyeColor hovered = swatchAt(mouseX, mouseY);
        if (hovered != null) {
            graphics.setComponentTooltipForNextFrame(font,
                    List.of(Component.translatable("color.minecraft." + hovered.getName())), mouseX, mouseY);
        }
    }

    /** Escape goes back to the device's screen instead of closing the menu. */
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
