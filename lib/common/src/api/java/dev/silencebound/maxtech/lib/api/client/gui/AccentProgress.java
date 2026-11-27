package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

/**
 * Work progress in the Accent look, as an arrow that fills from the left or a flame that fills from the bottom. The
 * shape is drawn as pixel art: the unfilled part in the slot edge colour, the filled part in the theme's gauge colours.
 * The tooltip and narration give the percent done and, while working, the seconds left. Values are ticks, read every
 * frame. The widget is the shape's size ({@link Shape#width()} by {@link Shape#height()}). Client thread only.
 */
@ApiStatus.Experimental
public final class AccentProgress extends AccentBar {
    /** Lang key of the percent line, "%s%%". */
    public static final String PERCENT_KEY = "maxtech_lib.gui.progress.percent";
    /** Lang key of the time line, "%s s left". */
    public static final String LEFT_KEY = "maxtech_lib.gui.progress.left";

    /** The two shapes. */
    public enum Shape {
        /** A right-pointing arrow, 22 by 15, filled from the left: crafting, processing. */
        ARROW(arrow()),
        /** A flame, 13 by 14, filled from the bottom: burning, heating. */
        FLAME(new String[]{"......#......", ".....##......", ".....###.....", "....####.....", "....#####....",
                "...######.#..", "...########..", "..##########.", "..##########.", ".############", ".############",
                ".############", "..##########.", "...########.."});

        /** The rows one after another, {@link #width} characters each; {@code #} marks a pixel. */
        private final String mask;
        private final int width;

        Shape(String[] rows) {
            this.mask = String.join("", rows);
            this.width = rows[0].length();
        }

        /** The width in pixels. */
        public int width() {
            return width;
        }

        /** The height in pixels. */
        public int height() {
            return mask.length() / width;
        }

        /** Whether the pixel at ({@code x}, {@code y}), from the top left, belongs to the shape. */
        public boolean covers(int x, int y) {
            return x >= 0 && x < width && y >= 0 && y < height() && mask.charAt(y * width + x) == '#';
        }

        private static String[] arrow() {
            // A shaft five pixels thick, then a head that narrows by a pixel a side per column.
            int width = 22;
            int height = 15;
            int middle = height / 2;
            String[] rows = new String[height];
            for (int y = 0; y < height; y++) {
                StringBuilder row = new StringBuilder(width);
                for (int x = 0; x < width; x++) {
                    int reach = x < 14 ? 2 : middle - (x - 14);
                    row.append(Math.abs(y - middle) <= reach ? '#' : '.');
                }
                rows[y] = row.toString();
            }
            return rows;
        }
    }

    private final Shape shape;
    private final LongSupplier done;
    private final LongSupplier total;

    /** A {@code shape} at ({@code x}, {@code y}) showing {@code done} of {@code total} ticks. */
    public AccentProgress(int x, int y, Shape shape, AccentTheme theme, LongSupplier done, LongSupplier total) {
        super(x, y, shape.width(), shape.height(), theme);
        this.shape = shape;
        this.done = done;
        this.total = total;
    }

    @Override
    public List<Component> tooltip() {
        return tooltip(done.getAsLong(), total.getAsLong());
    }

    /**
     * Progress tooltip lines: the percent done, rounded down so only finished work shows 100, and while work is under
     * way ({@code 0 <= done < total}) the seconds left at 20 ticks a second, to one decimal.
     */
    public static List<Component> tooltip(long done, long total) {
        List<Component> lines = new ArrayList<>(2);
        lines.add(Component.translatable(PERCENT_KEY, percent(done, total)));
        if (total > 0 && done < total) {
            long left = total - Math.max(0, done);
            lines.add(Component.translatable(LEFT_KEY, String.format(Locale.ROOT, "%.1f", left / 20.0)));
        }
        return lines;
    }

    /** {@code done} of {@code total} in whole percent, rounded down; 0 to 100. */
    public static int percent(long done, long total) {
        if (total <= 0 || done <= 0) {
            return 0;
        }
        if (done >= total) {
            return 100;
        }
        return (int) Math.min(99, (long) Math.floor((double) done / total * 100));
    }

    @Override
    void draw(GuiGraphicsExtractor graphics, boolean lit) {
        int width = shape.width();
        int height = shape.height();
        boolean across = shape == Shape.ARROW;
        int filled = fill(done.getAsLong(), total.getAsLong(), across ? width : height);
        int track = edge(lit);
        for (int y = 0; y < height; y++) {
            int colour = Accent.blend(theme.barTop(), theme.barBottom(), height <= 1 ? 0 : (float) y / (height - 1));
            int x = 0;
            while (x < width) {
                if (!shape.covers(x, y)) {
                    x++;
                    continue;
                }
                boolean on = across ? x < filled : y >= height - filled;
                int end = x;
                while (end < width && shape.covers(end, y) && (across ? end < filled : on) == on) {
                    end++;
                }
                graphics.fill(getX() + x, getY() + y, getX() + end, getY() + y + 1, on ? colour : track);
                x = end;
            }
        }
    }
}
