package dev.silencebound.maxtech.storage.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Edits made on a rule card (M7L2 chunk 11), as edits of the graph the card reads: one saved graph, two views. A
 * setting changes one node's setting; removing a line removes what the line reads and the nodes that then feed nothing;
 * adding a trigger, condition or action adds a node wired the way the card reads it:
 *
 * <ul>
 * <li>a trigger goes into every open event input of the actions (an action's "when", or the start of the If, Delay,
 * Cooldown or Wait until chain before it), or into a switch's open on/off with its own yes/no; with no action yet it
 * waits unwired for one;</li>
 * <li>a condition joins the IF chain: AND into the If before each action (an If is put in when there is none) or into a
 * switch's on/off; a Compare comes with an Item count to compare;</li>
 * <li>an action runs after the last action: it takes the same event (or, for a switch, the same on/off).</li>
 * </ul>
 *
 * New nodes are placed in columns beside the nodes they join (triggers left, actions right), below what is there, so
 * the node view stays readable. Every edit returns a new graph or a refusal (a lang key) and never a graph with a wire
 * to a missing node or port, or two wires into one input; the server checks and saves it like an editor save. Pure; any
 * thread.
 */
public final class CardEdits {
    /** Distance between the layout's columns, in editor units: a wide node and room for wires. */
    static final int COLUMN = 180;
    /** Distance between nodes placed under each other. */
    static final int ROW = 70;

    public static final String REFUSED_GONE = LogicLang.add("card.edit.gone",
            "That part of the rule changed; look at the card again");
    public static final String REFUSED_VALUE = LogicLang.add("card.edit.value", "That value does not fit this setting");
    public static final String REFUSED_WIRED = LogicLang.add("card.edit.wired",
            "A wire sets this value; change it in the editor");
    public static final String REFUSED_REMOVE = LogicLang.add("card.edit.remove",
            "This line can't be removed on the card; open the editor");
    public static final String REFUSED_TYPE = LogicLang.add("card.edit.type", "That node does not fit there");
    public static final String REFUSED_NO_SLOT = LogicLang.add("card.edit.no_slot",
            "No action can take another trigger here; open the editor to wire it");
    public static final String REFUSED_NEED_ACTION = LogicLang.add("card.edit.need_action",
            "Add an action first; a condition decides when it runs");

    // History lines of card edits; the argument is the node's name.
    public static final String HISTORY_TRIGGER = LogicLang.add("history.card_trigger",
            "added the trigger %s on the card");
    public static final String HISTORY_CONDITION = LogicLang.add("history.card_condition",
            "added the condition %s on the card");
    public static final String HISTORY_ACTION = LogicLang.add("history.card_action", "added the action %s on the card");
    public static final String HISTORY_REMOVED = LogicLang.add("history.card_removed",
            "removed the %s line on the card");
    public static final String HISTORY_NEW = LogicLang.add("history.card_new", "started the rule as a card");

    private CardEdits() {
    }

    /** Loads the class so its lang lines are declared; {@link LogicLang#english()} calls it. */
    static void init() {
    }

    /** What a "+" button on the card adds. */
    public enum Add {
        TRIGGER, CONDITION, ACTION
    }

    /**
     * An edit's outcome: the new graph, or the lang key of why the edit was refused.
     *
     * @param <K>
     *            what item settings hold
     * @param graph
     *            the edited graph; empty when refused
     * @param refusal
     *            the refusal's lang key; empty when the edit applied
     */
    public record Result<K>(Optional<LogicGraph<K>> graph, String refusal) {
        static <K> Result<K> ok(LogicGraph<K> graph) {
            return new Result<>(Optional.of(graph), "");
        }

        static <K> Result<K> refused(String key) {
            return new Result<>(Optional.empty(), key);
        }
    }

    // ---- which node types each "+" offers

    /** The node types the "+" button {@code add} offers, in palette order; the screen applies Common / All. */
    public static List<NodeType> choices(Add add) {
        List<NodeType> out = new ArrayList<>();
        for (NodeType type : NodeTypes.all()) {
            if (offers(add, type)) {
                out.add(type);
            }
        }
        return out;
    }

