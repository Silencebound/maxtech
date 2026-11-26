package dev.silencebound.maxtech.storage.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What a save changed in a graph, in a few words for the rule's change history (M7L2 chunk 9): "added 2 nodes, changed
 * Compare limit 100 -> 200, added a wire". Nodes are matched by id, so a node whose type changed under the same id
 * counts as removed and added. The parts come in a fixed order: nodes added, nodes removed, changed settings (the first
 * {@link #DETAILED_SETTINGS} in graph order with old and new value, then a count of the rest), wires added, wires
 * removed, nodes only moved; "saved without changes" when nothing differs. Pure; allocates; runs once per save.
 */
public final class GraphDiff {
    /** Settings changes spelled out with old and new value; more are counted. */
    public static final int DETAILED_SETTINGS = 3;

    private GraphDiff() {
    }

    /**
     * The parts that describe how {@code after} differs from {@code before}, at most {@link #DETAILED_SETTINGS} + 6.
     *
     * @param items
     *            how an item setting's value reads (an item's lang key, a tag as text)
     */
    public static <K> List<GraphChange> of(LogicGraph<K> before, LogicGraph<K> after,
            Function<K, GraphChange.Arg> items) {
        Map<Integer, LogicNode<K>> old = new HashMap<>();
        for (LogicNode<K> node : before.nodes()) {
            old.put(node.id(), node);
        }
        Set<Integer> kept = new HashSet<>();
        List<LogicNode<K>> added = new ArrayList<>();
        List<GraphChange> settings = new ArrayList<>();
        int moreSettings = 0;
        int moved = 0;
        for (LogicNode<K> node : after.nodes()) {
            LogicNode<K> was = old.get(node.id());
            if (was == null || !was.type().equals(node.type())) {
                added.add(node);
                continue;
            }
            kept.add(node.id());
            if (was.x() != node.x() || was.y() != node.y()) {
                moved++;
            }
            Set<String> names = new TreeSet<>(was.settings().values().keySet());
            names.addAll(node.settings().values().keySet());
            for (String name : names) {
                SettingValue<K> a = was.settings().values().get(name);
                SettingValue<K> b = node.settings().values().get(name);
                Optional<SettingSpec> spec = node.type().setting(name);
                if (same(a, b, spec)) {
                    continue;
                }
                if (settings.size() < DETAILED_SETTINGS) {
                    settings.add(new GraphChange(LogicLang.HISTORY_SETTING,
                            List.of(GraphChange.Arg.key(node.type().nameKey()),
                                    spec.map(s -> GraphChange.Arg.key(s.nameKey()))
                                            .orElseGet(() -> GraphChange.Arg.text(name)),
                                    value(a, spec, items), value(b, spec, items))));
                } else {
                    moreSettings++;
                }
            }
        }
        List<LogicNode<K>> removed = new ArrayList<>();
        for (LogicNode<K> node : before.nodes()) {
            if (!kept.contains(node.id())) {
                removed.add(node);
            }
        }
        Set<Wire> oldWires = new HashSet<>(before.wires());
        Set<Wire> newWires = new HashSet<>(after.wires());
        int wiresAdded = 0;
        for (Wire wire : newWires) {
            if (!oldWires.contains(wire)) {
                wiresAdded++;
            }
        }
        int wiresRemoved = 0;
        for (Wire wire : oldWires) {
            if (!newWires.contains(wire)) {
                wiresRemoved++;
            }
        }

        List<GraphChange> out = new ArrayList<>();
        nodes(out, added, LogicLang.HISTORY_NODE_ADDED, LogicLang.HISTORY_NODES_ADDED);
        nodes(out, removed, LogicLang.HISTORY_NODE_REMOVED, LogicLang.HISTORY_NODES_REMOVED);
        out.addAll(settings);
        if (moreSettings > 0) {
            out.add(GraphChange.of(LogicLang.HISTORY_MORE_SETTINGS, String.valueOf(moreSettings)));
        }
        count(out, wiresAdded, LogicLang.HISTORY_WIRE_ADDED, LogicLang.HISTORY_WIRES_ADDED);
        count(out, wiresRemoved, LogicLang.HISTORY_WIRE_REMOVED, LogicLang.HISTORY_WIRES_REMOVED);
        count(out, moved, LogicLang.HISTORY_NODE_MOVED, LogicLang.HISTORY_NODES_MOVED);
        if (out.isEmpty()) {
            out.add(GraphChange.of(LogicLang.HISTORY_NO_CHANGE));
        }
        return out;
    }

    /** One node names its type ("added a Compare node"); several are counted. */
    private static <K> void nodes(List<GraphChange> out, List<LogicNode<K>> nodes, String one, String many) {
        if (nodes.size() == 1) {
            out.add(new GraphChange(one, List.of(GraphChange.Arg.key(nodes.get(0).type().nameKey()))));
        } else if (nodes.size() > 1) {
            out.add(GraphChange.of(many, String.valueOf(nodes.size())));
        }
    }

    private static void count(List<GraphChange> out, int count, String one, String many) {
        if (count == 1) {
            out.add(GraphChange.of(one));
        } else if (count > 1) {
            out.add(GraphChange.of(many, String.valueOf(count)));
        }
    }

    /** Whether two saved values mean the same: equal, or one unset and the other the spec's default. */
    private static <K> boolean same(@Nullable SettingValue<K> a, @Nullable SettingValue<K> b,
            Optional<SettingSpec> spec) {
        if (a == null ? b == null : a.equals(b)) {
            return true;
        }
        if (spec.isEmpty()) {
            return false;
        }
        SettingValue<K> fallback = fallback(spec.get());
        return fallback != null && (a == null ? fallback : a).equals(b == null ? fallback : b);
    }

    /** What an unset setting means: the default of a number, flag or choice; null for text, labels and items. */
    private static <K> @Nullable SettingValue<K> fallback(SettingSpec spec) {
        return switch (spec.kind()) {
            case NUMBER -> new SettingValue.Num<>(spec.defaultValue());
            case FLAG -> new SettingValue.Flag<>(spec.defaultValue() != 0);
            case CHOICE -> spec.choices().isEmpty()
                    ? null
                    : new SettingValue.Text<>(spec.choices().get((int) spec.defaultValue()));
            default -> null;
        };
    }

    /** A setting's value as the history shows it; an unset value reads as its default, or "(empty)". */
    private static <K> GraphChange.Arg value(@Nullable SettingValue<K> value, Optional<SettingSpec> spec,
            Function<K, GraphChange.Arg> items) {
        @Nullable
        SettingValue<K> shown = value;
        if (shown == null && spec.isPresent()) {
            shown = fallback(spec.get());
        }
        if (shown == null) {
            return GraphChange.Arg.key(LogicLang.HISTORY_EMPTY);
        }
        return switch (shown) {
            case SettingValue.Num<K>(long number) -> GraphChange.Arg.text(String.valueOf(number));
            case SettingValue.Flag<K>(boolean flag) ->
                GraphChange.Arg.key(flag ? LogicLang.WORD_YES : LogicLang.WORD_NO);
            case SettingValue.Text<K>(String text) -> {
                if (text.isEmpty()) {
                    yield GraphChange.Arg.key(LogicLang.HISTORY_EMPTY);
                }
                if (spec.isPresent() && spec.get().kind() == SettingKind.CHOICE) {
                    yield GraphChange.Arg.key(SettingSpec.choiceKey(text));
                }
                yield GraphChange.Arg.text("\"" + text + "\"");
            }
            case SettingValue.Item<K>(K item) -> items.apply(item);
        };
    }
}
