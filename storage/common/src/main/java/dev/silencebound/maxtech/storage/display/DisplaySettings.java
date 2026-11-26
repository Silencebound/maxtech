package dev.silencebound.maxtech.storage.display;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.text.RichText;
import dev.silencebound.maxtech.storage.display.task.TaskBoardRef;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * What one wall screen shows and how: its mode, its source (with the key or tag a count reads), the time window of its
 * graphs, its accent colour and the rich text the {@link DisplaySourceKind#TEXT} source shows. Kept on every panel of
 * the wall and in the panel's item ({@code display_settings} component), so a broken panel or a split wall keeps them.
 * Out-of-range numbers are clamped, never refused, so an edited save or a forged payload cannot make a screen fail.
 *
 * @param windowSeconds
 *            seconds of history a graph shows; the client samples while the screen is in view
 * @param accent
 *            index into {@link #ACCENTS}
 * @param text
 *            markup ({@link DisplayText}), cleaned and cut to {@link RichText#MAX_LENGTH}; kept whatever the source, so
 *            switching sources back and forth keeps it
 * @param chart
 *            what a {@link DisplaySourceKind#HISTORY} screen records and its window; kept whatever the source
 * @param board
 *            the task board a {@link DisplaySourceKind#TASK_BOARD} screen shows; kept whatever the source
 * @param list
 *            the list code ({@code mtlist1:}) a {@link DisplaySourceKind#MATERIAL_LIST} screen counts, cut to
 *            {@link #LIST_MAX} characters; kept whatever the source
 */
public record DisplaySettings(DisplayMode mode, DisplaySourceKind source, Optional<StackKey> item,
        Optional<TagKey<Item>> tag, int windowSeconds, int accent, String text, ChartSettings chart, TaskBoardRef board,
        String list) {
    /** Longest list code a screen keeps (about sixty entries); the screen shows {@link ScreenRows#MAX_ROWS} rows. */
    public static final int LIST_MAX = 2048;
    /** The graph windows the settings GUI offers, in seconds. */
    public static final IntList WINDOWS = IntLists.unmodifiable(IntArrayList.of(30, 60, 120));
    /** Accent colours (RGB): the hologram cyan, then green, amber, magenta and white. */
    public static final IntList ACCENTS = IntLists
            .unmodifiable(IntArrayList.of(0x3CD6F0, 0x5CE08A, 0xF0B44C, 0xE06CD0, 0xE8F4F8));
    public static final DisplaySettings DEFAULT = new DisplaySettings(DisplayMode.VALUE, DisplaySourceKind.POWER_STORED,
            Optional.empty(), Optional.empty(), 60, 0, "", ChartSettings.DEFAULT, TaskBoardRef.DEFAULT, "");

    public static final Codec<DisplaySettings> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(DisplayMode.CODEC.optionalFieldOf("mode", DisplayMode.VALUE).forGetter(DisplaySettings::mode),
                    DisplaySourceKind.CODEC.optionalFieldOf("source", DEFAULT.source())
                            .forGetter(DisplaySettings::source),
                    StackKey.CODEC.optionalFieldOf("item").forGetter(DisplaySettings::item),
                    TagKey.codec(Registries.ITEM).optionalFieldOf("tag").forGetter(DisplaySettings::tag),
                    Codec.INT.optionalFieldOf("window", 60).forGetter(DisplaySettings::windowSeconds),
                    Codec.INT.optionalFieldOf("accent", 0).forGetter(DisplaySettings::accent),
                    Codec.STRING.optionalFieldOf("text", "").forGetter(DisplaySettings::text),
                    ChartSettings.CODEC.optionalFieldOf("chart", ChartSettings.DEFAULT)
                            .forGetter(DisplaySettings::chart),
                    TaskBoardRef.CODEC.optionalFieldOf("board", TaskBoardRef.DEFAULT).forGetter(DisplaySettings::board),
                    Codec.STRING.optionalFieldOf("list", "").forGetter(DisplaySettings::list))
            .apply(instance, DisplaySettings::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, DisplaySettings> STREAM_CODEC = StreamCodec.composite(
            DisplayMode.STREAM_CODEC, DisplaySettings::mode, DisplaySourceKind.STREAM_CODEC, DisplaySettings::source,
            ByteBufCodecs.optional(StackKey.STREAM_CODEC), DisplaySettings::item,
            ByteBufCodecs.optional(TagKey.streamCodec(Registries.ITEM)), DisplaySettings::tag, ByteBufCodecs.VAR_INT,
            DisplaySettings::windowSeconds, ByteBufCodecs.VAR_INT, DisplaySettings::accent,
            ByteBufCodecs.stringUtf8(RichText.MAX_LENGTH), DisplaySettings::text, ChartSettings.STREAM_CODEC,
            DisplaySettings::chart, TaskBoardRef.STREAM_CODEC, DisplaySettings::board,
            ByteBufCodecs.stringUtf8(LIST_MAX), DisplaySettings::list, DisplaySettings::new);

    public DisplaySettings {
        windowSeconds = Mth.clamp(windowSeconds, WINDOWS.getInt(0), WINDOWS.getInt(WINDOWS.size() - 1));
        accent = Mth.clamp(accent, 0, ACCENTS.size() - 1);
        text = RichText.clean(text);
        list = list.length() > LIST_MAX ? list.substring(0, LIST_MAX) : list;
    }

    /** Settings without text. */
    public DisplaySettings(DisplayMode mode, DisplaySourceKind source, Optional<StackKey> item,
            Optional<TagKey<Item>> tag, int windowSeconds, int accent) {
        this(mode, source, item, tag, windowSeconds, accent, "");
    }

    /** Settings without a chart. */
    public DisplaySettings(DisplayMode mode, DisplaySourceKind source, Optional<StackKey> item,
            Optional<TagKey<Item>> tag, int windowSeconds, int accent, String text) {
        this(mode, source, item, tag, windowSeconds, accent, text, ChartSettings.DEFAULT);
    }

    /** Settings without a task board. */
    public DisplaySettings(DisplayMode mode, DisplaySourceKind source, Optional<StackKey> item,
            Optional<TagKey<Item>> tag, int windowSeconds, int accent, String text, ChartSettings chart) {
        this(mode, source, item, tag, windowSeconds, accent, text, chart, TaskBoardRef.DEFAULT, "");
    }

    /** Settings without a list. */
    public DisplaySettings(DisplayMode mode, DisplaySourceKind source, Optional<StackKey> item,
            Optional<TagKey<Item>> tag, int windowSeconds, int accent, String text, ChartSettings chart,
            TaskBoardRef board) {
        this(mode, source, item, tag, windowSeconds, accent, text, chart, board, "");
    }

    public DisplaySettings withMode(DisplayMode mode) {
        return new DisplaySettings(mode, source, item, tag, windowSeconds, accent, text, chart, board, list);
    }

    public DisplaySettings withSource(DisplaySourceKind source) {
        return new DisplaySettings(mode, source, item, tag, windowSeconds, accent, text, chart, board, list);
    }

    /** These settings counting {@code item}, or nothing with null. */
    public DisplaySettings withItem(@Nullable StackKey item) {
        return new DisplaySettings(mode, source, Optional.ofNullable(item), tag, windowSeconds, accent, text, chart,
                board, list);
    }

    /** These settings counting {@code tag}, or no tag with null. */
    public DisplaySettings withTag(@Nullable TagKey<Item> tag) {
        return new DisplaySettings(mode, source, item, Optional.ofNullable(tag), windowSeconds, accent, text, chart,
                board, list);
    }

    public DisplaySettings withWindow(int windowSeconds) {
        return new DisplaySettings(mode, source, item, tag, windowSeconds, accent, text, chart, board, list);
    }

    public DisplaySettings withAccent(int accent) {
        return new DisplaySettings(mode, source, item, tag, windowSeconds, accent, text, chart, board, list);
    }

    /** These settings with the markup {@code text} (cleaned, see {@link RichText#clean}). */
    public DisplaySettings withText(String text) {
        return new DisplaySettings(mode, source, item, tag, windowSeconds, accent, text, chart, board, list);
    }

    /** These settings with the history chart {@code chart}. */
    public DisplaySettings withChart(ChartSettings chart) {
        return new DisplaySettings(mode, source, item, tag, windowSeconds, accent, text, chart, board, list);
    }

    /** These settings showing task board {@code board}. */
    public DisplaySettings withBoard(TaskBoardRef board) {
        return new DisplaySettings(mode, source, item, tag, windowSeconds, accent, text, chart, board, list);
    }

    /** These settings counting the list code {@code list} (cut to {@link #LIST_MAX} characters). */
    public DisplaySettings withList(String list) {
        return new DisplaySettings(mode, source, item, tag, windowSeconds, accent, text, chart, board, list);
    }

    /** The accent as an opaque ARGB colour. */
    public int accentColour() {
        return 0xFF000000 | ACCENTS.getInt(accent);
    }
}
