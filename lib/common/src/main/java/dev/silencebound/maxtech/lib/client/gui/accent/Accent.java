package dev.silencebound.maxtech.lib.client.gui.accent;

import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.lib.client.gui.LayoutCheck;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.text.TextFit;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The Accent Frame look shared by every MaxTech screen (Storage PRD 7.2; owner decision 2026-10-18, "one look
 * everywhere"): cyan on graphite, a dark outline, a cyan accent line under a title tab on top, flat monochrome slots
 * and blue-to-cyan gradient bars. Drawn with plain fills as pixel art, so it needs no textures and scales cleanly. This
 * class holds the palette (ARGB) and the drawing; the widgets built on it are {@link AccentButton}, {@link IconButton},
 * {@link SideTabs}, {@link ScrollBar}, {@link AccentToast}, {@link AccentPanel}, {@link AccentInset},
 * {@link AccentRow}, {@link AccentReadout}, {@link AccentTag}, {@link AccentTabRow} and {@link AccentStatus}. Positions
 * are absolute screen coordinates. Client only.
 */
public final class Accent {
    public static final int OUTLINE = 0xFF0C0E10;
    public static final int GRAPHITE = 0xFF25292D;
    public static final int BEVEL = 0xFF343A3F;
    public static final int SLOT = 0xFF15181B;
    public static final int SLOT_EDGE = 0xFF3B4247;
    public static final int ACCENT = 0xFF2A8794;
    public static final int ACCENT_DIM = 0xFF1C5660;
    public static final int TEXT = 0xFF9FD3D9;
    public static final int LABEL = 0xFF7D8A90;
    public static final int BAR_TOP = 0xFF46B2BD;
    public static final int BAR_BOTTOM = 0xFF294C86;
    /** Warnings: an amber badge or toast. */
    public static final int AMBER = 0xFFE0A030;
    /** Errors: a red badge or toast. */
    public static final int RED = 0xFFD8504A;
    /** Running, done, fine: a status dot, a tag or a log edge. */
    public static final int GREEN = 0xFF55C46A;
    /** Values the player can change in place (a card's choice words). */
    public static final int GOLD = 0xFFE3C15A;
    /** Headings, links and live values on a dark panel; brighter than {@link #ACCENT} so text reads. */
    public static final int CYAN = BAR_TOP;
    /** Text and marks that are switched off or dimmed. */
    public static final int DIM = 0xFF4A5257;
    /** Text under the mouse (a link, a clickable word). */
    public static final int BRIGHT = 0xFFE4F6F8;
    /** A faint fill over a hovered list row. */
    public static final int HOVER_FILL = 0x14FFFFFF;
    public static final int TAB_HEIGHT = 12;
    /** The smallest a button label is drawn to fit before it is cut instead. */
    private static final float MIN_LABEL_SCALE = 0.6F;

    private Accent() {
    }

    /**
     * The panel with its title tab sticking out above it; the tab is as wide as the title, and a title wider than the
     * panel ends with an ellipsis.
     */
    public static void panel(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            Component title) {
        panel(graphics, font, x, y, width, height, title, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    /** {@link #panel}; while the mouse is on a shortened title, the whole title shows as a tooltip. */
    public static void panel(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            Component title, int mouseX, int mouseY) {
        int room = Math.max(0, width - 24);
        int tabWidth = Math.min(font.width(title), room) + 12;
        int tabY = y - TAB_HEIGHT + 1;
        box(graphics, x + 6, tabY, tabWidth, TAB_HEIGHT + 1);
        box(graphics, x, y, width, height);
        // The tab opens into the panel: no border between them.
        graphics.fill(x + 7, y, x + 5 + tabWidth, y + 1, GRAPHITE);
        graphics.fill(x + 3, y + 3, x + width - 3, y + 4, ACCENT);
        graphics.fill(x + 7, tabY + 1, x + 5 + tabWidth, tabY + 2, ACCENT_DIM);
        if (font.width(title) <= room) {
            TextScale.text(graphics, font, title, x + 12, tabY + 3, TEXT, false);
        } else {
            ScreenComponents.fittedText(graphics, font, title, x + 12, tabY + 3, room, TEXT, mouseX, mouseY);
        }
    }

    /**
     * Where a panel {@code size} tall starts on a screen {@code available} tall: centred, keeping the title tab on the
     * screen while there is room for it and never pushing the panel's bottom off a screen it fits on.
     */
    public static int top(int available, int size) {
        return LayoutCheck.place(available, size, TAB_HEIGHT);
    }

    /** A flat graphite box with a dark outline and a one-pixel bevel. */
    public static void box(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, OUTLINE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, BEVEL);
        graphics.fill(x + 2, y + 2, x + width - 1, y + height - 1, GRAPHITE);
    }

    /** A flat slot frame around the 16x16 item at ({@code itemX}, {@code itemY}). */
    public static void slot(GuiGraphicsExtractor graphics, int itemX, int itemY) {
        graphics.fill(itemX - 1, itemY - 1, itemX + 17, itemY + 17, SLOT_EDGE);
        graphics.fill(itemX, itemY, itemX + 16, itemY + 16, SLOT);
    }

    /** A vertical gauge filled from the bottom, blue below to cyan on top; {@code fraction} from 0 to 1. */
    public static void verticalBar(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float fraction) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, SLOT_EDGE);
        graphics.fill(x, y, x + width, y + height, SLOT);
        int filled = Math.round(height * Math.clamp(fraction, 0.0F, 1.0F));
        if (filled > 0) {
            graphics.fillGradient(x, y + height - filled, x + width, y + height, BAR_TOP, BAR_BOTTOM);
        }
    }

    /** A horizontal progress track filled from the left, blue to cyan; {@code fraction} from 0 to 1. */
    public static void horizontalBar(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
            float fraction) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, SLOT_EDGE);
        graphics.fill(x, y, x + width, y + height, SLOT);
        int filled = Math.round(width * Math.clamp(fraction, 0.0F, 1.0F));
        for (int i = 0; i < filled; i++) {
            graphics.fill(x + i, y, x + i + 1, y + height, blend(BAR_BOTTOM, BAR_TOP, (float) i / width));
        }
    }

    /**
     * A flat button face; cyan edged when hovered. A label a little wider than the button is drawn smaller to fit (step
     * buttons such as "-100"); one much wider ends with an ellipsis.
     */
    public static void button(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            Component label, boolean hovered) {
        face(graphics, x, y, width, height, hovered);
        label(graphics, font, x, y, width, height, label, TEXT);
    }

    /**
     * {@link #button(GuiGraphicsExtractor, Font, int, int, int, int, Component, boolean)}; while the mouse is on a
     * button whose label was shortened, the whole label shows as a tooltip.
     */
    public static void button(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            Component label, boolean hovered, int mouseX, int mouseY) {
        button(graphics, font, x, y, width, height, label, hovered);
        if (font.width(label) > labelRoom(width)
                && ScreenComponents.mouseOver(graphics, x, y, width, height, mouseX, mouseY)) {
            graphics.setComponentTooltipForNextFrame(font, List.of(label), mouseX, mouseY);
        }
    }

    /** A lit button face (the current mode tab, a toggle that is on); the label is fitted like {@link #button}'s. */
    public static void litButton(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            Component label) {
        litFace(graphics, x, y, width, height);
        label(graphics, font, x, y, width, height, label, TEXT);
    }

    /** A greyed button face for a button that cannot act now; the label is fitted like {@link #button}'s. */
    public static void disabledButton(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            Component label) {
        graphics.fill(x, y, x + width, y + height, SLOT_EDGE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, SLOT);
        label(graphics, font, x, y, width, height, label, LABEL);
    }

    /**
     * The scale a label {@code labelWidth} wide is drawn at in {@code room}: 1 when it fits, smaller when a smaller
     * label fits and stays readable, 0 when it must be cut with an ellipsis instead.
     */
    public static float labelScale(int labelWidth, int room) {
        if (labelWidth <= room) {
            return 1.0F;
        }
        float scale = (float) room / labelWidth;
        return scale >= MIN_LABEL_SCALE ? scale : 0.0F;
    }

    /** The width a button's label may take: two pixels clear of each edge. */
    public static int labelRoom(int width) {
        return Math.max(0, width - 4);
    }

    private static void label(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            Component label, int colour) {
        int room = labelRoom(width);
        int ty = y + (height - 8) / 2;
        int labelWidth = font.width(label);
        float scale = labelScale(labelWidth, room);
        if (scale == 1.0F) {
            TextScale.text(graphics, font, label, x + (width - labelWidth) / 2, ty, colour, false);
            return;
        }
        if (scale > 0) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(x + (width - labelWidth * scale) / 2, ty + 4 * (1 - scale));
            graphics.pose().scale(scale, scale);
            TextScale.text(graphics, font, label, 0, 0, colour, false);
            graphics.pose().popMatrix();
            return;
        }
        String fitted = TextFit.fit(label.getString(), room, font::width);
        TextScale.text(graphics, font, fitted, x + (width - font.width(fitted)) / 2, ty, colour, false);
    }

    /**
     * Draws {@code text} cut to {@code width} with an ellipsis, showing the whole text as a tooltip while the mouse is
     * on it; see {@link ScreenComponents#fittedText}.
     */
    public static void text(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width,
            int colour, int mouseX, int mouseY) {
        ScreenComponents.fittedText(graphics, font, text, x, y, width, colour, mouseX, mouseY);
    }

    /** A button face for a pressed-in state (a toggle that is on, an open tab): accent edge, dim accent inside. */
    public static void litFace(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, ACCENT);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, ACCENT_DIM);
    }

    /** A button face with no label, for icon buttons: cyan edged when hovered. */
    public static void face(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean hovered) {
        graphics.fill(x, y, x + width, y + height, hovered ? ACCENT : SLOT_EDGE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, hovered ? ACCENT_DIM : SLOT);
    }

    /** A square dot {@code size} wide in {@code colour} with a dark rim, for badges and changed-setting marks. */
    public static void dot(GuiGraphicsExtractor graphics, int x, int y, int size, int colour) {
        graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, OUTLINE);
        graphics.fill(x, y, x + size, y + size, colour);
    }

    /** How tall a line of text draws: the line height without the gap below it. */
    public static int textHeight(Font font) {
        return TextScale.lineHeight(font) - 1;
    }

    /** {@code argb} with its alpha replaced by {@code alpha} (0 to 255). */
    public static int withAlpha(int argb, int alpha) {
        return (Math.clamp(alpha, 0, 255) << 24) | (argb & 0x00FFFFFF);
    }

    /** Whether the point is inside the rectangle. */
    public static boolean contains(int x, int y, int width, int height, double px, double py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    private static int blend(int from, int to, float t) {
        int r = channel(from, to, t, 16);
        int g = channel(from, to, t, 8);
        int b = channel(from, to, t, 0);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int channel(int from, int to, float t, int shift) {
        int a = from >> shift & 0xFF;
        int b = to >> shift & 0xFF;
        return Math.round(a + (b - a) * t);
    }
}
