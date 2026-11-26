package dev.silencebound.maxtech.storage.display;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;

/**
 * What a list screen shows (M7C round 3: task board, alarm panel, build material list, production stats): a title and a
 * status on top, an optional fill bar under them, and rows, each with a tone, an optional checkbox, item icon, fill bar
 * and short history, its text on the left and a note on the right. The server builds it ({@link RowFeed}) and sends it
 * when it changed; the client lays it out with {@link #rowAt} and the painter's same numbers, so a click on a row is
 * found on the server without asking the client. Texts are components, so lang keys travel and a dedicated server needs
 * no translations. Immutable.
 *
 * @param fill
 *            the header bar in per mille, {@link #NO_FILL} for none
 */
public record ScreenRows(Component title, Component status, int fill, List<Row> rows) {
    /** Most rows one screen gets; a wall shows as many as fit. */
    public static final int MAX_ROWS = 32;
    /** Most points of a row's history. */
    public static final int MAX_SPARK = 16;
    public static final int NO_FILL = -1;
    /** A row's time when it has none. */
    public static final long NO_TIME = Long.MIN_VALUE;

    /** Tones: the row's colour. */
    public static final int NORMAL = 0;
    public static final int DIM = 1;
    public static final int GOOD = 2;
    public static final int WARN = 3;
    public static final int BAD = 4;
    private static final int TONE_MASK = 0xF;
    /** Style bits beside the tone: a checkbox, ticked, struck-through text, a tinted band behind the row. */
    public static final int BOX = 1 << 4;
    public static final int CHECKED = 1 << 5;
    public static final int STRIKE = 1 << 6;
    public static final int BAND = 1 << 7;

    public static final ScreenRows EMPTY = new ScreenRows(Component.empty(), Component.empty(), NO_FILL, List.of());

    /**
     * One row.
     *
     * @param style
     *            a tone ({@link #NORMAL} to {@link #BAD}) with the style bits
     * @param icon
     *            an item drawn before the text
     * @param fill
     *            a bar under the row in per mille, {@link #NO_FILL} for none
     * @param spark
     *            a short history drawn as small bars before the note, oldest first; empty for none
     * @param time
     *            a game time the client shows as an age after the note ("5 min"), {@link #NO_TIME} for none
     */
    public record Row(int style, Optional<Item> icon, Component text, Component note, int fill, List<Long> spark,
            long time) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Row> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Row::style, ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.ITEM)),
                Row::icon, ComponentSerialization.TRUSTED_STREAM_CODEC, Row::text,
                ComponentSerialization.TRUSTED_STREAM_CODEC, Row::note, ByteBufCodecs.VAR_INT, Row::fill,
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list(MAX_SPARK)), Row::spark, ByteBufCodecs.VAR_LONG,
                Row::time, Row::new);

        public Row {
            fill = fill < 0 ? NO_FILL : Math.min(fill, 1000);
            spark = spark.size() > MAX_SPARK
                    ? List.copyOf(spark.subList(spark.size() - MAX_SPARK, spark.size()))
                    : List.copyOf(spark);
        }

        /** A plain row: text and note in {@code style}, nothing else. */
        public static Row of(int style, Component text, Component note) {
            return new Row(style, Optional.empty(), text, note, NO_FILL, List.of(), NO_TIME);
        }

        public int tone() {
            return style & TONE_MASK;
        }

        public boolean has(int bit) {
            return (style & bit) != 0;
        }

        public Row withIcon(Item item) {
            return new Row(style, Optional.of(item), text, note, fill, spark, time);
        }

        public Row withFill(int fill) {
            return new Row(style, icon, text, note, fill, spark, time);
        }

        public Row withSpark(List<Long> spark) {
            return new Row(style, icon, text, note, fill, spark, time);
        }

        public Row withTime(long time) {
            return new Row(style, icon, text, note, fill, spark, time);
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenRows> STREAM_CODEC = StreamCodec.composite(
            ComponentSerialization.TRUSTED_STREAM_CODEC, ScreenRows::title, ComponentSerialization.TRUSTED_STREAM_CODEC,
            ScreenRows::status, ByteBufCodecs.VAR_INT, ScreenRows::fill,
            Row.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ROWS)), ScreenRows::rows, ScreenRows::new);

    public ScreenRows {
        fill = fill < 0 ? NO_FILL : Math.min(fill, 1000);
        rows = rows.size() > MAX_ROWS ? List.copyOf(rows.subList(0, MAX_ROWS)) : List.copyOf(rows);
    }

    /** {@code part} of {@code whole} in per mille, 0 to 1000; {@link #NO_FILL} without a whole. */
    public static int perMille(long part, long whole) {
        return whole <= 0 ? NO_FILL : (int) Math.clamp((long) (1000.0 * Math.max(0, part) / whole), 0, 1000);
    }

    // Layout, shared by the painter and the server's click lookup. Lengths are in blocks in the screen's plane, x to
    // the right and y up from the bottom-left corner of the screen's glass; bezel is the model frame's width.

    /** Height of a row's text: smaller than a value screen's label, so a wall holds more rows. */
    public static float label(float w, float h) {
        return Mth.clamp(0.075F * Math.min(w, h), 0.065F, 0.14F);
    }

    /** Distance from the glass's edge to the picture: the frame and the border gap of the other screens. */
    public static float margin(float bezel) {
        return bezel + 0.025F;
    }

    /** Distance between the tops of two rows. */
    public static float step(float label) {
        return label * 1.35F;
    }

    /** Where the title's text starts, from the bottom. */
    public static float titleBottom(float w, float h, float bezel) {
        return h - margin(bezel) - label(w, h) * 1.5F;
    }

    /** The top of the first row, below the title and its bar. */
    public static float rowsTop(float w, float h, float bezel) {
        return h - margin(bezel) - label(w, h) * 2.3F;
    }

    /** How many rows a screen of this size shows. */
    public static int fit(float w, float h, float bezel) {
        float label = label(w, h);
        return Math.max(0, (int) ((rowsTop(w, h, bezel) - margin(bezel)) / step(label)));
    }

    /** The top of row {@code index}, from the bottom. */
    public static float rowTop(float w, float h, float bezel, int index) {
        return rowsTop(w, h, bezel) - index * step(label(w, h));
    }

    /** The row shown at height {@code y} (from the bottom) of a screen of this size, or -1 for none. */
    public static int rowAt(float w, float h, float bezel, float y) {
        float top = rowsTop(w, h, bezel);
        if (y > top || y < margin(bezel)) {
            return -1;
        }
        int index = (int) ((top - y) / step(label(w, h)));
        return index < fit(w, h, bezel) ? index : -1;
    }
}
