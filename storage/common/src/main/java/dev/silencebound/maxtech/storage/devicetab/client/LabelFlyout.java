package dev.silencebound.maxtech.storage.devicetab.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.lib.api.client.gui.Icon;
import dev.silencebound.maxtech.lib.api.client.gui.IconButton;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.label.LabelContent;
import dev.silencebound.maxtech.storage.label.LabelCopy;
import dev.silencebound.maxtech.storage.label.LabelPayloads;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.DyeColor;
import org.jspecify.annotations.Nullable;

/**
 * The label tab's flyout (replaces the label page for device screens): the text field, the sixteen dye colours as
 * swatches with None, and "Copy to all" (M7C item 8). Every change is sent at once ({@link LabelPayloads.Change}); the
 * server trims the text and treats an empty one as no label. It starts from the label the server last showed
 * ({@link LabelPayloads#shown()}). Client only.
 */
public final class LabelFlyout implements SideTabs.Flyout {
    private static final int WIDTH = 112;
    private static final int SWATCH = 12;
    private static final int SWATCH_STEP = 14;
    private static final int PER_ROW = 8;
    private static final int GAP = 4;
    private static final DyeColor[] DYES = DyeColor.values();

    private final AbstractContainerMenu menu;
    private String text = "";
    private @Nullable DyeColor colour;
    private DeviceLabel sent = DeviceLabel.NONE;

    public LabelFlyout(AbstractContainerMenu menu) {
        this.menu = menu;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return AccentButton.COMPACT_HEIGHT + GAP + captionHeight() + 2 * SWATCH_STEP + GAP + AccentButton.HEIGHT;
    }

    private static int captionHeight() {
        return TextScale.lineHeight(Minecraft.getInstance().font) + 1;
    }

    @Override
    public void init(SideTabs.Rect content, Consumer<AbstractWidget> add) {
        DeviceLabel label = LabelPayloads.shownFor(menu.containerId) ? LabelPayloads.shown() : DeviceLabel.NONE;
        text = label.text();
        colour = label.colour();
        sent = label;
        Font font = Minecraft.getInstance().font;
        EditBox box = new EditBox(font, content.x(), content.y(), content.width(), AccentButton.COMPACT_HEIGHT,
                Component.translatable(LabelContent.TITLE_KEY));
        box.setMaxLength(DeviceLabel.MAX_LENGTH);
        box.setValue(text);
        box.setHint(Component.translatable(LabelContent.HINT_KEY));
        box.setTooltip(Tooltip.create(Component.translatable(LabelContent.HELP_KEY)));
        box.setResponder(this::typed);
        box.setTextColor(Accent.TEXT);
        add.accept(box);
        int swatchY = content.y() + AccentButton.COMPACT_HEIGHT + GAP + captionHeight();
        for (int i = 0; i < DYES.length; i++) {
            DyeColor dye = DYES[i];
            int x = content.x() + (i % PER_ROW) * SWATCH_STEP;
            int y = swatchY + (i / PER_ROW) * SWATCH_STEP;
            add.accept(
                    IconButton
                            .toggle(x, y, Icon.swatch(dye.getTextureDiffuseColor(), SWATCH - 4, SWATCH - 4),
                                    () -> colour == dye,
                                    () -> Component.translatable("color.minecraft." + dye.getName()), () -> pick(dye))
                            .size(SWATCH));
        }
        int buttonsY = swatchY + 2 * SWATCH_STEP + GAP;
        int half = (content.width() - GAP) / 2;
        add.accept(new AccentButton(content.x(), buttonsY, half, AccentButton.HEIGHT,
                Component.translatable(LabelContent.NO_COLOUR_KEY), () -> colour != null, () -> pick(null)));
        AccentButton copy = new AccentButton(content.x() + half + GAP, buttonsY, content.width() - half - GAP,
                AccentButton.HEIGHT, Component.translatable(LabelCopy.BUTTON_KEY), () -> !sent.isEmpty(),
                () -> Platform.get().network().sendToServer(new LabelCopy.Copy(menu.containerId)));
        copy.setTooltip(Tooltip.create(Component.translatable(LabelCopy.HINT_KEY)));
        add.accept(copy);
    }

    @Override
    public void extract(GuiGraphicsExtractor graphics, Font font, SideTabs.Rect content, int mouseX, int mouseY) {
        TextScale.text(graphics, font, Component.translatable(LabelContent.COLOUR_KEY), content.x(),
                content.y() + AccentButton.COMPACT_HEIGHT + GAP, Accent.LABEL, false);
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
        if (next.equals(sent)) {
            return;
        }
        sent = next;
        Platform.get().network().sendToServer(new LabelPayloads.Change(menu.containerId, next));
    }
}
