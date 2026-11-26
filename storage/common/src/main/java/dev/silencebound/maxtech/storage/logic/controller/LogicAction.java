package dev.silencebound.maxtech.storage.logic.controller;

import java.util.Optional;

/**
 * One action a graph run asked for, as the controller collected it. Area B only records actions (and logs them at
 * debug); area E executes them. Immutable.
 *
 * @param graph
 *            the index of the graph that asked
 * @param kind
 *            what to do
 * @param label
 *            the target label (the source label for a move); empty is the controller (redstone) or the network's
 *            storage (moves)
 * @param item
 *            the item for craft requests and moves; empty otherwise
 * @param to
 *            a move's destination label; empty is the network's storage; a pool send's member number as {@code #2};
 *            empty for other kinds
 * @param value
 *            on (1) or off (0), the priority, the redstone strength, the amount, 1 for cancelling only the graph's own
 *            crafts, a filter change as slot * 10 + port mode, a shared variable's new value, a stock rule switched on
 *            (1) or off (0), a stock rule's new amount, or what a pool member took (or would take)
 * @param text
 *            a notice's text or a display's text with the values filled in, a shared variable's name, the rule a graph
 *            runs, or which stock amount is set ({@code keep}, {@code restart}, {@code batch}); empty for other kinds
 * @param channels
 *            a notice's channel bits ({@code LogicContext.CHANNEL_*}); 0 for other kinds
 * @param simulated
 *            the graph was in simulate mode: the action was only recorded, nothing in the world changed
 */
public record LogicAction(int graph, Kind kind, String label, Optional<LogicKey> item, String to, long value,
        String text, int channels, boolean simulated) {
    /** The action kinds, one per {@code LogicContext} action call. */
    public enum Kind {
        SWITCH_DEVICE, SET_PRIORITY, REDSTONE, REQUEST_CRAFT, MOVE_ITEMS, NOTIFY, CANCEL_CRAFT, WRITE_DISPLAY, SET_FILTER, SET_VARIABLE, RUN_RULE, CHANNEL, STOCK_SWITCH, STOCK_SET, POOL_SEND, POWER_PRIORITY, SET_TASK
    }

    /** An action that was carried out (or tried). */
    public LogicAction(int graph, Kind kind, String label, Optional<LogicKey> item, String to, long value, String text,
            int channels) {
        this(graph, kind, label, item, to, value, text, channels, false);
    }

    /** This action as only recorded by a graph in simulate mode. */
    LogicAction asSimulated() {
        return new LogicAction(graph, kind, label, item, to, value, text, channels, true);
    }

    /** An action on labelled devices without an item: switch, priority, redstone. */
    static LogicAction device(int graph, Kind kind, String label, long value) {
        return new LogicAction(graph, kind, label, Optional.empty(), "", value, "", 0);
    }

    /** One line for logs and tests, such as {@code "switch_device lamps 1"}. */
    public String describe() {
        StringBuilder out = new StringBuilder(simulated ? "would " : "");
        out.append(kind.name().toLowerCase(java.util.Locale.ROOT));
        if (!label.isEmpty()) {
            out.append(' ').append(label);
        }
        item.ifPresent(key -> out.append(' ').append(key));
        if (!to.isEmpty()) {
            out.append(" -> ").append(to);
        }
        if (kind == Kind.NOTIFY || kind == Kind.WRITE_DISPLAY) {
            out.append(" \"").append(text).append("\" ").append(channels);
        } else if (kind == Kind.RUN_RULE) {
            out.append(" \"").append(text).append('"');
        } else {
            if (kind == Kind.SET_VARIABLE || kind == Kind.STOCK_SET) {
                out.append(' ').append(text);
            }
            out.append(' ').append(value);
        }
        return out.toString();
    }
}
