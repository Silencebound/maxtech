package dev.silencebound.maxtech.storage.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rules with parameters (M7L2 row 19). A rule declares a parameter with a Parameter node: a name, a kind (number,
 * yes/no, item or tag, label) and a default value, which the node outputs like a Constant. Two ways fill parameters
 * with other values:
 *
 * <ul>
 * <li>A <b>copy with values</b> (the controller's instance of a rule): the template rule's graph with each parameter
 * node's value replaced by the copy's own ({@link #apply}). The copy is an ordinary graph after that, so it watches its
 * own items and runs on its own events.</li>
 * <li>A <b>call</b>: a Run another rule node names parameters of the rule it runs for its item and amount. In a rule
 * that has a "Run by another rule" node and parameters, every node that reads the event or a parameter, directly or
 * through others, runs once per call with the call's values, like a For each branch ({@link ForEach}); an item
 * parameter reaches the reads and actions there the way a For each's item does.</li>
 * </ul>
 *
 * <p>
 * Names are matched as {@link #key} keys them: trimmed and lower case. Pure; any thread.
 */
public final class Parameters {
    /** Most parameters one rule declares; a call fills them from fixed arrays. */
    public static final int MAX = 8;

    /** Kind index: a number. Same order as the Constant's kind choice. */
    public static final int NUMBER = 0;
    /** Kind index: yes or no. */
    public static final int YES_NO = 1;
    /** Kind index: an item or a tag. */
    public static final int ITEM = 2;
    /** Kind index: a device label. */
    public static final int LABEL = 3;

    private Parameters() {
    }

    /**
     * One parameter of a rule.
     *
     * @param <K>
     *            what items are
     * @param node
     *            the id of its Parameter node
     * @param key
     *            its name as {@link #key} keys it
     * @param name
     *            its name as the player typed it
     * @param kind
     *            {@link #NUMBER}, {@link #YES_NO}, {@link #ITEM} or {@link #LABEL}
     * @param value
     *            its value in the graph (the default, or a copy's own); empty for an item that is not set
     */
    public record Param<K>(int node, String key, String name, int kind, Optional<SettingValue<K>> value) {
    }

    /**
     * What {@link #apply} made: the graph with the values in, and the problems found, each naming the parameter.
     *
     * @param <K>
     *            what items are
     * @param graph
     *            the template's graph with the values set where they fit
     * @param errors
     *            a value for a parameter the template does not have, or of another kind
     */
    public record Applied<K>(LogicGraph<K> graph, List<GraphError> errors) {
        public Applied {
            errors = List.copyOf(errors);
        }
    }

    /** A parameter's name as calls and copies match it: trimmed, lower case, at most 32 characters. */
    public static String key(String name) {
        return LogicCompiler.variableName(name);
    }

    /** The kind index of a Parameter (or Constant) node with {@code settings}. */
    public static int kind(NodeSettings<?> settings) {
        return NodeTypes.PARAMETER.setting(NodeTypes.KIND)
                .map(spec -> spec.choiceIndex(settings.text(NodeTypes.KIND).orElse(""))).orElse(NUMBER);
    }

    /** The setting that holds the value of a parameter of kind {@code kind}. */
    public static String valueSetting(int kind) {
        return switch (kind) {
            case YES_NO -> NodeTypes.YES;
            case ITEM -> NodeTypes.ITEM_NAME;
            case LABEL -> NodeTypes.LABEL_NAME;
            default -> NodeTypes.VALUE;
        };
    }

    /** The kind's choice id, as the kind setting saves it. */
    public static String kindId(int kind) {
        return switch (kind) {
            case YES_NO -> "yes_no";
            case ITEM -> "item";
            case LABEL -> "label";
            default -> "number";
        };
    }

    /** Whether {@code value} is a value a parameter of kind {@code kind} takes. */
    public static boolean fits(int kind, SettingValue<?> value) {
        return switch (kind) {
            case YES_NO -> value instanceof SettingValue.Flag<?>;
            case ITEM -> value instanceof SettingValue.Item<?>;
            case LABEL -> value instanceof SettingValue.Text<?>;
            default -> value instanceof SettingValue.Num<?>;
        };
    }

    /** Whether {@code graph} declares any parameter. */
    public static <K> boolean any(LogicGraph<K> graph) {
        for (LogicNode<K> node : graph.nodes()) {
            if (node.type() == NodeTypes.PARAMETER) {
                return true;
            }
        }
        return false;
    }

    /**
     * The parameters {@code graph} declares, in node order; of nodes sharing a key only the first (the compiler marks
     * the others). Allocates.
     */
    public static <K> List<Param<K>> of(LogicGraph<K> graph) {
        List<Param<K>> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LogicNode<K> node : graph.nodes()) {
            if (node.type() != NodeTypes.PARAMETER) {
                continue;
            }
            String name = node.settings().text(NodeTypes.NAME).orElse("").strip();
            String key = key(name);
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            int kind = kind(node.settings());
            found.add(new Param<>(node.id(), key, name, kind, value(node, kind)));
        }
        return List.copyOf(found);
    }

    /** The parameter of {@code graph} keyed {@code key} ({@link #key}); empty when it has none. */
    public static <K> Optional<Param<K>> find(LogicGraph<K> graph, String key) {
        for (Param<K> param : of(graph)) {
            if (param.key().equals(key)) {
                return Optional.of(param);
            }
        }
        return Optional.empty();
    }

    /** The value a Parameter node of kind {@code kind} holds now: its setting, the spec's default when unset. */
    private static <K> Optional<SettingValue<K>> value(LogicNode<K> node, int kind) {
        NodeSettings<K> settings = node.settings();
        return switch (kind) {
            case YES_NO -> Optional.of(new SettingValue.Flag<>(settings.flag(NodeTypes.YES).orElse(true)));
            case ITEM -> settings.item(NodeTypes.ITEM_NAME).map(SettingValue.Item::new);
            case LABEL -> Optional.of(new SettingValue.Text<>(settings.text(NodeTypes.LABEL_NAME).orElse("")));
            default -> {
                SettingSpec spec = NodeTypes.PARAMETER.setting(NodeTypes.VALUE).orElseThrow();
                yield Optional.of(new SettingValue.Num<>(spec.clamp(settings.number(NodeTypes.VALUE).orElse(0))));
            }
        };
    }

    /** Each parameter's key with its value in {@code graph} (the defaults), unset items left out. Allocates. */
    public static <K> Map<String, SettingValue<K>> values(LogicGraph<K> graph) {
        Map<String, SettingValue<K>> values = new HashMap<>();
        for (Param<K> param : of(graph)) {
            param.value().ifPresent(value -> values.put(param.key(), value));
        }
        return values;
    }

    /**
     * {@code template} with each parameter's value replaced by the one {@code values} gives for its key; parameters
     * without one keep the template's default. A value for a key the template has no parameter for, or of another kind
     * than the parameter now is, is an error naming the parameter ({@link LogicLang#PARAM_UNKNOWN},
     * {@link LogicLang#PARAM_KIND}); the rest still goes in. Numbers are clamped to the value range. Allocates.
     */
    public static <K> Applied<K> apply(LogicGraph<K> template, Map<String, SettingValue<K>> values) {
        List<GraphError> errors = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<LogicNode<K>> nodes = new ArrayList<>(template.nodes().size());
        for (LogicNode<K> node : template.nodes()) {
            if (node.type() != NodeTypes.PARAMETER) {
                nodes.add(node);
                continue;
            }
            String key = key(node.settings().text(NodeTypes.NAME).orElse(""));
            SettingValue<K> value = values.get(key);
            if (value == null || key.isEmpty()) {
                nodes.add(node);
                continue;
            }
            used.add(key);
            int kind = kind(node.settings());
            if (!fits(kind, value)) {
                errors.add(new GraphError(node.id(), valueSetting(kind), LogicLang.PARAM_KIND, key));
                nodes.add(node);
                continue;
            }
            SettingValue<K> clean = value instanceof SettingValue.Num<K>(long number)
                    ? new SettingValue.Num<>(NodeTypes.PARAMETER.setting(NodeTypes.VALUE).orElseThrow().clamp(number))
                    : value;
            nodes.add(node.withSettings(node.settings().with(valueSetting(kind), clean)));
        }
        for (String key : values.keySet().stream().sorted().toList()) {
            if (!used.contains(key)) {
                errors.add(GraphError.graph(LogicLang.PARAM_UNKNOWN, key));
            }
        }
        return new Applied<>(new LogicGraph<>(nodes, template.wires()), errors);
    }
}
