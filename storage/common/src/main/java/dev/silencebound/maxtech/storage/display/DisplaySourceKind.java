package dev.silencebound.maxtech.storage.display;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * Which network value a wall screen shows (owner decision 2026-10-03: item count over time, throughput, power and
 * capacity). Counts are pushed by the network's change stream; the rest are sampled from the network's cached stats on
 * the screen's cadence, and only while someone can see the screen.
 */
public enum DisplaySourceKind implements StringRepresentable {
    /** The stored amount of one item or fluid key, through the per-key listener. */
    ITEM_COUNT("item_count", 0, true, "items", false),
    /** The stored amount of every item in an item tag, through the contents watcher. */
    TAG_COUNT("tag_count", 1, true, "items", false),
    /** Operations per second the network made over the last sample. */
    THROUGHPUT("throughput", 2, false, "ops_per_second", false),
    /** FE in the network's store, out of its capacity. */
    POWER_STORED("power_stored", 3, false, "fe", false),
    /** FE per tick that went into the network's store over the last sample. */
    POWER_IN("power_in", 4, false, "fe_per_tick", false),
    /** FE per tick the network spent on operations over the last sample. */
    POWER_OUT("power_out", 5, false, "fe_per_tick", false),
    /** Bytes the network's cells use, out of their capacity. */
    BYTES_USED("bytes_used", 6, false, "bytes", false),
    /** Bytes the network's cells have free, out of their capacity. */
    BYTES_FREE("bytes_free", 7, false, "bytes", false),
    /**
     * The newest notices logic graphs on the network sent with their "displays" channel (M7L), pushed when a notify
     * node posts one. The value is the number of notices posted since the network was built.
     */
    LOGIC_NOTICES("logic_notices", 8, true, "notices", false),
    /**
     * The screen's own rich text ({@link DisplaySettings#text()}, markup in {@link DisplayText}), with live values for
     * its placeholders: counts are pushed like the count sources, sensor values are sampled on the screen's cadence.
     * The value is unused (0).
     */
    TEXT("text", 9, true, "text", false),
    /**
     * A recorded value over hours as a line chart (M7L2 history charts): the screen records its
     * {@link DisplaySettings#chart()} value on the network scheduler at the chart's interval, whether or not anyone
     * looks, and saves the record with the screen. The value is the newest sample; viewers get the chart again after
     * each sample.
     */
    HISTORY("history", 10, true, "value", false),
    /**
     * A task board (M7C item 15): the tasks of the board {@link DisplaySettings#board()} names, ticked by a right-click
     * on their row, done by hand, by an auto goal or by logic ({@link dev.silencebound.maxtech.storage.display.task}).
     * The value is unused (0).
     */
    TASK_BOARD("task_board", 11, true, "tasks", true),
    /**
     * The alarm panel (M7C item 16): the alarms of the network's controller
     * ({@link dev.silencebound.maxtech.storage.controller.alarm.AlarmCenter}), fresh ones first; a right-click on a
     * fresh one's row acknowledges it. The value is unused (0).
     */
    ALARMS("alarms", 12, true, "alarms", true),
    /**
     * The build material list (M7C item 17): the entries of the list code {@link DisplaySettings#list()} with what the
     * network has, needs and lacks of each; a click on the last rows crafts the missing amounts or sends the code as a
     * copyable chat line ({@link dev.silencebound.maxtech.storage.display.material}). The value is unused (0).
     */
    MATERIAL_LIST("material_list", 13, true, "items", true),
    /**
     * Production stats (M7C item 18): items per minute of the network's crafting CPUs, stock keepers and machine pools
     * with a short history, and the items moving in and out most
     * ({@link dev.silencebound.maxtech.storage.display.production}). The value is unused (0).
     */
    PRODUCTION("production", 14, true, "items_per_minute", true);

    private static final DisplaySourceKind[] VALUES = values();
    /** By network id; ids may have gaps (sources added independently), which read as {@link #ITEM_COUNT}. */
    private static final DisplaySourceKind[] BY_ID = byIds();
    public static final Codec<DisplaySourceKind> CODEC = StringRepresentable.fromEnum(DisplaySourceKind::values);
    public static final StreamCodec<ByteBuf, DisplaySourceKind> STREAM_CODEC = ByteBufCodecs
            .idMapper(DisplaySourceKind::byId, DisplaySourceKind::id);

    private final String name;
    private final int id;
    private final boolean pushed;
    private final String unit;
    private final boolean rows;

    DisplaySourceKind(String name, int id, boolean pushed, String unit, boolean rows) {
        this.name = name;
        this.id = id;
        this.pushed = pushed;
        this.unit = unit;
        this.rows = rows;
    }

    private static DisplaySourceKind[] byIds() {
        int most = 0;
        for (DisplaySourceKind kind : VALUES) {
            most = Math.max(most, kind.id);
        }
        DisplaySourceKind[] table = new DisplaySourceKind[most + 1];
        for (DisplaySourceKind kind : VALUES) {
            table[kind.id] = kind;
        }
        return table;
    }

    public int id() {
        return id;
    }

    /** The kind with network id {@code id}; an unknown id reads as {@link #ITEM_COUNT}. */
    public static DisplaySourceKind byId(int id) {
        DisplaySourceKind kind = id >= 0 && id < BY_ID.length ? BY_ID[id] : null;
        return kind == null ? ITEM_COUNT : kind;
    }

    /** The next source in declaration order, after the last the first. */
    public DisplaySourceKind next() {
        int at = 0;
        while (VALUES[at] != this) {
            at++;
        }
        return VALUES[(at + 1) % VALUES.length];
    }

    /**
     * Whether the screen shows a list of rows ({@link ScreenRows}) that a {@link RowFeed} builds on the server, such as
     * a task board; a click on a row goes to the feed.
     */
    public boolean rows() {
        return rows;
    }

    /**
     * Whether changes are pushed to the screen (counts): the screen then sleeps until its key or tag changes instead of
     * sampling on a cadence.
     */
    public boolean pushed() {
        return pushed;
    }

    /** Whether the value is a rate over the last sample, computed from one of the network's running totals. */
    public boolean isRate() {
        return this == THROUGHPUT || this == POWER_IN || this == POWER_OUT;
    }

    /** Whether the value has a maximum the screen shows next to it (stored FE, bytes). */
    public boolean hasMax() {
        return this == POWER_STORED || this == BYTES_USED || this == BYTES_FREE;
    }

    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".display.source." + name;
    }

    /** The lang key of the unit the value is shown in ({@code %s} is the number). */
    public String unitKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".display.unit." + unit;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
