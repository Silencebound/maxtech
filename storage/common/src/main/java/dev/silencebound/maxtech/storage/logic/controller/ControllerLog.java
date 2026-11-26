package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.GraphError;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jspecify.annotations.Nullable;

/**
 * What the Logic Controller screen's rule detail and LOG tab show (M7L2 chunk 10), built from data the controller
 * already keeps: per graph its name, when it last ran, how many actions that run asked for, those actions and the run's
 * why trace, its compile errors and its pinned values (M7L2 chunk 8); and the network's newest notices. Sent by the
 * open menu only when a run, an edit, a notice or a pinned value changed something, at most every {@link #INTERVAL}
 * ticks ({@link LogicControllerMenu#logUpdate}). Actions of a rule in simulate mode read "Would ...". Building
 * allocates; it never runs while nothing changed. Server thread for building.
 */
public final class ControllerLog {
    /** Ticks between two updates of one open screen. */
    public static final int INTERVAL = 10;
    /** The newest network notices sent. */
    public static final int MAX_NOTICES = 16;
    /** Lines per rule: its last run's actions and why trace (the trace holds at most {@link RunTrace#CAPACITY}). */
    public static final int MAX_LINES = 20;
    /** Compile errors sent per rule. */
    public static final int MAX_ERRORS = 8;
    private static final int MAX_KEY = 256;
    private static final int MAX_TEXT = 256;

    private ControllerLog() {
    }

