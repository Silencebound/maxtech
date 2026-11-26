package dev.silencebound.maxtech.storage.logic.editor;

import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.Port;
import dev.silencebound.maxtech.storage.logic.PortType;
import java.util.Optional;

/**
 * The live line inside a node that watches something (owner request 2026-10-07): the count a crossing watches and where
 * it stands against the limit, a sensor value, a counter, a latch, the time a delay has left. Which nodes have one is
 * fixed by type, so a node's size does not change when values arrive. The values come from the editor's live sync
 * ({@code EditorPayloads.Live}), only while the editor is open. Pure; the screen and the JUnit tests share it.
 */
public final class LiveLine {
    /**
     * The live entry carrying the value a crossing node watches, which its own outputs do not show while the value
     * crosses no limit (such changes cost no run). Not a port name: port names have no {@code @}.
     */
    public static final String WATCHED = "@watched";
    /** The live entry carrying the game tick a delay node fires at; 0 while it is not counting down. */
    public static final String DUE = "@due";

    /** How the value reads. */
    public enum Format {
        /** A number with thousands grouped. */
        NUMBER,
        /** Yes or no. */
        YES_NO,
        /** A crossing's value against its limit: {@code 1,240 / >100}. */
        CROSSING,
        /** Seconds left until a delay fires, from {@link #DUE}. */
        DUE;

        /** A small number for packets (the controller screen's pinned values); never saved. */
        public int id() {
            return switch (this) {
                case NUMBER -> 0;
                case YES_NO -> 1;
                case CROSSING -> 2;
                case DUE -> 3;
            };
        }

        /** The format with {@link #id()} {@code id}; a plain number for unknown ids. */
        public static Format byId(int id) {
            return switch (id) {
                case 1 -> YES_NO;
                case 2 -> CROSSING;
                case 3 -> DUE;
                default -> NUMBER;
            };
        }
    }

    /** Where a crossing's value stands. */
    public enum Position {
        /** Past the limit: the condition is met whatever it was before. */
        PAST,
        /** Between the limit and the switch-back value: the condition stays as it was. */
        BETWEEN,
        /** Short of the switch-back value: the condition is not met. */
        SHORT
    }

    /**
     * A node type's live line: the live entry it reads ({@link #WATCHED}, {@link #DUE} or an output's name), the line's
     * lang key (one {@code %s}, the value; empty for a crossing, whose line is the value alone) and how the value
     * reads.
     */
    public record Spec(String entry, String key, Format format) {
    }

    private LiveLine() {
    }

    /** The live line of nodes of {@code type}; empty for nodes without one. */
    public static Optional<Spec> of(NodeType type) {
        Spec spec = switch (type.id()) {
            case "count_crosses" -> new Spec(WATCHED, "", Format.CROSSING);
            case "sensor_crosses" -> new Spec(WATCHED, "", Format.CROSSING);
            case "item_count" -> new Spec("count", LogicLang.LIVE_COUNT, Format.NUMBER);
            case "sensor_value" -> new Spec("value", LogicLang.LIVE_VALUE, Format.NUMBER);
            case "device_state" -> new Spec("on", LogicLang.LIVE_ON, Format.YES_NO);
            case "power_changes" -> new Spec("powered", LogicLang.LIVE_POWERED, Format.YES_NO);
            case "redstone_changes", "redstone_strength" ->
                new Spec("strength", LogicLang.LIVE_STRENGTH, Format.NUMBER);
            case "counter" -> new Spec("count", LogicLang.LIVE_COUNT, Format.NUMBER);
            case "latch" -> new Spec("state", LogicLang.LIVE_LATCHED, Format.YES_NO);
            case "hold" -> new Spec("value", LogicLang.LIVE_VALUE, Format.NUMBER);
            case "delay", "timer" -> new Spec(DUE, LogicLang.LIVE_DUE, Format.DUE);
            case "toggle" -> new Spec("state", LogicLang.LIVE_ON, Format.YES_NO);
            case "stats" -> new Spec("average", LogicLang.LIVE_AVERAGE, Format.NUMBER);
            case "change_rate" -> new Spec("rate", LogicLang.LIVE_RATE, Format.NUMBER);
            case "math" -> new Spec("result", LogicLang.LIVE_VALUE, Format.NUMBER);
            case "select" -> new Spec("value", LogicLang.LIVE_VALUE, Format.NUMBER);
            case "daytime", "daytime_changes" -> new Spec("day", LogicLang.LIVE_DAY, Format.YES_NO);
            case "weather", "weather_changes" -> new Spec("weather", LogicLang.LIVE_WEATHER, Format.NUMBER);
            case "fluid_amount" -> new Spec("amount_mb", LogicLang.LIVE_AMOUNT_MB, Format.NUMBER);
            case "energy" -> new Spec("percent", LogicLang.LIVE_FULL, Format.NUMBER);
            case "free_space" -> new Spec("free_bytes", LogicLang.LIVE_FREE_BYTES, Format.NUMBER);
            case "craft_status" -> new Spec("running", LogicLang.LIVE_RUNNING, Format.YES_NO);
            case "count_by_tag" -> new Spec("count", LogicLang.LIVE_COUNT, Format.NUMBER);
            case "pulse_redstone" -> new Spec(DUE, LogicLang.LIVE_PULSE, Format.DUE);
            case "item_moves" -> new Spec("amount", LogicLang.LIVE_AMOUNT, Format.NUMBER);
            case "player_nearby" -> new Spec("players", LogicLang.LIVE_PLAYERS, Format.NUMBER);
            case "shared_variable" -> new Spec("value", LogicLang.LIVE_VALUE, Format.NUMBER);
            case "wait_until" -> new Spec("held", LogicLang.LIVE_HELD, Format.NUMBER);
            case "stock_status" -> new Spec("count", LogicLang.LIVE_COUNT, Format.NUMBER);
            case "stock_short" -> new Spec("short", LogicLang.LIVE_STOCK_SHORT, Format.YES_NO);
            case "pool_status" -> new Spec("idle", LogicLang.LIVE_POOL_IDLE, Format.NUMBER);
            case "sequence" -> new Spec("step", LogicLang.LIVE_STEP, Format.NUMBER);
            case "for_each" -> new Spec("checked", LogicLang.LIVE_CHECKED, Format.NUMBER);
            default -> null;
        };
        return Optional.ofNullable(spec);
    }

