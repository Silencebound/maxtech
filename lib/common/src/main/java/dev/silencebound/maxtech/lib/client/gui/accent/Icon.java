package dev.silencebound.maxtech.lib.client.gui.accent;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * What an {@link IconButton} or a {@link SideTabs} tab shows: a tinted GUI sprite, a pixel glyph drawn with fills, or
 * an item. The button picks the colour (normal, hovered, greyed out); an item ignores it. Client only.
 */
public interface Icon {
    int width();

    int height();

    /** Draws the icon with its top left corner at ({@code x}, {@code y}) in {@code colour}. */
    void draw(GuiGraphicsExtractor graphics, int x, int y, int colour);

    /** Draws the icon centred in the box. */
    default void drawCentred(GuiGraphicsExtractor graphics, int boxX, int boxY, int boxWidth, int boxHeight,
            int colour) {
        draw(graphics, boxX + (boxWidth - width()) / 2, boxY + (boxHeight - height()) / 2, colour);
    }

    /** A GUI atlas sprite {@code width} by {@code height}, drawn white and tinted with the button's colour. */
    static Icon sprite(Identifier sprite, int width, int height) {
        return new Sprite(sprite, width, height);
    }

    /**
     * A pixel glyph: one string per row, {@code '#'} draws a pixel, anything else leaves it clear. The widest row sets
     * the width.
     */
    static Icon glyph(String... rows) {
        int width = 0;
        for (String row : rows) {
            width = Math.max(width, row.length());
        }
        return new Glyph(List.of(rows), width);
    }

    /** A plain colour swatch {@code width} by {@code height} in {@code argb}; the button's colour is ignored. */
    static Icon swatch(int argb, int width, int height) {
        return new Swatch(argb, width, height);
    }

    /** An item drawn 16 x 16 as in a slot; the colour is ignored. */
    static Icon item(ItemStack stack) {
        return new Item(stack.copy());
    }

    /** A tinted sprite. */
    record Sprite(Identifier sprite, int width, int height) implements Icon {
        @Override
        public void draw(GuiGraphicsExtractor graphics, int x, int y, int colour) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height, colour);
        }
    }

    /** A glyph drawn pixel by pixel with fills. */
    record Glyph(List<String> rows, int width) implements Icon {
        @Override
        public int height() {
            return rows.size();
        }

        @Override
        public void draw(GuiGraphicsExtractor graphics, int x, int y, int colour) {
            for (int row = 0; row < rows.size(); row++) {
                String line = rows.get(row);
                for (int col = 0; col < line.length(); col++) {
                    if (line.charAt(col) == '#') {
                        graphics.fill(x + col, y + row, x + col + 1, y + row + 1, colour);
                    }
                }
            }
        }
    }

    /** A colour swatch. */
    record Swatch(int argb, int width, int height) implements Icon {
        @Override
        public void draw(GuiGraphicsExtractor graphics, int x, int y, int colour) {
            graphics.fill(x, y, x + width, y + height, argb);
        }
    }

    /** An item icon. */
    record Item(ItemStack stack) implements Icon {
        @Override
        public int width() {
            return 16;
        }

        @Override
        public int height() {
            return 16;
        }

        @Override
        public void draw(GuiGraphicsExtractor graphics, int x, int y, int colour) {
            graphics.fakeItem(stack, x, y);
        }
    }
}
