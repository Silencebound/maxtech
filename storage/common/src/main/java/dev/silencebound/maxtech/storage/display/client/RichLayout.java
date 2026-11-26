package dev.silencebound.maxtech.storage.display.client;

import dev.silencebound.maxtech.lib.text.RichText;
import dev.silencebound.maxtech.storage.display.DisplayText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.function.IntFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Rich text ({@link RichText}) laid out in font pixels for one width: each line wrapped at word boundaries into rows (a
 * word wider than a row is broken), runs of text merged into one styled sequence per stretch between icons, icons as
 * squares one line high. The world's screens ({@link ScreenPainter}) and the editor's preview draw the same layout,
 * scaled to their own size. Built once per text, width, language and value change and then only drawn. Client only.
 */
public final class RichLayout {
    /** An icon's square in font pixels, at the normal size; it takes one more pixel of space after it. */
    public static final int ICON = 9;
    /** A row's height in font pixels at the normal size (the font's line plus one pixel). */
    public static final int ROW = 10;

    /** One piece of a row: text, or an item's icon. {@code x} is in the row's own pixels. */
    public static final class Piece {
        public final int x;
        public final @Nullable FormattedCharSequence text;
        public final ItemStack icon;
        /** The icon's model, filled by the world renderer each frame; null for text and unknown items. */
        public final @Nullable ItemStackRenderState model;

        Piece(int x, @Nullable FormattedCharSequence text, ItemStack icon) {
            this.x = x;
            this.text = text;
            this.icon = icon;
            this.model = icon.isEmpty() ? null : new ItemStackRenderState();
        }
    }

    /**
     * One row: its pieces, alignment and scale; {@code width} in its own pixels (without trailing spaces), {@code top}
     * in normal-size pixels from the top of the layout.
     */
    public record Row(List<Piece> pieces, RichText.Align align, float scale, int width, float top) {
        /** The row's height in normal-size pixels. */
        public float height() {
            return ROW * scale;
        }
    }

    private final List<Row> rows;
    private final float height;
    private final int width;

    private RichLayout(List<Row> rows, float height, int width) {
        this.rows = rows;
        this.height = height;
        this.width = width;
    }

    public List<Row> rows() {
        return rows;
    }

    /** The layout's height in normal-size pixels. */
    public float height() {
        return height;
    }

    /** The width it was laid out for, in normal-size pixels. */
    public int width() {
        return width;
    }

    /** Where a row starts, in normal-size pixels from the left edge, for its alignment. */
    public float left(Row row) {
        float used = row.width() * row.scale();
        return switch (row.align()) {
            case LEFT -> 0;
            case CENTER -> Math.max(0, (width - used) / 2);
            case RIGHT -> Math.max(0, width - used);
        };
    }

    /**
     * Lays {@code text} out {@code width} normal-size pixels wide. {@code value} gives the text shown for placeholder
     * {@code n}; {@code maxRows} stops after that many rows (0 for no limit). An icon of an item that does not exist
     * takes its space and draws nothing.
     */
    public static RichLayout build(RichText text, IntFunction<String> value, Font font, int width, int maxRows) {
        Builder builder = new Builder(font, Math.max(1, width), maxRows);
        for (RichText.Line line : text.lines()) {
            if (builder.full()) {
                break;
            }
            builder.line(line, value);
        }
        return new RichLayout(builder.rows, builder.top, Math.max(1, width));
    }

    private static final class Builder {
        private final Font font;
        private final int width;
        private final int maxRows;
        final ObjectArrayList<Row> rows = new ObjectArrayList<>();
        float top;
        private RichText.@Nullable Line line;
        private int avail;
        private ObjectArrayList<Piece> pieces = new ObjectArrayList<>();
        private int x;
        /** Width of the spaces at the end of the row so far, left out of its width. */
        private int trailing;
        private @Nullable MutableComponent segment;
        private int segmentX;

        Builder(Font font, int width, int maxRows) {
            this.font = font;
            this.width = width;
            this.maxRows = maxRows;
        }

        boolean full() {
            return maxRows > 0 && rows.size() >= maxRows;
        }

        void line(RichText.Line current, IntFunction<String> value) {
            line = current;
            avail = Math.max(1, (int) (width / current.size().scale()));
            for (RichText.Run run : current.runs()) {
                if (run instanceof RichText.Text text) {
                    words(text.text(), DisplayText.style(text.style()));
                } else if (run instanceof RichText.Value placeholder) {
                    String shown = value.apply(placeholder.index());
                    if (!shown.isEmpty()) {
                        atom(shown, DisplayText.style(placeholder.style()));
                    }
                } else if (run instanceof RichText.Icon icon) {
                    icon(icon);
                }
                if (full()) {
                    return;
                }
            }
            endRow();
        }

        /** Text split after each run of spaces, so rows break between words. */
        private void words(String text, Style style) {
            int start = 0;
            int i = 0;
            while (i < text.length()) {
                boolean space = text.charAt(i) == ' ';
                i++;
                if (space && (i == text.length() || text.charAt(i) != ' ')) {
                    atom(text.substring(start, i), style);
                    start = i;
                }
            }
            if (start < text.length()) {
                atom(text.substring(start), style);
            }
        }

        private void atom(String word, Style style) {
            if (full()) {
                return;
            }
            Component part = Component.literal(word).withStyle(style);
            int w = font.width(part);
            if (x + w > avail && x > 0) {
                endRow();
                if (full() || word.isBlank()) {
                    return;
                }
            }
            if (w > avail) {
                // A word wider than a whole row: as much as fits on each row.
                String rest = word;
                while (!rest.isEmpty() && !full()) {
                    FormattedText head = font.substrByWidth(Component.literal(rest).withStyle(style), avail - x);
                    int length = Math.max(1, head.getString().length());
                    if (length < rest.length() && Character.isHighSurrogate(rest.charAt(length - 1))) {
                        length++;
                    }
                    String piece = rest.substring(0, Math.min(length, rest.length()));
                    rest = rest.substring(piece.length());
                    append(piece, style, font.width(Component.literal(piece).withStyle(style)));
                    if (!rest.isEmpty()) {
                        endRow();
                    }
                }
                return;
            }
            append(word, style, w);
        }

        private void append(String text, Style style, int w) {
            MutableComponent current = segment;
            if (current == null) {
                current = Component.empty();
                segment = current;
                segmentX = x;
            }
            current.append(Component.literal(text).withStyle(style));
            x += w;
            int kept = text.stripTrailing().length();
            trailing = kept == text.length()
                    ? 0
                    : kept == 0 ? trailing + w : font.width(Component.literal(text.substring(kept)).withStyle(style));
        }

        private void icon(RichText.Icon icon) {
            if (x + ICON > avail && x > 0) {
                endRow();
                if (full()) {
                    return;
                }
            }
            flush();
            Item item = DisplayText.item(icon.id());
            pieces.add(new Piece(x, null, item == null ? ItemStack.EMPTY : new ItemStack(item)));
            x += ICON + 1;
            trailing = 1;
        }

        private void flush() {
            MutableComponent current = segment;
            if (current != null) {
                pieces.add(new Piece(segmentX, current.getVisualOrderText(), ItemStack.EMPTY));
                segment = null;
            }
        }

        private void endRow() {
            RichText.Line current = line;
            if (current == null || full()) {
                return;
            }
            flush();
            float scale = current.size().scale();
            rows.add(new Row(List.copyOf(pieces), current.align(), scale, Math.max(0, x - trailing), top));
            top += ROW * scale;
            pieces = new ObjectArrayList<>();
            x = 0;
            trailing = 0;
        }
    }
}