    /** Whether nodes of {@code type} have a live line. */
    public static boolean has(NodeType type) {
        return of(type).isPresent();
    }

    /**
     * What a pinned node of {@code type} shows on the controller screen (M7L2 chunk 8): its live line, else its first
     * number or yes/no output as a plain value; empty for nodes with neither (actions, pure events), which cannot be
     * pinned.
     */
    public static Optional<Spec> pin(NodeType type, NodeSettings<?> settings) {
        Optional<Spec> live = of(type);
        if (live.isPresent()) {
            return live;
        }
        for (Port port : type.outputs(settings)) {
            if (port.type() == PortType.NUMBER) {
                return Optional.of(new Spec(port.name(), LogicLang.LIVE_VALUE, Format.NUMBER));
            }
            if (port.type() == PortType.YES_NO) {
                return Optional.of(new Spec(port.name(), LogicLang.LIVE_VALUE, Format.YES_NO));
            }
        }
        return Optional.empty();
    }

    /** Whether a crossing node with {@code settings} looks for values below its limit rather than above. */
    public static boolean below(NodeType type, NodeSettings<?> settings) {
        return "below".equals(SettingRules.choice(type, settings, "mode"));
    }

    /** A crossing node's limit, as its settings give it. */
    public static long threshold(NodeType type, NodeSettings<?> settings) {
        return settings.number("threshold")
                .orElse(type.setting("threshold").map(spec -> spec.defaultValue()).orElse(0L));
    }

    /**
     * A crossing node's switch-back value as the compiler resolves it: the limit when unset, and never past the limit
     * on the wrong side.
     */
    public static long switchBack(NodeType type, NodeSettings<?> settings) {
        long threshold = threshold(type, settings);
        if (settings.number("switch_back").isEmpty()) {
            return threshold;
        }
        long wanted = settings.number("switch_back").getAsLong();
        return below(type, settings) ? Math.max(wanted, threshold) : Math.min(wanted, threshold);
    }

    /**
     * Where {@code value} stands for a crossing that looks {@code below} or above {@code threshold}, switching back at
     * {@code switchBack} (the checks of {@code Checks.state}: above is met over the limit and stops at or under the
     * switch-back value; below the other way round).
     */
    public static Position position(boolean below, long value, long threshold, long switchBack) {
        if (below ? value < threshold : value > threshold) {
            return Position.PAST;
        }
        if (below ? value < switchBack : value > switchBack) {
            return Position.BETWEEN;
        }
        return Position.SHORT;
    }

    /** A crossing's value against its limit: {@code 1,240 / >100}, or {@code 3 / <10} for a below limit. */
    public static String crossing(boolean below, long value, long threshold) {
        return InfoFormat.number(value) + " / " + (below ? "<" : ">") + InfoFormat.number(threshold);
    }

    /** Ticks a delay due at game tick {@code due} has left at {@code now}; never below 0. */
    public static long ticksLeft(long due, long now) {
        return Math.max(0, due - now);
    }

    /** Seconds with at most one decimal, rounded up so a delay never shows 0 before it fires: 70 ticks are 3.5. */
    public static String secondsLeft(long ticks) {
        long tenths = (Math.max(0, ticks) + 1) / 2;
        return InfoFormat.number(tenths / 10) + (tenths % 10 == 0 ? "" : "." + tenths % 10);
    }
}
