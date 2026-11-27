package dev.silencebound.maxtech.lib.client.gui;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.client.gui.TextFit;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;

/**
 * The GUI building blocks MaxTech screens draw with (PRD 5.3, "vanilla-flat"): vanilla's nine-slice panel and slot
 * sprites, sunken frames drawn with plain fills in vanilla's GUI colours, side tabs, tinted buttons and tooltips. No
 * MaxTech texture exists; screens draw through these methods instead of blitting textures of their own. Positions are
 * absolute screen coordinates. Client only; call from a screen's render methods.
 */
public final class ScreenComponents {
    public static final String ON_KEY = "gui.maxtech.on";
    public static final String OFF_KEY = "gui.maxtech.off";

    /** Vanilla's light grey panel with a rounded black outline, nine-sliced to any size. */
    public static final Identifier PANEL = Identifier.withDefaultNamespace("recipe_book/overlay_recipe");
    public static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");
    public static final Identifier TAB = Identifier.withDefaultNamespace("recipe_book/tab");
    public static final Identifier TAB_SELECTED = Identifier.withDefaultNamespace("recipe_book/tab_selected");

    /** Vanilla's label grey, as container screens use for their titles. */
    public static final int LABEL_COLOUR = 0xFF404040;
    public static final int TAB_WIDTH = 35;
    public static final int TAB_HEIGHT = 27;

    private static final int INSET_DARK = 0xFF373737;
    private static final int INSET_LIGHT = 0xFFFFFFFF;
    private static final int INSET_FILL = 0xFF8B8B8B;

    private ScreenComponents() {
    }

    /** A vanilla panel covering the rectangle, border included. */
    public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, width, height);
    }

    /** A slot frame around the 16x16 item at ({@code itemX}, {@code itemY}), as vanilla's {@code Slot} positions. */
    public static void slot(GuiGraphicsExtractor graphics, int itemX, int itemY) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, itemX - 1, itemY - 1, 18, 18);
    }

    /** A sunken frame like a slot's, at any size: dark top and left edges, light bottom and right, slot grey inside. */
    public static void inset(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, INSET_FILL);
        graphics.fill(x, y, x + width - 1, y + 1, INSET_DARK);
        graphics.fill(x, y + 1, x + 1, y + height - 1, INSET_DARK);
        graphics.fill(x + 1, y + height - 1, x + width, y + height, INSET_LIGHT);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, INSET_LIGHT);
    }

    public static boolean isHovering(int x, int y, int width, int height, int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** Shows {@code lines} as the tooltip for this frame. */
    public static void tooltip(GuiGraphicsExtractor graphics, Font font, List<Component> lines, int mouseX,
            int mouseY) {
        graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    /**
     * Draws {@code text} at ({@code x}, {@code y}) cut to {@code width} with an ellipsis ({@link TextFit}); while the
     * mouse is on a cut text, the whole text shows as a tooltip. Positions are in the current pose's space (a container
     * screen's labels are relative to the panel), the mouse in screen coordinates. Returns whether the text was cut.
     * Nothing is drawn for a width of 0 or less.
     */
    public static boolean fittedText(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width,
            int colour, int mouseX, int mouseY) {
        if (width <= 0) {
            return false;
        }
        if (TextScale.width(font, text) <= width) {
            TextScale.text(graphics, font, text, x, y, colour, false);
            return false;
        }
        String plain = text.getString();
        TextScale.text(graphics, font, TextFit.fit(plain, width, s -> TextScale.width(font, s)), x, y, colour, false);
        if (mouseOver(graphics, x, y - 1, width, TextScale.lineHeight(font) + 1, mouseX, mouseY)) {
            graphics.setComponentTooltipForNextFrame(font, List.of(text), mouseX, mouseY);
        }
        return true;
    }

    /**
     * Draws {@code text} wrapped to {@code width} in at most {@code maxLines} lines {@code lineStep} apart; a text that
     * needs more ends its last line with an ellipsis and shows whole as a tooltip while the mouse is on it. Positions
     * as in {@link #fittedText}. Returns the y below the last line drawn.
     */
    public static int fittedLines(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width,
            int maxLines, int lineStep, int colour, int mouseX, int mouseY) {
        String plain = text.getString();
        List<String> lines = TextFit.lines(plain, width, maxLines, s -> TextScale.width(font, s));
        for (int i = 0; i < lines.size(); i++) {
            TextScale.text(graphics, font, lines.get(i), x, y + i * lineStep, colour, false);
        }
        if (!lines.isEmpty() && TextFit.linesCut(plain, width, maxLines, s -> TextScale.width(font, s))
                && mouseOver(graphics, x, y - 1, width, lines.size() * lineStep, mouseX, mouseY)) {
            graphics.setTooltipForNextFrame(font, font.split(text, 200), mouseX, mouseY);
        }
        return y + lines.size() * lineStep;
    }

    /**
     * Whether the mouse, in screen coordinates, is on the rectangle given in the current pose's space (a container
     * screen's labels are drawn relative to the panel). Only the pose's translation and scale are applied; MaxTech
     * screens use no other.
     */
    public static boolean mouseOver(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int mouseX,
            int mouseY) {
        Matrix3x2f pose = graphics.pose();
        float left = pose.m00() * x + pose.m20();
        float top = pose.m11() * y + pose.m21();
        return mouseX >= left && mouseX < left + pose.m00() * width && mouseY >= top
                && mouseY < top + pose.m11() * height;
    }

    /** "on" or "off", for toggle labels. */
    public static Component onOff(boolean on) {
        return Component.translatable(on ? ON_KEY : OFF_KEY);
    }

    /**
     * A side tab in vanilla's recipe book style with an item icon, sticking out of a panel's left edge; the selected
     * tab covers the panel border so it reads as part of the panel. Its tooltip is the tab's title.
     */
    public static final class TabButton extends AbstractButton {
        private final ItemStack icon;
        private final boolean selected;
        private final Runnable onPress;

        /** {@code x} is where an unselected tab starts: 30 left of the panel's edge. */
        public TabButton(int x, int y, Component title, ItemStack icon, boolean selected, Runnable onPress) {
            super(x, y, TAB_WIDTH, TAB_HEIGHT, title);
            this.icon = icon;
            this.selected = selected;
            this.onPress = onPress;
            setTooltip(Tooltip.create(title));
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (!selected) {
                onPress.run();
            }
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            int x = selected ? getX() - 2 : getX();
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, selected ? TAB_SELECTED : TAB, x, getY(), width, height);
            graphics.fakeItem(icon, x + 9, getY() + 5);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /**
     * A vanilla button whose sprite is tinted, for compact state buttons (side modes, toggles) where the colour carries
     * the state and the label is a letter or two. The owner updates label, tint and tooltip when the state changes.
     */
    public static final class TintedButton extends Button {
        private int tint;

        public TintedButton(int x, int y, int width, int height, Component label, int tint, OnPress onPress) {
            super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
            this.tint = tint;
        }

        /** ARGB multiplied onto vanilla's grey button sprite. */
        public void setTint(int tint) {
            this.tint = tint;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITES.get(active, isHoveredOrFocused()), getX(), getY(),
                    width, height, tint);
            extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
        }
    }
}
