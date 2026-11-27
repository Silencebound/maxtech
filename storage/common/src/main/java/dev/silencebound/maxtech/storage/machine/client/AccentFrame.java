package dev.silencebound.maxtech.storage.machine.client;

import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Storage's name for the Accent Frame look, which lives in Lib since the GUI pass (B09 item 21a) so every MaxTech
 * screen shares it: palette and drawing are {@link Accent}'s, the widgets are in {@code lib.client.gui.accent}. This
 * class only forwards, so Storage's screens keep their imports; new code uses {@link Accent} directly. Client only.
 */
public final class AccentFrame {
    public static final int OUTLINE = Accent.OUTLINE;
    public static final int GRAPHITE = Accent.GRAPHITE;
    public static final int BEVEL = Accent.BEVEL;
    public static final int SLOT = Accent.SLOT;
    public static final int SLOT_EDGE = Accent.SLOT_EDGE;
    public static final int ACCENT = Accent.ACCENT;
    public static final int ACCENT_DIM = Accent.ACCENT_DIM;
    public static final int TEXT = Accent.TEXT;
    public static final int LABEL = Accent.LABEL;
    public static final int BAR_TOP = Accent.BAR_TOP;
    public static final int BAR_BOTTOM = Accent.BAR_BOTTOM;
    public static final int TAB_HEIGHT = Accent.TAB_HEIGHT;

    private AccentFrame() {
    }

    /** See {@link Accent#panel(GuiGraphicsExtractor, Font, int, int, int, int, Component)}. */
    public static void panel(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            Component title) {
        Accent.panel(graphics, font, x, y, width, height, title);
    }

    /** See {@link Accent#panel(GuiGraphicsExtractor, Font, int, int, int, int, Component, int, int)}. */
    public static void panel(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            Component title, int mouseX, int mouseY) {
        Accent.panel(graphics, font, x, y, width, height, title, mouseX, mouseY);
    }

    /** See {@link Accent#top}. */
    public static int top(int available, int size) {
        return Accent.top(available, size);
    }

    /** See {@link Accent#box}. */
    public static void box(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        Accent.box(graphics, x, y, width, height);
    }

    /** See {@link Accent#slot}. */
    public static void slot(GuiGraphicsExtractor graphics, int itemX, int itemY) {
        Accent.slot(graphics, itemX, itemY);
    }

    /** See {@link Accent#verticalBar}. */
    public static void verticalBar(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float fraction) {
        Accent.verticalBar(graphics, x, y, width, height, fraction);
    }

    /** See {@link Accent#horizontalBar}. */
    public static void horizontalBar(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
            float fraction) {
        Accent.horizontalBar(graphics, x, y, width, height, fraction);
    }

    /** See {@link Accent#button(GuiGraphicsExtractor, Font, int, int, int, int, Component, boolean)}. */
    public static void button(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            Component label, boolean hovered) {
        Accent.button(graphics, font, x, y, width, height, label, hovered);
    }

    /** See {@link Accent#button(GuiGraphicsExtractor, Font, int, int, int, int, Component, boolean, int, int)}. */
    public static void button(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            Component label, boolean hovered, int mouseX, int mouseY) {
        Accent.button(graphics, font, x, y, width, height, label, hovered, mouseX, mouseY);
    }

    /** See {@link Accent#disabledButton}. */
    public static void disabledButton(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
            Component label) {
        Accent.disabledButton(graphics, font, x, y, width, height, label);
    }

    /** See {@link Accent#labelScale}. */
    public static float labelScale(int labelWidth, int room) {
        return Accent.labelScale(labelWidth, room);
    }

    /** See {@link Accent#labelRoom}. */
    public static int labelRoom(int width) {
        return Accent.labelRoom(width);
    }

    /** See {@link Accent#text}. */
    public static void text(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width,
            int colour, int mouseX, int mouseY) {
        Accent.text(graphics, font, text, x, y, width, colour, mouseX, mouseY);
    }
}