    /**
     * One line of a why chain or an error list: a lang key filled with three texts and an item. Args go in as
     * {@code %1$s} to {@code %4$s}; the item is a lang key ({@code itemKey}) or, without one, a literal
     * ({@code itemText}).
     *
     * @param node
     *            the lang key of the node type the line is about, or empty for none
     * @param key
     *            the line's lang key
     * @param a
     *            the first argument (a label, a value, an error's argument, a notice's text)
     * @param b
     *            the second argument (an amount or a limit)
     * @param c
     *            the third argument (a destination label, a display's text)
     * @param itemKey
     *            the item's lang key, or empty
     * @param itemText
     *            the item's text when it has no lang key (a tag), or empty
     */
    public record Line(String node, String key, String a, String b, String c, String itemKey, String itemText) {
        public static final StreamCodec<ByteBuf, Line> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_KEY), Line::node, ByteBufCodecs.stringUtf8(MAX_KEY), Line::key,
                ByteBufCodecs.stringUtf8(MAX_TEXT), Line::a, ByteBufCodecs.stringUtf8(MAX_TEXT), Line::b,
                ByteBufCodecs.stringUtf8(MAX_TEXT), Line::c, ByteBufCodecs.stringUtf8(MAX_KEY), Line::itemKey,
                ByteBufCodecs.stringUtf8(MAX_TEXT), Line::itemText, Line::new);

        public Line {
            a = cut(a);
            b = cut(b);
            c = cut(c);
            itemText = cut(itemText);
        }

        static Line of(String node, String key, String a, String b) {
            return new Line(node, key, a, b, "", "", "");
        }
    }

    /**
     * One pinned value of a rule (M7L2 chunk 8), formatted on the client like the node's live line in the editor.
     *
     * @param node
     *            the lang key of the node type
     * @param key
     *            the line's lang key, one {@code %s} for the value (a crossing shows the value alone)
     * @param format
     *            the {@code LiveLine.Format} id
     * @param value
     *            the value, a due tick for a countdown, or {@link Long#MIN_VALUE} when not known
     * @param limit
     *            a crossing's limit; 0 otherwise
     * @param below
     *            a crossing looks for values below its limit
     */
    public record Pin(String node, String key, int format, long value, long limit, boolean below) {
        public static final StreamCodec<ByteBuf, Pin> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_KEY), Pin::node, ByteBufCodecs.stringUtf8(MAX_KEY), Pin::key,
                ByteBufCodecs.VAR_INT, Pin::format, ByteBufCodecs.VAR_LONG, Pin::value, ByteBufCodecs.VAR_LONG,
                Pin::limit, ByteBufCodecs.BOOL, Pin::below, Pin::new);
    }

    /**
     * One rule's part of the log.
     *
     * @param name
     *            the rule's name; empty when it has none
     * @param lastRun
     *            the game time of its last run, or -1 when it has not run since it was saved or loaded
     * @param runs
     *            its runs since it was saved or loaded
     * @param actions
     *            how many actions its last run asked for
     * @param lines
     *            the last run's actions, then its why trace
     * @param errors
     *            its compile errors; not empty while it is paused for them
     * @param pins
     *            its pinned values, at most {@link GraphSlot#MAX_PINS}
     */
    public record Rule(String name, long lastRun, long runs, int actions, List<Line> lines, List<Line> errors,
            List<Pin> pins) {
        public static final StreamCodec<ByteBuf, Rule> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(GraphSlot.MAX_NAME), Rule::name, ByteBufCodecs.VAR_LONG, Rule::lastRun,
                ByteBufCodecs.VAR_LONG, Rule::runs, ByteBufCodecs.VAR_INT, Rule::actions,
                Line.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_LINES)), Rule::lines,
                Line.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ERRORS)), Rule::errors,
                Pin.STREAM_CODEC.apply(ByteBufCodecs.list(GraphSlot.MAX_PINS)), Rule::pins, Rule::new);

        public Rule {
            lines = List.copyOf(lines);
            errors = List.copyOf(errors);
            pins = List.copyOf(pins);
        }

        /** A rule without pinned values. */
        public Rule(String name, long lastRun, long runs, int actions, List<Line> lines, List<Line> errors) {
            this(name, lastRun, runs, actions, lines, errors, List.of());
        }
    }

    /** Server to client: the log of the open controller menu {@code containerId}. */
    public record Update(int containerId, List<Rule> rules,
            List<NetworkNotices.Notice> notices) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(MaxtechStorage.id("logic_controller_log"));
        public static final StreamCodec<ByteBuf, Update> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Update::containerId, Rule.STREAM_CODEC.apply(ByteBufCodecs.list(LogicControllerMenu.ROWS)),
                Update::rules, NetworkNotices.Notice.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_NOTICES)),
                Update::notices, Update::new);

        public Update {
            rules = List.copyOf(rules);
            notices = List.copyOf(notices);
        }

        @Override
        public Type<Update> type() {
            return TYPE;
        }
    }

    /** Registers the update packet: the client keeps it in its open controller menu. */
    static void register() {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Update.TYPE, Update.STREAM_CODEC,
                (payload, player) -> {
                    if (player.containerMenu instanceof LogicControllerMenu menu
                            && menu.containerId == payload.containerId()) {
                        menu.acceptLog(payload);
                    }
                });
    }

    /** The log of {@code worker}'s first {@link LogicControllerMenu#ROWS} graphs and {@code network}'s notices. */
    static Update build(int containerId, LogicWorker worker, @Nullable NetworkState network) {
        int count = Math.min(worker.size(), LogicControllerMenu.ROWS);
        List<Rule> rules = new ArrayList<>(count);
        List<LogicAction> actions = worker.actions();
        for (int g = 0; g < count; g++) {
            LogicGraph<LogicKey> graph = worker.graph(g);
            List<Line> lines = new ArrayList<>();
            int asked = worker.lastActions(g);
            // The last run's actions are the newest ones of this graph in the kept list.
            List<LogicAction> mine = new ArrayList<>();
            for (int i = actions.size() - 1; i >= 0 && mine.size() < asked; i--) {
                if (actions.get(i).graph() == g) {
                    mine.add(actions.get(i));
                }
            }
            for (int i = mine.size() - 1; i >= 0 && lines.size() < MAX_LINES; i--) {
                lines.add(action(mine.get(i)));
            }
            Optional<LogicEvaluator<LogicKey>> evaluator = worker.evaluator(g);
            long runs = evaluator.map(LogicEvaluator::runs).orElse(0L);
            if (evaluator.isPresent()) {
                RunTrace trace = evaluator.get().trace();
                for (int i = 0; i < trace.size() && lines.size() < MAX_LINES - 1; i++) {
                    String first = trace.text(i).isEmpty() ? InfoFormat.number(trace.first(i)) : trace.text(i);
                    lines.add(Line.of(nodeKey(graph, trace.node(i)), trace.reason(i), first,
                            InfoFormat.number(trace.second(i))));
                }
                if (trace.dropped() > 0) {
                    lines.add(Line.of("", ControllerTexts.LOG_MORE, InfoFormat.number(trace.dropped()), ""));
                }
            }
            List<Line> errors = new ArrayList<>();
            for (GraphError error : worker.errors(g)) {
                if (errors.size() >= MAX_ERRORS) {
                    break;
                }
                errors.add(Line.of(nodeKey(graph, error.node()), error.key(), error.arg(), ""));
            }
            List<Pin> pins = new ArrayList<>(worker.pinCount(g));
            for (int i = 0; i < worker.pinCount(g) && i < GraphSlot.MAX_PINS; i++) {
                pins.add(worker.pin(g, i));
            }
            rules.add(new Rule(worker.name(g), worker.lastRun(g), runs, asked, lines, errors, pins));
        }
        List<NetworkNotices.Notice> notices = network == null ? List.of() : network.notices().newest(MAX_NOTICES);
        return new Update(containerId, rules, notices);
    }

    /** The lang key of node {@code id}'s type in {@code graph}; empty for the whole graph or an unknown id. */
    private static String nodeKey(LogicGraph<LogicKey> graph, int id) {
        for (LogicNode<LogicKey> node : graph.nodes()) {
            if (node.id() == id) {
                return node.type().nameKey();
            }
        }
        return "";
    }

    /** {@code action} as a line: what the rule asked for, in plain words. */
    static Line action(LogicAction action) {
        String label = action.label();
        String amount = InfoFormat.number(action.value());
        String itemKey = "";
        String itemText = "";
        if (action.item().isPresent()) {
            LogicKey item = action.item().get();
            switch (item) {
                case LogicKey.Stack(StackKey key) -> {
                    itemKey = key instanceof ItemKey stack ? stack.item().getDescriptionId() : "";
                    itemText = key.toString();
                }
                case LogicKey.Tag(var tag) -> itemText = "#" + tag.location();
                case LogicKey.FluidTag(var tag) -> itemText = "#" + tag.location();
            }
        }
        String key = switch (action.kind()) {
            case SWITCH_DEVICE ->
                action.value() != 0 ? ControllerTexts.ACTION_SWITCH_ON : ControllerTexts.ACTION_SWITCH_OFF;
            case SET_PRIORITY -> ControllerTexts.ACTION_PRIORITY;
            case REDSTONE -> label.isEmpty() ? ControllerTexts.ACTION_REDSTONE_SELF : ControllerTexts.ACTION_REDSTONE;
            case REQUEST_CRAFT -> ControllerTexts.ACTION_CRAFT;
            case MOVE_ITEMS -> label.isEmpty()
                    ? ControllerTexts.ACTION_MOVE_OUT
                    : action.to().isEmpty() ? ControllerTexts.ACTION_MOVE_IN : ControllerTexts.ACTION_MOVE;
            case NOTIFY -> ControllerTexts.ACTION_NOTIFY;
            case CANCEL_CRAFT ->
                action.value() != 0 ? ControllerTexts.ACTION_CANCEL_OWN : ControllerTexts.ACTION_CANCEL;
            case WRITE_DISPLAY -> ControllerTexts.ACTION_DISPLAY;
            case SET_FILTER -> ControllerTexts.ACTION_FILTER;
            case SET_VARIABLE -> ControllerTexts.ACTION_VARIABLE;
            case RUN_RULE -> ControllerTexts.ACTION_RUN_RULE;
            case STOCK_SWITCH -> action.item().isPresent()
                    ? (action.value() != 0 ? ControllerTexts.ACTION_STOCK_ON : ControllerTexts.ACTION_STOCK_OFF)
                    : (action.value() != 0
                            ? ControllerTexts.ACTION_STOCK_ALL_ON
                            : ControllerTexts.ACTION_STOCK_ALL_OFF);
            case STOCK_SET -> switch (action.text()) {
                case "restart" -> ControllerTexts.ACTION_STOCK_RESTART;
                case "batch" -> ControllerTexts.ACTION_STOCK_BATCH;
                default -> ControllerTexts.ACTION_STOCK_KEEP;
            };
            case CHANNEL -> ControllerTexts.ACTION_CHANNEL;
            case POOL_SEND -> ControllerTexts.ACTION_POOL;
            case POWER_PRIORITY -> ControllerTexts.ACTION_POWER_PRIORITY;
            case SET_TASK -> action.value() != 0 ? ControllerTexts.ACTION_TASK_DONE : ControllerTexts.ACTION_TASK_OPEN;
        };
        if (action.simulated()) {
            key = ControllerTexts.would(key);
        }
        LogicAction.Kind kind = action.kind();
        String a = kind == LogicAction.Kind.NOTIFY || kind == LogicAction.Kind.SET_VARIABLE
                || kind == LogicAction.Kind.RUN_RULE || kind == LogicAction.Kind.SET_TASK ? action.text() : label;
        return new Line("", key, a, amount,
                action.kind() == LogicAction.Kind.WRITE_DISPLAY ? action.text() : action.to(), itemKey, itemText);
    }

    private static String cut(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }
}