    /** Whether {@code add} offers {@code type}. */
    public static boolean offers(Add add, NodeType type) {
        return switch (add) {
            case TRIGGER -> type.category() == NodeCategory.EVENTS && eventOutput(type) != null;
            case ACTION -> type.category() == NodeCategory.ACT;
            case CONDITION -> condition(type);
        };
    }

    /**
     * A condition: a read, check or memory with a yes/no output that needs no wire to work (every required input has a
     * setting of its name), or a Compare, which gets an Item count.
     */
    private static boolean condition(NodeType type) {
        NodeCategory category = type.category();
        if (category != NodeCategory.READ && category != NodeCategory.CHECK && category != NodeCategory.REMEMBER) {
            return false;
        }
        if (yesNoOutput(type) == null) {
            return false;
        }
        if (type == NodeTypes.COMPARE) {
            return true;
        }
        for (Port port : type.inputs()) {
            if (port.required() && type.setting(port.name()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ---- what a draft lacks

    /**
     * Whether {@code graph} has an event node (a card's WHEN) or a step sequence, which moves by itself when its
     * conditions change.
     */
    public static boolean hasTrigger(LogicGraph<?> graph) {
        for (LogicNode<?> node : graph.nodes()) {
            if (node.type().category() == NodeCategory.EVENTS || node.type() == NodeTypes.SEQUENCE) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code graph} has an action (a card's THEN). */
    public static boolean hasAction(LogicGraph<?> graph) {
        for (LogicNode<?> node : graph.nodes()) {
            if (node.type().category() == NodeCategory.ACT) {
                return true;
            }
        }
        return false;
    }

    // ---- settings

    /**
     * Whether the card may edit setting {@code setting} of {@code node} in {@code graph}: the type has it and no wire
     * goes into the input of the same name.
     */
    public static <K> boolean editable(LogicGraph<K> graph, LogicNode<K> node, String setting) {
        if (node.type().setting(setting).isEmpty()) {
            return false;
        }
        for (Wire wire : graph.wires()) {
            if (wire.to() == node.id() && wire.input().equals(setting)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The value a click on a choice or yes/no setting gives: the next choice (after the last, the first) or the other
     * answer. Empty for other kinds, which take a typed or picked value.
     */
    public static <K> Optional<SettingValue<K>> next(LogicNode<K> node, String setting) {
        SettingSpec spec = node.type().setting(setting).orElse(null);
        if (spec == null) {
            return Optional.empty();
        }
        NodeSettings<K> settings = node.settings();
        return switch (spec.kind()) {
            case CHOICE -> {
                int index = spec.choiceIndex(settings.text(setting).orElse(""));
                index = Math.clamp(index, 0, spec.choices().size() - 1);
                yield Optional.of(new SettingValue.Text<>(spec.choices().get((index + 1) % spec.choices().size())));
            }
            case FLAG -> Optional.of(new SettingValue.Flag<>(!settings.flag(setting).orElse(spec.defaultValue() != 0)));
            default -> Optional.empty();
        };
    }

    /**
     * {@code graph} with setting {@code setting} of node {@code node} set to {@code value}: a number is kept within the
     * setting's bounds; a choice must be one of its choices, a text or label at most its length. Refused when the node
     * or setting is gone, a wire sets the value, or the value does not fit.
     */
    public static <K> Result<K> set(LogicGraph<K> graph, int node, String setting, SettingValue<K> value) {
        LogicNode<K> target = graph.node(node);
        SettingSpec spec = target == null ? null : target.type().setting(setting).orElse(null);
        if (target == null || spec == null) {
            return Result.refused(REFUSED_GONE);
        }
        if (!editable(graph, target, setting)) {
            return Result.refused(REFUSED_WIRED);
        }
        if (!spec.kind().accepts(value)) {
            return Result.refused(REFUSED_VALUE);
        }
        SettingValue<K> fitted = value;
        switch (value) {
            case SettingValue.Num<K>(long number) -> fitted = new SettingValue.Num<>(spec.clamp(number));
            case SettingValue.Text<K>(String text) -> {
                int most = switch (spec.kind()) {
                    case LABEL -> NetworkRef.MAX_REF;
                    case CHOICE -> text.length();
                    default -> SettingSpec.MAX_TEXT;
                };
                if (text.length() > most || (spec.kind() == SettingKind.CHOICE && !spec.choices().contains(text))) {
                    return Result.refused(REFUSED_VALUE);
                }
            }
            default -> {
            }
        }
        List<LogicNode<K>> nodes = new ArrayList<>(graph.nodes().size());
        LogicNode<K> changed = target.withSettings(target.settings().with(setting, fitted));
        for (LogicNode<K> each : graph.nodes()) {
            nodes.add(each.id() == node ? changed : each);
        }
        // A setting can take ports away (a sequence's step count): their wires go with them.
        List<Wire> wires = new ArrayList<>(graph.wires().size());
        for (Wire wire : graph.wires()) {
            if ((wire.to() != node || hasPort(changed.type().inputs(changed.settings()), wire.input()))
                    && (wire.from() != node || hasPort(changed.type().outputs(changed.settings()), wire.output()))) {
                wires.add(wire);
            }
        }
        return Result.ok(new LogicGraph<>(nodes, wires));
    }

    private static boolean hasPort(List<Port> ports, String name) {
        for (Port port : ports) {
            if (port.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ---- removing a line

    /**
     * {@code graph} without the line of {@code section} about node {@code node}: a THEN line's action, a WHEN line's
     * event node, or an IF line's check (taken out of the And, Or, Not, If or switch it joins; an If whose condition
     * goes is replaced by its event). The nodes that fed only what was removed go too, except event nodes. REMEMBER,
     * LET and custom lines, a check an Otherwise branch reads and a "turns yes" step are refused.
     */
    public static <K> Result<K> remove(LogicGraph<K> graph, RuleCard.Section section, int node) {
        Draft<K> draft = new Draft<>(graph);
        LogicNode<K> target = draft.node(node);
        if (target == null) {
            return Result.refused(REFUSED_GONE);
        }
        NodeCategory category = target.type().category();
        switch (section) {
            case THEN, WHEN -> {
                if (category != (section == RuleCard.Section.THEN ? NodeCategory.ACT : NodeCategory.EVENTS)) {
                    return Result.refused(REFUSED_REMOVE);
                }
                Set<Integer> sources = draft.sources(node);
                draft.removeNode(node);
                draft.prune(sources);
            }
            case IF -> {
                if (!detach(draft, node, 0)) {
                    return Result.refused(REFUSED_REMOVE);
                }
                draft.prune(Set.of(node));
            }
            default -> {
                return Result.refused(REFUSED_REMOVE);
            }
        }
        return Result.ok(draft.graph());
    }

    /**
     * Takes check {@code id} out of every And, Or, Either, Not, If condition or switch on/off its yes/no outputs feed.
     * False when it feeds none of them, or something else the card cannot take apart.
     */
    private static <K> boolean detach(Draft<K> draft, int id, int depth) {
        LogicNode<K> check = draft.node(id);
        if (check == null || depth > 16) {
            return false;
        }
        boolean any = false;
        for (Wire wire : new ArrayList<>(draft.from(id))) {
            Port port = output(check, wire.output());
            if (port == null || port.type() != PortType.YES_NO) {
                continue;
            }
            LogicNode<K> consumer = draft.node(wire.to());
            if (consumer == null) {
                return false;
            }
            NodeType type = consumer.type();
            if (type == NodeTypes.AND || type == NodeTypes.OR || type == NodeTypes.XOR) {
                Wire other = draft.into(consumer.id(), "a".equals(wire.input()) ? "b" : "a");
                draft.removeWire(wire);
                if (other == null) {
                    if (!detach(draft, consumer.id(), depth + 1)) {
                        return false;
                    }
                } else {
                    // The gate gives way to its other input.
                    for (Wire out : new ArrayList<>(draft.from(consumer.id()))) {
                        draft.removeWire(out);
                        draft.wire(other.from(), other.output(), out.to(), out.input());
                    }
                }
                draft.removeNode(consumer.id());
            } else if (type == NodeTypes.NOT) {
                draft.removeWire(wire);
                if (!detach(draft, consumer.id(), depth + 1)) {
                    return false;
                }
                draft.removeNode(consumer.id());
            } else if (type == NodeTypes.IF && "condition".equals(wire.input())) {
                for (Wire out : draft.from(consumer.id())) {
                    if ("otherwise".equals(out.output())) {
                        return false;
                    }
                }
                Wire when = draft.into(consumer.id(), "when");
                for (Wire out : new ArrayList<>(draft.from(consumer.id()))) {
                    draft.removeWire(out);
                    if (when != null) {
                        draft.wire(when.from(), when.output(), out.to(), out.input());
                    }
                }
                draft.removeNode(consumer.id());
            } else if (type.category() == NodeCategory.ACT && "on".equals(wire.input())) {
                draft.removeWire(wire);
            } else {
                return false;
            }
            any = true;
        }
        return any;
    }

    // ---- adding

    /** {@code graph} with a new node of {@code type} added by the "+" button {@code add}. */
    public static <K> Result<K> add(LogicGraph<K> graph, Add add, NodeType type) {
        if (!offers(add, type)) {
            return Result.refused(REFUSED_TYPE);
        }
        return switch (add) {
            case TRIGGER -> addTrigger(graph, type);
            case CONDITION -> addCondition(graph, type);
            case ACTION -> addAction(graph, type);
        };
    }

    /** An input an event or a yes/no could go into: a node's input and what it carries. */
    private record Slot(int node, String input, PortType type) {
    }

    private static <K> Result<K> addTrigger(LogicGraph<K> graph, NodeType type) {
        Draft<K> draft = new Draft<>(graph);
        List<LogicNode<K>> actions = draft.actions();
        LogicNode<K> first = draft.first(NodeCategory.EVENTS);
        int x = first != null ? first.x() : draft.empty() ? 0 : draft.minX() - COLUMN;
        int id = draft.add(type, x, draft.below(x));
        if (actions.isEmpty()) {
            return Result.ok(draft.graph());
        }
        String event = eventOutput(type);
        String yesNo = yesNoOutput(type);
        boolean wired = false;
        for (Slot slot : openSlots(draft, actions)) {
            String output = slot.type() == PortType.EVENT ? event : yesNo;
            if (output != null) {
                draft.wire(id, output, slot.node(), slot.input());
                wired = true;
            }
        }
        return wired ? Result.ok(draft.graph()) : Result.refused(REFUSED_NO_SLOT);
    }

    /**
     * Where a trigger could go: for each action with an event input, the open start of the chain before it (its own
     * input, or an If's, Delay's, Cooldown's or Wait until's); for a switch with an open on/off, that.
     */
    private static <K> List<Slot> openSlots(Draft<K> draft, List<LogicNode<K>> actions) {
        List<Slot> slots = new ArrayList<>();
        for (LogicNode<K> action : actions) {
            if (hasInput(action.type(), "do", PortType.EVENT)) {
                int node = action.id();
                String input = "do";
                for (int guard = 0; guard < 64; guard++) {
                    Wire wire = draft.into(node, input);
                    if (wire == null) {
                        Slot slot = new Slot(node, input, PortType.EVENT);
                        if (!slots.contains(slot)) {
                            slots.add(slot);
                        }
                        break;
                    }
                    LogicNode<K> source = draft.node(wire.from());
                    String next = source == null ? null : chainInput(source.type());
                    if (source == null || next == null) {
                        break;
                    }
                    node = source.id();
                    input = next;
                }
            } else if (hasInput(action.type(), "on", PortType.YES_NO) && draft.into(action.id(), "on") == null) {
                slots.add(new Slot(action.id(), "on", PortType.YES_NO));
            }
        }
        return slots;
    }

    /** The event input of a step the card reads before an action (If, Delay, Cooldown, Wait until); else null. */
    private static @Nullable String chainInput(NodeType type) {
        if (type == NodeTypes.IF) {
            return "when";
        }
        if (type == NodeTypes.DELAY) {
            return "start";
        }
        if (type == NodeTypes.COOLDOWN || type == NodeTypes.WAIT_UNTIL) {
            return "in";
        }
        return null;
    }

    private static <K> Result<K> addCondition(LogicGraph<K> graph, NodeType type) {
        Draft<K> draft = new Draft<>(graph);
        List<LogicNode<K>> actions = draft.actions();
        if (actions.isEmpty()) {
            return Result.refused(REFUSED_NEED_ACTION);
        }
        LogicNode<K> lastAction = actions.getLast();
        int x = lastAction.x() - 2 * COLUMN;
        String output = yesNoOutput(type);
        if (output == null) {
            return Result.refused(REFUSED_TYPE);
        }
        int check = draft.add(type, x, draft.below(x));
        if (type == NodeTypes.COMPARE) {
            LogicNode<K> compare = draft.node(check);
            int countX = x - COLUMN;
            int count = draft.add(NodeTypes.ITEM_COUNT, countX, compare == null ? 0 : compare.y());
            draft.wire(count, "count", check, NodeTypes.VALUE);
        }
        Set<Integer> joinedIfs = new HashSet<>();
        Map<String, Integer> newIfs = new HashMap<>();
        List<LogicNode<K>> waiting = new ArrayList<>();
        for (LogicNode<K> action : actions) {
            if (hasInput(action.type(), "do", PortType.EVENT)) {
                Wire event = draft.into(action.id(), "do");
                if (event == null) {
                    waiting.add(action);
                    continue;
                }
                LogicNode<K> source = draft.node(event.from());
                if (source != null && source.type() == NodeTypes.IF && "then".equals(event.output())) {
                    if (joinedIfs.add(source.id())) {
                        andInto(draft, source.id(), "condition", check, output);
                    }
                    continue;
                }
                String key = event.from() + "/" + event.output();
                Integer gate = newIfs.get(key);
                if (gate == null) {
                    gate = draft.add(NodeTypes.IF, action.x() - COLUMN, action.y());
                    draft.wire(event.from(), event.output(), gate, "when");
                    draft.wire(check, output, gate, "condition");
                    newIfs.put(key, gate);
                }
                draft.removeWire(event);
                draft.wire(gate, "then", action.id(), "do");
            } else if (hasInput(action.type(), "on", PortType.YES_NO)) {
                andInto(draft, action.id(), "on", check, output);
            }
        }
        if (!waiting.isEmpty()) {
            // Actions without a trigger yet: an If waits for one in front of them.
            LogicNode<K> firstWaiting = waiting.getFirst();
            int gate = draft.add(NodeTypes.IF, firstWaiting.x() - COLUMN, firstWaiting.y());
            draft.wire(check, output, gate, "condition");
            for (LogicNode<K> action : waiting) {
                draft.wire(gate, "then", action.id(), "do");
            }
        }
        return Result.ok(draft.graph());
    }

    /** Wires {@code check} into {@code input} of {@code target}, through a new And when something is wired there. */
    private static <K> void andInto(Draft<K> draft, int target, String input, int check, String output) {
        Wire existing = draft.into(target, input);
        if (existing == null) {
            draft.wire(check, output, target, input);
            return;
        }
        LogicNode<K> at = draft.node(target);
        int x = (at == null ? 0 : at.x()) - COLUMN;
        int and = draft.add(NodeTypes.AND, x, draft.below(x));
        draft.removeWire(existing);
        draft.wire(existing.from(), existing.output(), and, "a");
        draft.wire(check, output, and, "b");
        draft.wire(and, "result", target, input);
    }

    private static <K> Result<K> addAction(LogicGraph<K> graph, NodeType type) {
        Draft<K> draft = new Draft<>(graph);
        List<LogicNode<K>> actions = draft.actions();
        LogicNode<K> last = actions.isEmpty() ? null : actions.getLast();
        int x = last != null ? last.x() : draft.empty() ? 2 * COLUMN : draft.maxX() + COLUMN;
        int id = draft.add(type, x, draft.below(x));
        LogicNode<K> trigger = draft.first(NodeCategory.EVENTS);
        if (hasInput(type, "do", PortType.EVENT)) {
            Wire same = null;
            Wire level = null;
            for (int i = actions.size() - 1; i >= 0 && same == null; i--) {
                LogicNode<K> action = actions.get(i);
                same = draft.into(action.id(), "do");
                if (level == null && hasInput(action.type(), "on", PortType.YES_NO)) {
                    level = draft.into(action.id(), "on");
                }
            }
            if (same != null) {
                draft.wire(same.from(), same.output(), id, "do");
            } else if (trigger != null) {
                String event = eventOutput(trigger.type());
                if (event != null && level != null) {
                    // Switches follow a check; the new action runs on the trigger when that check is yes.
                    int gate = draft.add(NodeTypes.IF, x - COLUMN, draft.below(x - COLUMN));
                    draft.wire(trigger.id(), event, gate, "when");
                    draft.wire(level.from(), level.output(), gate, "condition");
                    draft.wire(gate, "then", id, "do");
                } else if (event != null) {
                    draft.wire(trigger.id(), event, id, "do");
                }
            }
        } else if (hasInput(type, "on", PortType.YES_NO)) {
            Wire same = null;
            Wire condition = null;
            for (int i = actions.size() - 1; i >= 0 && same == null; i--) {
                LogicNode<K> action = actions.get(i);
                same = draft.into(action.id(), "on");
                Wire event = condition == null ? draft.into(action.id(), "do") : null;
                LogicNode<K> source = event == null ? null : draft.node(event.from());
                if (source != null && source.type() == NodeTypes.IF) {
                    condition = draft.into(source.id(), "condition");
                }
            }
            Wire from = same != null ? same : condition;
            String yesNo = trigger == null ? null : yesNoOutput(trigger.type());
            if (from != null) {
                draft.wire(from.from(), from.output(), id, "on");
            } else if (trigger != null && yesNo != null) {
                draft.wire(trigger.id(), yesNo, id, "on");
            }
        }
        return Result.ok(draft.graph());
    }

    // ---- ports

    private static @Nullable String eventOutput(NodeType type) {
        for (Port port : type.outputs(NodeSettings.empty())) {
            if (port.type() == PortType.EVENT) {
                return port.name();
            }
        }
        return null;
    }

    private static @Nullable String yesNoOutput(NodeType type) {
        for (Port port : type.outputs(NodeSettings.empty())) {
            if (port.type() == PortType.YES_NO) {
                return port.name();
            }
        }
        return null;
    }

    private static boolean hasInput(NodeType type, String name, PortType portType) {
        for (Port port : type.inputs()) {
            if (port.name().equals(name) && port.type() == portType) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Port output(LogicNode<?> node, String name) {
        for (Port port : node.type().outputs(node.settings())) {
            if (port.name().equals(name)) {
                return port;
            }
        }
        return null;
    }

    /** A graph being edited: nodes by id in graph order and the wires. */
    private static final class Draft<K> {
        private final Map<Integer, LogicNode<K>> nodes = new LinkedHashMap<>();
        private final List<Wire> wires;

        Draft(LogicGraph<K> graph) {
            for (LogicNode<K> node : graph.nodes()) {
                nodes.putIfAbsent(node.id(), node);
            }
            wires = new ArrayList<>(graph.wires());
        }

        LogicGraph<K> graph() {
            return new LogicGraph<>(new ArrayList<>(nodes.values()), wires);
        }

        boolean empty() {
            return nodes.isEmpty();
        }

        @Nullable
        LogicNode<K> node(int id) {
            return nodes.get(id);
        }

        /** The actions in graph order. */
        List<LogicNode<K>> actions() {
            List<LogicNode<K>> out = new ArrayList<>();
            for (LogicNode<K> node : nodes.values()) {
                if (node.type().category() == NodeCategory.ACT) {
                    out.add(node);
                }
            }
            return out;
        }

        @Nullable
        LogicNode<K> first(NodeCategory category) {
            for (LogicNode<K> node : nodes.values()) {
                if (node.type().category() == category) {
                    return node;
                }
            }
            return null;
        }

        int minX() {
            int min = Integer.MAX_VALUE;
            for (LogicNode<K> node : nodes.values()) {
                min = Math.min(min, node.x());
            }
            return min == Integer.MAX_VALUE ? 0 : min;
        }

        int maxX() {
            int max = Integer.MIN_VALUE;
            for (LogicNode<K> node : nodes.values()) {
                max = Math.max(max, node.x());
            }
            return max == Integer.MIN_VALUE ? 0 : max;
        }

        /** The y under every node near column {@code x}; the top of the graph when the column is free. */
        int below(int x) {
            int y = Integer.MIN_VALUE;
            int top = Integer.MAX_VALUE;
            for (LogicNode<K> node : nodes.values()) {
                top = Math.min(top, node.y());
                if (Math.abs(node.x() - x) < COLUMN / 2) {
                    y = Math.max(y, node.y() + ROW);
                }
            }
            return y != Integer.MIN_VALUE ? y : top == Integer.MAX_VALUE ? 0 : top;
        }

        /** Adds a node of {@code type} with default settings at ({@code x}, {@code y}); returns its new id. */
        int add(NodeType type, int x, int y) {
            int id = 1;
            for (Integer each : nodes.keySet()) {
                id = Math.max(id, each + 1);
            }
            nodes.put(id, new LogicNode<>(id, type, x, y, NodeSettings.empty()));
            return id;
        }

        void removeNode(int id) {
            nodes.remove(id);
            wires.removeIf(wire -> wire.from() == id || wire.to() == id);
        }

        void wire(int from, String output, int to, String input) {
            wires.removeIf(wire -> wire.to() == to && wire.input().equals(input));
            wires.add(new Wire(from, output, to, input));
        }

        void removeWire(Wire wire) {
            wires.remove(wire);
        }

        /** The wire into {@code input} of node {@code node}, or null. */
        @Nullable
        Wire into(int node, String input) {
            for (Wire wire : wires) {
                if (wire.to() == node && wire.input().equals(input)) {
                    return wire;
                }
            }
            return null;
        }

        /** The wires out of node {@code node}. */
        List<Wire> from(int node) {
            List<Wire> out = new ArrayList<>();
            for (Wire wire : wires) {
                if (wire.from() == node) {
                    out.add(wire);
                }
            }
            return out;
        }

        /** The nodes wired into node {@code node}. */
        Set<Integer> sources(int node) {
            Set<Integer> out = new HashSet<>();
            for (Wire wire : wires) {
                if (wire.to() == node) {
                    out.add(wire.from());
                }
            }
            return out;
        }

        /**
         * Removes the nodes among {@code start} that feed nothing now, then the same for what fed them; event nodes and
         * actions stay, since they have their own lines.
         */
        void prune(Set<Integer> start) {
            List<Integer> todo = new ArrayList<>(start);
            while (!todo.isEmpty()) {
                int id = todo.removeLast();
                LogicNode<K> node = nodes.get(id);
                if (node == null || node.type().category() == NodeCategory.EVENTS
                        || node.type().category() == NodeCategory.ACT || node.type() == NodeTypes.SEQUENCE
                        || !from(id).isEmpty()) {
                    continue;
                }
                todo.addAll(sources(id));
                removeNode(id);
            }
        }
    }
}
