package dev.silencebound.maxtech.storage.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import dev.silencebound.maxtech.storage.display.task.TaskNames;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Checks a {@link LogicGraph} and flattens it for {@link LogicEvaluator}. It reports every problem it finds per node,
 * in plain words ({@link GraphError}); a graph with any problem does not compile and stays paused. Checks: the size
 * limit, wires that lead nowhere or join different types, two wires into one input, required inputs and settings, and
 * loops that do not pass through a Remember or Delay node.
 *
 * <p>
 * Ordering: nodes run in an order where every node runs after the nodes that feed it. A wire into a loop-breaking node
 * ({@link NodeType#breaksLoops()}) that closes a loop is left out of the ordering and read one run later, so a loop
 * steps once per run. Compiling allocates; running does not. Pure; safe on any thread.
 */
public final class LogicCompiler {
    /** In the per-input feeder table: a wire went in but was rejected. */
    private static final int REJECTED = -2;
    private static final int[] NO_WATCHES = new int[0];
    private static final long[] NO_TABLE = new long[0];

    private LogicCompiler() {
    }

    private record WatchKey<K>(WatchKind kind, Optional<K> item, String label, String name, long number) {
    }

    private record ActionKey<K>(Op op, Optional<K> item, String label, String to, String text, int channels,
            long number, int mode) {
    }

    /** Compiles {@code graph}, which may hold at most {@code maxNodes} nodes. */
    public static <K> CompileResult<K> compile(LogicGraph<K> graph, int maxNodes) {
        List<GraphError> errors = new ArrayList<>();
        List<LogicNode<K>> nodes = graph.nodes();
        int n = nodes.size();
        if (n > maxNodes) {
            errors.add(GraphError.graph(LogicLang.TOO_MANY_NODES, Integer.toString(maxNodes)));
            return new CompileResult<>(graph, Optional.empty(), errors);
        }
        Map<Integer, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (index.putIfAbsent(nodes.get(i).id(), i) != null) {
                errors.add(GraphError.node(nodes.get(i).id(), LogicLang.DUPLICATE_NODE));
            }
        }
        List<List<Port>> inputs = new ArrayList<>(n);
        List<List<Port>> outputs = new ArrayList<>(n);
        // Per node and input: the feeding node's index and output index, or -1.
        int[][] fromNode = new int[n][];
        int[][] fromPort = new int[n][];
        for (LogicNode<K> node : nodes) {
            List<Port> in = node.type().inputs(node.settings());
            inputs.add(in);
            outputs.add(node.type().outputs(node.settings()));
            int[] none = new int[in.size()];
            Arrays.fill(none, -1);
            fromNode[inputs.size() - 1] = none;
            fromPort[inputs.size() - 1] = none.clone();
        }

        for (Wire wire : graph.wires()) {
            Integer from = index.get(wire.from());
            Integer to = index.get(wire.to());
            if (from == null || to == null) {
                int at = from != null ? wire.from() : to != null ? wire.to() : GraphError.GRAPH;
                errors.add(GraphError.node(at, LogicLang.MISSING_NODE));
                continue;
            }
            int out = portIndex(outputs.get(from), wire.output());
            int in = portIndex(inputs.get(to), wire.input());
            if (out < 0) {
                errors.add(GraphError.port(wire.from(), wire.output(), LogicLang.UNKNOWN_PORT));
                continue;
            }
            if (in < 0) {
                errors.add(GraphError.port(wire.to(), wire.input(), LogicLang.UNKNOWN_PORT));
                continue;
            }
            PortType type = inputs.get(to).get(in).type();
            if (outputs.get(from).get(out).type() != type) {
                errors.add(GraphError.port(wire.to(), wire.input(), type.wrongTypeKey()));
                if (fromNode[to][in] == -1) {
                    // Wired, if wrongly: the input should not also say it needs a wire.
                    fromNode[to][in] = REJECTED;
                }
                continue;
            }
            if (fromNode[to][in] != -1) {
                errors.add(GraphError.port(wire.to(), wire.input(), LogicLang.TWO_WIRES));
                continue;
            }
            fromNode[to][in] = from;
            fromPort[to][in] = out;
        }

        for (int i = 0; i < n; i++) {
            checkInputs(nodes, i, inputs.get(i), fromNode[i], errors);
            checkMissingItem(nodes, i, inputs.get(i), fromNode[i], fromPort[i], errors);
            checkNetworks(nodes, i, inputs.get(i), fromNode[i], errors);
        }
        checkParameters(nodes, errors);
        if (!errors.isEmpty()) {
            return new CompileResult<>(graph, Optional.empty(), errors);
        }

        // Loops: a wire into a loop breaker inside a loop is read one run later; any loop left is an error.
        int[] component = components(n, fromNode, null);
        boolean[][] late = new boolean[n][];
        for (int i = 0; i < n; i++) {
            late[i] = new boolean[fromNode[i].length];
            for (int p = 0; p < fromNode[i].length; p++) {
                int from = fromNode[i][p];
                late[i][p] = from >= 0 && component[from] == component[i] && nodes.get(i).type().breaksLoops();
            }
        }
        int[] strict = components(n, fromNode, late);
        int[] componentSize = new int[n];
        for (int c : strict) {
            componentSize[c]++;
        }
        for (int i = 0; i < n; i++) {
            boolean selfLoop = false;
            for (int p = 0; p < fromNode[i].length; p++) {
                selfLoop |= fromNode[i][p] == i && !late[i][p];
            }
            if (componentSize[strict[i]] > 1 || selfLoop) {
                errors.add(GraphError.node(nodes.get(i).id(), LogicLang.LOOP));
            }
        }
        if (!errors.isEmpty()) {
            return new CompileResult<>(graph, Optional.empty(), errors);
        }
        // For each branches (M7L2 row 18): which nodes run once per item, and whether they may.
        int[] branch = new int[n];
        Arrays.fill(branch, -1);
        List<List<Integer>> bodies = branches(nodes, fromNode, fromPort, late, branch, errors);
        if (!errors.isEmpty()) {
            return new CompileResult<>(graph, Optional.empty(), errors);
        }
        int owner = callOwner(nodes, new ArrayList<>());
        return new CompileResult<>(graph,
                Optional.of(build(graph, inputs, fromNode, fromPort, late, branch, bodies, owner)), errors);
    }

    /**
     * Finds each For each node's branch: the nodes that read its "each" or "item" output, directly or through other
     * nodes, in the same run. Sets {@code branch[i]} to the index of the For each whose branch node {@code i} is in,
     * and returns per node the indices of its branch (empty for other nodes). Adds an error for a node in two branches,
     * a For each inside a branch, a node that may not run once per item ({@link ForEach#allowedInBranch}) and a node
     * also fed from the For each's "done" or "items checked".
     */
    private static <K> List<List<Integer>> branches(List<LogicNode<K>> nodes, int[][] fromNode, int[][] fromPort,
            boolean[][] late, int[] branch, List<GraphError> errors) {
        int n = nodes.size();
        List<List<Integer>> bodies = new ArrayList<>(n);
        List<List<Integer>> next = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            bodies.add(List.of());
            next.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            for (int p = 0; p < fromNode[i].length; p++) {
                if (fromNode[i][p] >= 0 && !late[i][p]) {
                    next.get(fromNode[i][p]).add(i);
                }
            }
        }
        Set<Integer> flagged = new HashSet<>();
        for (int f = 0; f < n; f++) {
            if (nodes.get(f).type().op() != Op.FOR_EACH) {
                continue;
            }
            Set<Integer> body = new TreeSet<>();
            Set<Integer> after = new TreeSet<>();
            for (int i = 0; i < n; i++) {
                for (int p = 0; p < fromNode[i].length; p++) {
                    if (fromNode[i][p] != f || late[i][p]) {
                        continue;
                    }
                    int out = fromPort[i][p];
                    if (out == ForEach.OUT_EACH || out == ForEach.OUT_ITEM) {
                        body.add(i);
                    } else {
                        after.add(i);
                    }
                }
            }
            closure(body, next);
            closure(after, next);
            for (int i : body) {
                LogicNode<K> node = nodes.get(i);
                if (branch[i] >= 0 || node.type().op() == Op.FOR_EACH) {
                    if (flagged.add(i)) {
                        errors.add(GraphError.node(node.id(), LogicLang.EACH_NESTED));
                    }
                    continue;
                }
                branch[i] = f;
                if (!ForEach.allowedInBranch(node.type())) {
                    errors.add(GraphError.node(node.id(), LogicLang.EACH_NOT_HERE));
                } else if (after.contains(i)) {
                    errors.add(GraphError.node(node.id(), LogicLang.EACH_MIXED));
                }
            }
            bodies.set(f, List.copyOf(body));
        }
        // A call branch (M7L2 row 19): what reads the call or a parameter runs once per call.
        int owner = callOwner(nodes, errors);
        if (owner >= 0) {
            Set<Integer> body = new TreeSet<>();
            for (int i = 0; i < n; i++) {
                for (int p = 0; p < fromNode[i].length; p++) {
                    int from = fromNode[i][p];
                    if (from >= 0 && !late[i][p] && (from == owner || nodes.get(from).type().op() == Op.PARAMETER)) {
                        body.add(i);
                    }
                }
            }
            closure(body, next);
            for (int i : body) {
                LogicNode<K> node = nodes.get(i);
                if (branch[i] >= 0 || node.type().op() == Op.FOR_EACH) {
                    if (flagged.add(i)) {
                        errors.add(GraphError.node(node.id(), LogicLang.EACH_NESTED));
                    }
                    continue;
                }
                branch[i] = owner;
                if (!ForEach.allowedInBranch(node.type())) {
                    errors.add(GraphError.node(node.id(), LogicLang.CALL_NOT_HERE));
                }
            }
            bodies.set(owner, List.copyOf(body));
        }
        return bodies;
    }

    /**
     * Rules with parameters (M7L2 row 19): a parameter's name may appear once, and a rule has at most
     * {@link Parameters#MAX} of them.
     */
    private static <K> void checkParameters(List<LogicNode<K>> nodes, List<GraphError> errors) {
        Set<String> keys = new HashSet<>();
        for (LogicNode<K> node : nodes) {
            if (node.type().op() != Op.PARAMETER) {
                continue;
            }
            String key = Parameters.key(node.settings().text(NodeTypes.NAME).orElse(""));
            if (key.isEmpty()) {
                continue;
            }
            if (!keys.add(key)) {
                errors.add(GraphError.port(node.id(), NodeTypes.NAME, LogicLang.PARAM_DUPLICATE));
            } else if (keys.size() > Parameters.MAX) {
                errors.add(GraphError.port(node.id(), NodeTypes.NAME, LogicLang.PARAM_TOO_MANY));
            }
        }
    }

    /**
     * The index of the node whose calls run the rule's call branch: its "Run by another rule" node when the rule has
     * parameters; -1 otherwise, so a rule without parameters runs as it always did. A second such node is an error.
     */
    private static <K> int callOwner(List<LogicNode<K>> nodes, List<GraphError> errors) {
        boolean params = false;
        for (LogicNode<K> node : nodes) {
            params |= node.type().op() == Op.PARAMETER;
        }
        if (!params) {
            return -1;
        }
        int owner = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).type().op() != Op.RULE_CALLED) {
                continue;
            }
            if (owner < 0) {
                owner = i;
            } else {
                errors.add(GraphError.node(nodes.get(i).id(), LogicLang.CALL_ONE_ENTRY));
            }
        }
        return owner;
    }

    /** Adds to {@code set} every node its nodes feed in the same run, through any number of wires. */
    private static void closure(Set<Integer> set, List<List<Integer>> next) {
        List<Integer> todo = new ArrayList<>(set);
        while (!todo.isEmpty()) {
            int v = todo.removeLast();
            for (int w : next.get(v)) {
                if (set.add(w)) {
                    todo.add(w);
                }
            }
        }
    }

    private static int portIndex(List<Port> ports, String name) {
        for (int i = 0; i < ports.size(); i++) {
            if (ports.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static <K> void checkInputs(List<LogicNode<K>> nodes, int i, List<Port> inputs, int[] from,
            List<GraphError> errors) {
        LogicNode<K> node = nodes.get(i);
        NodeType type = node.type();
        if (type.missingScript()) {
            errors.add(GraphError.node(node.id(), LogicLang.SCRIPT_MISSING));
        }
        for (int p = 0; p < inputs.size(); p++) {
            Port port = inputs.get(p);
            if (from[p] != -1) {
                continue;
            }
            Optional<SettingSpec> spec = type.setting(port.name());
            boolean missing = spec.isPresent()
                    ? spec.get().required() && unset(node.settings(), spec.get())
                    : port.required();
            if (port.name().equals(NodeTypes.LABEL_NAME) && NodeTypes.channel(type, node.settings()) >= 0) {
                // A network redstone channel (M7L2) replaces the label.
                missing = false;
            }
            if (missing) {
                errors.add(GraphError.port(node.id(), port.name(), port.type().connectKey()));
            }
        }
        for (SettingSpec spec : type.settings()) {
            if (portIndex(inputs, spec.name()) < 0 && spec.required() && unset(node.settings(), spec)) {
                errors.add(GraphError.port(node.id(), spec.name(), LogicLang.FILL_IN));
            }
        }
        if ((type.op() == Op.CONSTANT || type.op() == Op.PARAMETER) && kind(node) == PortType.ITEM
                && node.settings().item(NodeTypes.ITEM_NAME).isEmpty()) {
            errors.add(GraphError.port(node.id(), NodeTypes.ITEM_NAME, LogicLang.FILL_IN));
        }
        if (type.op() == Op.RUN_RULE) {
            // An argument needs the parameter it fills, and a named parameter needs its argument (M7L2 row 19).
            boolean item = from[portIndex(inputs, NodeTypes.ITEM_NAME)] != -1
                    || node.settings().item(NodeTypes.ITEM_NAME).isPresent();
            boolean itemNamed = !node.settings().text(NodeTypes.ITEM_PARAM).orElse("").isBlank();
            if (item && !itemNamed) {
                errors.add(GraphError.port(node.id(), NodeTypes.ITEM_PARAM, LogicLang.FILL_IN));
            } else if (itemNamed && !item) {
                errors.add(GraphError.port(node.id(), NodeTypes.ITEM_NAME, PortType.ITEM.connectKey()));
            }
            if (from[portIndex(inputs, NodeTypes.AMOUNT)] != -1
                    && node.settings().text(NodeTypes.AMOUNT_PARAM).orElse("").isBlank()) {
                errors.add(GraphError.port(node.id(), NodeTypes.AMOUNT_PARAM, LogicLang.FILL_IN));
            }
        }
        if (type.op() == Op.MOVE_ITEMS && from[portIndex(inputs, NodeTypes.FROM)] == -1
                && from[portIndex(inputs, NodeTypes.TO)] == -1
                && node.settings().text(NodeTypes.FROM).orElse("").isEmpty()
                && node.settings().text(NodeTypes.TO).orElse("").isEmpty()) {
            errors.add(GraphError.port(node.id(), NodeTypes.TO, LogicLang.MOVE_NOWHERE));
        }
        if (type.op() == Op.SET_FILTER && number(node.settings(), type.setting(NodeTypes.SLOT).orElseThrow()) == 0
                && type.setting(NodeTypes.PORT_MODE).orElseThrow()
                        .choiceIndex(node.settings().text(NodeTypes.PORT_MODE).orElse("")) == 0) {
            errors.add(GraphError.port(node.id(), NodeTypes.SLOT, LogicLang.FILTER_NOTHING));
        }
        if (type.op() == Op.FOR_EACH) {
            switch (ForEach.source(node.settings())) {
                case ForEach.SOURCE_TAG -> {
                    if (node.settings().item(NodeTypes.TAG).isEmpty()) {
                        errors.add(GraphError.port(node.id(), NodeTypes.TAG, LogicLang.FILL_IN));
                    }
                }
                case ForEach.SOURCE_LIST -> {
                    if (listItems(node.settings()).isEmpty()) {
                        errors.add(GraphError.port(node.id(), ForEach.item(1), LogicLang.FILL_IN));
                    }
                }
                default -> {
                    if (node.settings().text(NodeTypes.LABEL_NAME).orElse("").isBlank()) {
                        errors.add(GraphError.port(node.id(), NodeTypes.LABEL_NAME, LogicLang.FILL_IN));
                    }
                }
            }
        }
        if (type.op() == Op.STEP_IS && !node.settings().text(Sequences.WHICH).orElse("").isBlank()
                && stepIndex(nodes, node, from[portIndex(inputs, Sequences.STEP)]) == 0) {
            errors.add(new GraphError(node.id(), Sequences.WHICH, LogicLang.NO_SUCH_STEP,
                    Sequences.clean(node.settings().text(Sequences.WHICH).orElse(""))));
        }
    }

    /**
     * The step a step-is node names: through the sequence wired into it, a step name or number; through anything else
     * (or nothing), a number in digits. 0 when it names none. {@code from} is the index of the node feeding its input,
     * or below 0.
     */
    private static <K> int stepIndex(List<LogicNode<K>> nodes, LogicNode<K> node, int from) {
        String which = node.settings().text(Sequences.WHICH).orElse("");
        if (from >= 0 && nodes.get(from).type().op() == Op.SEQUENCE) {
            return Sequences.find(nodes.get(from).settings(), which).orElse(0);
        }
        return Sequences.number(which, Integer.MAX_VALUE / 2).orElse(0);
    }

    /** The items a For each's list holds: those set among the first {@link ForEach#listSize} entries, in order. */
    private static <K> List<K> listItems(NodeSettings<K> settings) {
        List<K> items = new ArrayList<>();
        int size = ForEach.listSize(settings);
        for (int k = 1; k <= size; k++) {
            settings.item(ForEach.item(k)).ifPresent(items::add);
        }
        return items;
    }

    private static <K> boolean unset(NodeSettings<K> settings, SettingSpec spec) {
        return switch (spec.kind()) {
            case ITEM, FLUID, TAG -> settings.item(spec.name()).isEmpty();
            case TEXT, LABEL -> settings.text(spec.name()).orElse("").isBlank();
            case NUMBER -> settings.number(spec.name()).isEmpty();
            case FLAG -> settings.flag(spec.name()).isEmpty();
            case CHOICE -> settings.text(spec.name()).isEmpty();
        };
    }

    private static <K> PortType kind(LogicNode<K> node) {
        return node.type().outputs(node.settings()).getFirst().type();
    }

    /**
     * Strongly connected components (Tarjan) of the graph whose edges are the wires, leaving out those marked in
     * {@code skip}. Returns each node's component number.
     */
    private static int[] components(int n, int[][] fromNode, boolean @Nullable [][] skip) {
        // Edges from feeder to fed node.
        List<List<Integer>> next = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            next.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            for (int p = 0; p < fromNode[i].length; p++) {
                if (fromNode[i][p] >= 0 && (skip == null || !skip[i][p])) {
                    next.get(fromNode[i][p]).add(i);
                }
            }
        }
        Tarjan tarjan = new Tarjan(next);
        for (int i = 0; i < n; i++) {
            if (tarjan.order[i] < 0) {
                tarjan.visit(i);
            }
        }
        return tarjan.component;
    }

    private static final class Tarjan {
        final List<List<Integer>> next;
        final int[] order;
        final int[] low;
        final int[] component;
        final boolean[] onStack;
        final int[] stack;
        int stackSize;
        int counter;
        int components;

        Tarjan(List<List<Integer>> next) {
            int n = next.size();
            this.next = next;
            order = new int[n];
            Arrays.fill(order, -1);
            low = new int[n];
            component = new int[n];
            onStack = new boolean[n];
            stack = new int[n];
        }

        // Recursion depth is at most the node count, which the codec caps at LogicGraph.HARD_MAX_NODES.
        void visit(int v) {
            order[v] = counter;
            low[v] = counter;
            counter++;
            stack[stackSize++] = v;
            onStack[v] = true;
            for (int w : next.get(v)) {
                if (order[w] < 0) {
                    visit(w);
                    low[v] = Math.min(low[v], low[w]);
                } else if (onStack[w]) {
                    low[v] = Math.min(low[v], order[w]);
                }
            }
            if (low[v] == order[v]) {
                int w;
                do {
                    w = stack[--stackSize];
                    onStack[w] = false;
                    component[w] = components;
                } while (w != v);
                components++;
            }
        }
    }

    @SuppressWarnings("unchecked") // A generic array of compiled nodes; only CompiledNode<K> goes in.
    private static <K> CompiledGraph<K> build(LogicGraph<K> graph, List<List<Port>> inputs, int[][] fromNode,
            int[][] fromPort, boolean[][] late, int[] branch, List<List<Integer>> bodies, int owner) {
        List<LogicNode<K>> nodes = graph.nodes();
        int n = nodes.size();
        int[] order = order(n, fromNode, late, branch);
        int[] position = new int[n];
        for (int pos = 0; pos < n; pos++) {
            position[order[pos]] = pos;
        }

        // Value slots: outputs first, in evaluation order, then one per unwired number or yes/no input.
        int[] outBase = new int[n];
        int slots = 0;
        for (int pos = 0; pos < n; pos++) {
            int i = order[pos];
            outBase[i] = slots;
            slots += nodes.get(i).type().outputs(nodes.get(i).settings()).size();
        }
        List<Long> constants = new ArrayList<>();
        int[][] in = new int[n][];
        for (int i = 0; i < n; i++) {
            LogicNode<K> node = nodes.get(i);
            List<Port> ports = inputs.get(i);
            in[i] = new int[ports.size()];
            for (int p = 0; p < ports.size(); p++) {
                Port port = ports.get(p);
                if (port.type().fixed()) {
                    in[i][p] = -1;
                } else if (fromNode[i][p] >= 0) {
                    in[i][p] = outBase[fromNode[i][p]] + fromPort[i][p];
                } else {
                    long value = port.type() == PortType.EVENT ? 0 : port.fallback();
                    Optional<SettingSpec> spec = node.type().setting(port.name());
                    if (spec.isPresent() && spec.get().kind() == SettingKind.NUMBER) {
                        value = number(node.settings(), spec.get());
                    } else if (spec.isPresent() && spec.get().kind() == SettingKind.FLAG && node.type().scripted()) {
                        // A script node's yes/no input with a same-named setting reads it while unwired.
                        value = node.settings().flag(port.name()).orElse(spec.get().defaultValue() != 0) ? 1 : 0;
                    }
                    in[i][p] = slots + constants.size();
                    constants.add(value);
                }
            }
        }
        long[] initial = new long[slots + constants.size()];
        for (int c = 0; c < constants.size(); c++) {
            initial[slots + c] = constants.get(c);
        }

        // Fed nodes, split into this run's and next run's.
        List<Set<Integer>> down = new ArrayList<>(n);
        List<Set<Integer>> lateDown = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            down.add(new LinkedHashSet<>());
            lateDown.add(new LinkedHashSet<>());
        }
        for (int i = 0; i < n; i++) {
            for (int p = 0; p < fromNode[i].length; p++) {
                int from = fromNode[i][p];
                if (from >= 0) {
                    (late[i][p] ? lateDown : down).get(from).add(position[i]);
                }
            }
        }

        // Parameter nodes by index in the graph to their place among the parameters, in node order.
        Map<Integer, Integer> paramIndex = new java.util.TreeMap<>();
        for (int i = 0; i < n; i++) {
            if (nodes.get(i).type().op() == Op.PARAMETER) {
                paramIndex.put(i, paramIndex.size());
            }
        }
        Map<WatchKey<K>, Integer> watchIndex = new HashMap<>();
        List<Watch<K>> watches = new ArrayList<>();
        List<List<Integer>> watchNodes = new ArrayList<>();
        Map<ActionKey<K>, Integer> actionIndex = new HashMap<>();
        List<Integer> actionNodes = new ArrayList<>();
        List<Integer> timed = new ArrayList<>();
        CompiledNode<K>[] compiled = new CompiledNode[n];
        int memory = 0;
        for (int pos = 0; pos < n; pos++) {
            int i = order[pos];
            LogicNode<K> node = nodes.get(i);
            NodeType type = node.type();
            Op op = type.op();
            NodeSettings<K> settings = node.settings();
            List<Port> ports = inputs.get(i);
            // An item input wired from a For each's "item" takes the item of each pass (M7L2 row 18); no fixed item.
            // So does one wired from a parameter in a call branch (M7L2 row 19), with the call's item.
            int param = owner >= 0 && branch[i] == owner ? itemParam(type, ports, fromNode[i], paramIndex) : -1;
            boolean eachItem = param >= 0 || eachItem(type, nodes, ports, fromNode[i], fromPort[i]);
            // One wired from a crafting-job-failed node's "missing item" takes that run's missing item (M7L2).
            int missingFrom = missingItemSource(type, nodes, ports, fromNode[i], fromPort[i]);
            boolean runtimeItem = eachItem || missingFrom >= 0;
            @Nullable
            K item = runtimeItem ? null : resolveItem(type, nodes, ports, fromNode[i], settings);
            // A network redstone channel (M7L2) replaces the label: the node watches or writes the channel.
            int colour = NodeTypes.channel(type, settings);
            String label = colour >= 0 ? "" : resolveLabel(nodes, ports, fromNode[i], settings, labelName(op));
            String to = op == Op.MOVE_ITEMS ? resolveLabel(nodes, ports, fromNode[i], settings, NodeTypes.TO) : "";
            if (op == Op.RUN_RULE) {
                // The parameters of the called rule its item and amount fill (M7L2 row 19), keyed.
                label = Parameters.key(settings.text(NodeTypes.ITEM_PARAM).orElse(""));
                to = Parameters.key(settings.text(NodeTypes.AMOUNT_PARAM).orElse(""));
            }
            String text = switch (op) {
                case NOTIFY, WRITE_DISPLAY -> settings.text(NodeTypes.TEXT).orElse("").strip();
                case SET_VARIABLE -> variableName(settings.text(NodeTypes.NAME).orElse(""));
                case SET_TASK -> taskName(type, settings);
                case RUN_RULE -> settings.text(NodeTypes.RULE).orElse("").strip();
                // The sensor value that says a pool member is idle (M7L2); empty: every member is.
                case POOL_SEND -> settings.text(NodeTypes.IDLE_VALUE).orElse("").strip();
                default -> "";
            };
            String name = switch (op) {
                case SENSOR_CROSSES, SENSOR_VALUE -> settings.text(NodeTypes.VALUE).orElse("").strip();
                case VARIABLE -> variableName(settings.text(NodeTypes.NAME).orElse(""));
                case TASK -> taskName(type, settings);
                case POOL_STATUS, POOL_IDLE -> settings.text(NodeTypes.IDLE_VALUE).orElse("").strip();
                default -> "";
            };
            int channels = op == Op.NOTIFY ? channels(type, settings) : 0;

            // The type's choice setting (a mode, a constant's kind, a math operation, a statistics window).
            int mode = choiceSetting(type).map(spec -> spec.choiceIndex(settings.text(spec.name()).orElse("")))
                    .orElse(0);
            long threshold = type.setting(NodeTypes.THRESHOLD).map(spec -> number(settings, spec)).orElse(0L);
            long upper = type.setting(op == Op.WAIT_UNTIL ? NodeTypes.HOLD_MOST : NodeTypes.UPPER)
                    .map(spec -> number(settings, spec)).orElse(0L);
            if (op == Op.COMPARE && mode == Checks.BETWEEN && upper < threshold) {
                long swap = upper;
                upper = threshold;
                threshold = swap;
            }
            long switchBack = threshold;
            if (settings.number(NodeTypes.SWITCH_BACK).isPresent()) {
                long wanted = Math.clamp(settings.number(NodeTypes.SWITCH_BACK).getAsLong(), -SettingSpec.MAX_NUMBER,
                        SettingSpec.MAX_NUMBER);
                switchBack = mode == Checks.ABOVE ? Math.min(wanted, threshold) : Math.max(wanted, threshold);
            }
            long number = switch (op) {
                case COUNTER -> number(settings, type.setting(NodeTypes.STEP).orElseThrow());
                case DELAY, PULSE_REDSTONE -> number(settings, type.setting(NodeTypes.TICKS).orElseThrow());
                // The filter slot, 1 to 9; 0 leaves the slots alone.
                case SET_FILTER -> number(settings, type.setting(NodeTypes.SLOT).orElseThrow());
                case CONSTANT,
                        PARAMETER ->
                    mode == 1
                            ? (settings.flag(NodeTypes.YES).orElse(true) ? 1 : 0)
                            : number(settings, type.setting(NodeTypes.VALUE).orElseThrow());
                case TIMER ->
                    number(settings, type.setting(NodeTypes.INTERVAL).orElseThrow()) * NodeTypes.TICKS_PER_SECOND;
                case COOLDOWN ->
                    number(settings, type.setting(NodeTypes.SECONDS).orElseThrow()) * NodeTypes.TICKS_PER_SECOND;
                // The window: a sample count, or ticks to look back.
                case STATS -> mode == 0
                        ? number(settings, type.setting(NodeTypes.SAMPLES).orElseThrow())
                        : number(settings, type.setting(NodeTypes.SECONDS).orElseThrow()) * NodeTypes.TICKS_PER_SECOND;
                case PLAYERS_NEAR -> number(settings, type.setting(NodeTypes.RADIUS).orElseThrow());
                // The timeout in ticks; 0 holds an event until the condition turns yes.
                case WAIT_UNTIL ->
                    number(settings, type.setting(NodeTypes.TIMEOUT).orElseThrow()) * NodeTypes.TICKS_PER_SECOND;
                // The step count, and the step a step-is node names (checked above).
                case SEQUENCE -> Sequences.steps(settings);
                case STEP_IS -> stepIndex(nodes, node, fromNode[i][portIndex(ports, Sequences.STEP)]);
                // A pool's idle condition and limit, packed.
                case POOL_SEND, POOL_STATUS, POOL_IDLE -> idleCondition(type, settings);
                default -> 0;
            };
            long[] table = NO_TABLE;
            if (op == Op.SEQUENCE) {
                // Each step's time limit in ticks; 0 is none.
                table = new long[(int) number];
                for (int k = 1; k <= table.length; k++) {
                    table[k - 1] = Sequences.timeoutSeconds(settings, k) * NodeTypes.TICKS_PER_SECOND;
                }
            }
            int flags = 0;
            if (op == Op.BETWEEN) {
                flags |= flag(type, settings, NodeTypes.INCLUDE_LOW) ? CompiledNode.INCLUDE_LOW : 0;
                flags |= flag(type, settings, NodeTypes.INCLUDE_HIGH) ? CompiledNode.INCLUDE_HIGH : 0;
            }

            int watch = -1;
            WatchKind kind = op.watch();
            if (kind == WatchKind.REDSTONE && colour >= 0) {
                kind = WatchKind.CHANNEL;
            }
            if (kind != null && !runtimeItem) {
                // Players nearby watch a radius, item moves a direction, channels their number, stock keepers a
                // value; a watch differs by them.
                long watchNumber = switch (op) {
                    case PLAYERS_NEAR -> number;
                    case ITEM_MOVES -> mode;
                    case STOCK_SHORT -> WatchKind.STOCK_FLAGS;
                    case POOL_STATUS, POOL_IDLE -> number;
                    default -> kind == WatchKind.CHANNEL ? colour : 0;
                };
                watch = watch(watchIndex, watches, watchNodes,
                        new WatchKey<>(kind, Optional.ofNullable(item), label, name, watchNumber), pos);
            }
            int watch2 = -1;
            WatchKind second = op.secondWatch();
            if (second != null) {
                watch2 = watch(watchIndex, watches, watchNodes, new WatchKey<>(second, Optional.empty(), label, "", 0),
                        pos);
            }
            int[] more = NO_WATCHES;
            if (op == Op.STOCK_STATUS && !runtimeItem) {
                // The rule's other values and state from the keepers, and the item's count in the network.
                Optional<K> key = Optional.ofNullable(item);
                more = new int[]{
                        watch(watchIndex, watches, watchNodes,
                                new WatchKey<>(WatchKind.STOCK, key, label, "", WatchKind.STOCK_RESTART), pos),
                        watch(watchIndex, watches, watchNodes,
                                new WatchKey<>(WatchKind.STOCK, key, label, "", WatchKind.STOCK_BATCH), pos),
                        watch(watchIndex, watches, watchNodes,
                                new WatchKey<>(WatchKind.STOCK, key, label, "", WatchKind.STOCK_FLAGS), pos),
                        watch(watchIndex, watches, watchNodes, new WatchKey<>(WatchKind.ITEM_COUNT, key, "", "", 0),
                                pos)};
            }
            int action = -1;
            if (op.acts()) {
                // A setting replaces per target: a label, a redstone channel, a shared variable's name, or a stock
                // rule (label, item and, for amounts, which one).
                boolean stock = op == Op.STOCK_SWITCH || op == Op.STOCK_SET;
                ActionKey<K> key = op.replaces()
                        ? new ActionKey<>(op, stock ? Optional.ofNullable(item) : Optional.empty(), label, "",
                                op == Op.SET_VARIABLE || op == Op.SET_TASK ? text : "", colour + 1, 0, stock ? mode : 0)
                        : new ActionKey<>(op, Optional.ofNullable(item), label, to, text, channels, number, mode);
                Integer existing = actionIndex.get(key);
                if (existing == null) {
                    existing = actionNodes.size();
                    actionIndex.put(key, existing);
                    actionNodes.add(pos);
                }
                action = existing;
            }
            if (op == Op.DELAY || op == Op.TIMER || op == Op.STATS || op == Op.PULSE_REDSTONE || op == Op.WAIT_UNTIL
                    || op == Op.SEQUENCE) {
                timed.add(pos);
            }
            int[] body = NO_WATCHES;
            int[] entry = NO_WATCHES;
            int[] bodyEvents = NO_WATCHES;
            List<K> items = List.of();
            if (op == Op.FOR_EACH || i == owner) {
                List<Integer> members = bodies.get(i);
                body = new int[members.size()];
                List<Integer> events = new ArrayList<>();
                for (int b = 0; b < body.length; b++) {
                    int j = members.get(b);
                    body[b] = position[j];
                    List<Port> outs = nodes.get(j).type().outputs(nodes.get(j).settings());
                    for (int k = 0; k < outs.size(); k++) {
                        if (outs.get(k).type() == PortType.EVENT) {
                            events.add(outBase[j] + k);
                        }
                    }
                }
                Arrays.sort(body);
                bodyEvents = toArray(events);
                Set<Integer> first = new TreeSet<>();
                for (int j : members) {
                    for (int p = 0; p < fromNode[j].length; p++) {
                        if (fromNode[j][p] == i && !late[j][p]) {
                            first.add(position[j]);
                        }
                    }
                }
                entry = toArray(first);
                if (mode == ForEach.SOURCE_LIST) {
                    items = List.copyOf(listItems(settings));
                }
            }
            compiled[pos] = new CompiledNode<>(node.id(), type, in[i], late[i], outBase[i], memory, watch, watch2, more,
                    action, toArray(down.get(i)), toArray(lateDown.get(i)), mode, threshold, switchBack, upper, number,
                    item, label, to, text, channels, flags, colour, table, branch[i] >= 0 ? position[branch[i]] : -1,
                    eachItem, param, missingFrom >= 0 ? position[missingFrom] : -1, body, entry, bodyEvents, items);
            memory += type.memory();
        }
        int[][] watchArrays = new int[watchNodes.size()][];
        for (int w = 0; w < watchArrays.length; w++) {
            watchArrays[w] = toArray(watchNodes.get(w));
        }
        // The rule's parameters in node order, for calls (M7L2 row 19): output slot, key, kind, default.
        int params = paramIndex.size();
        int[] paramSlots = new int[params];
        String[] paramKeys = new String[params];
        int[] paramKinds = new int[params];
        long[] paramNumbers = new long[params];
        @Nullable
        Object[] paramItems = new Object[params];
        for (Map.Entry<Integer, Integer> entry : paramIndex.entrySet()) {
            int i = entry.getKey();
            int k = entry.getValue();
            LogicNode<K> node = nodes.get(i);
            CompiledNode<K> made = compiled[position[i]];
            paramSlots[k] = made.out;
            paramKeys[k] = Parameters.key(node.settings().text(NodeTypes.NAME).orElse(""));
            paramKinds[k] = Parameters.kind(node.settings());
            paramNumbers[k] = made.number;
            paramItems[k] = node.settings().item(NodeTypes.ITEM_NAME).orElse(null);
        }
        return new CompiledGraph<>(graph, compiled, initial, slots, memory, watches, watchArrays, toArray(timed),
                toArray(actionNodes), owner >= 0 ? position[owner] : -1,
                new CompiledGraph.Params(paramSlots, paramKeys, paramKinds, paramNumbers, paramItems));
    }

    /**
     * Kahn's ordering over the wires that are read in the same run; ties go by the graph's own order. A For each runs
     * after every node outside its branch that feeds a node in it, so its passes read this run's values.
     */
    private static int[] order(int n, int[][] fromNode, boolean[][] late, int[] branch) {
        int[] waiting = new int[n];
        List<List<Integer>> next = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            next.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            for (int p = 0; p < fromNode[i].length; p++) {
                int from = fromNode[i][p];
                if (from >= 0 && !late[i][p]) {
                    waiting[i]++;
                    next.get(from).add(i);
                    int each = branch[i];
                    if (each >= 0 && from != each && branch[from] != each) {
                        waiting[each]++;
                        next.get(from).add(each);
                    }
                }
            }
        }
        int[] order = new int[n];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < n; i++) {
            if (waiting[i] == 0) {
                order[tail++] = i;
            }
        }
        while (head < tail) {
            int v = order[head++];
            for (int w : next.get(v)) {
                if (--waiting[w] == 0) {
                    order[tail++] = w;
                }
            }
        }
        if (tail != n) {
            throw new IllegalStateException("Logic graph ordering missed a loop the checks should have caught");
        }
        return order;
    }

    /** The index of the watch {@code key}, added when no earlier node watches it; node {@code pos} reads it. */
    private static <K> int watch(Map<WatchKey<K>, Integer> watchIndex, List<Watch<K>> watches,
            List<List<Integer>> watchNodes, WatchKey<K> key, int pos) {
        Integer existing = watchIndex.get(key);
        if (existing == null) {
            existing = watches.size();
            watchIndex.put(key, existing);
            watches.add(new Watch<>(existing, key.kind(), key.item(), key.label(), key.name(), key.number()));
            watchNodes.add(new ArrayList<>());
        }
        watchNodes.get(existing).add(pos);
        return existing;
    }

    /**
     * The item, fluid or tag a node names: through the wire into its item input, else its setting. The input and the
     * setting share a name: "item", or the type's fluid or tag setting.
     */
    private static <K> @Nullable K resolveItem(NodeType type, List<LogicNode<K>> nodes, List<Port> ports, int[] from,
            NodeSettings<K> settings) {
        String name = NodeTypes.ITEM_NAME;
        for (SettingSpec spec : type.settings()) {
            if (spec.kind().holdsKey()) {
                name = spec.name();
                break;
            }
        }
        int port = portIndex(ports, name);
        if (port >= 0 && from[port] >= 0) {
            // A constant's item setting is always "item".
            return nodes.get(from[port]).settings().item(NodeTypes.ITEM_NAME).orElse(null);
        }
        return settings.item(name).orElse(null);
    }

    /**
     * Whether the node's item input (the input its item, fluid or tag setting is named after) is wired from a For
     * each's "item" output, so it takes the item of each pass (M7L2 row 18).
     */
    private static <K> boolean eachItem(NodeType type, List<LogicNode<K>> nodes, List<Port> ports, int[] from,
            int[] fromPort) {
        String name = NodeTypes.ITEM_NAME;
        for (SettingSpec spec : type.settings()) {
            if (spec.kind().holdsKey()) {
                name = spec.name();
                break;
            }
        }
        int port = portIndex(ports, name);
        return port >= 0 && from[port] >= 0 && nodes.get(from[port]).type().op() == Op.FOR_EACH
                && fromPort[port] == ForEach.OUT_ITEM;
    }

    /**
     * The place among the rule's parameters of the parameter wired into the node's item input, or -1 when no parameter
     * feeds it (M7L2 row 19).
     */
    private static int itemParam(NodeType type, List<Port> ports, int[] from, Map<Integer, Integer> paramIndex) {
        String name = NodeTypes.ITEM_NAME;
        for (SettingSpec spec : type.settings()) {
            if (spec.kind().holdsKey()) {
                name = spec.name();
                break;
            }
        }
        int port = portIndex(ports, name);
        if (port < 0 || from[port] < 0) {
            return -1;
        }
        Integer index = paramIndex.get(from[port]);
        return index == null ? -1 : index;
    }

    /**
     * A crafting-job-failed node's "missing item" may only feed the item input of a node that can take an item while it
     * runs ({@link NodeTypes#takesMissingItem}); anything else would read the failed node's own item setting.
     */
    private static <K> void checkMissingItem(List<LogicNode<K>> nodes, int i, List<Port> inputs, int[] from,
            int[] fromPort, List<GraphError> errors) {
        NodeType type = nodes.get(i).type();
        String itemInput = itemInputName(type);
        for (int p = 0; p < inputs.size(); p++) {
            if (from[p] >= 0 && nodes.get(from[p]).type().op() == Op.CRAFT_FAILED
                    && fromPort[p] == NodeTypes.CRAFT_FAILED_OUT_ITEM
                    && (!inputs.get(p).name().equals(itemInput) || !NodeTypes.takesMissingItem(type))) {
                errors.add(GraphError.port(nodes.get(i).id(), inputs.get(p).name(), LogicLang.MISSING_ITEM_NOT_HERE));
            }
        }
    }

    /**
     * The index of the crafting-job-failed node whose "missing item" output feeds the node's item input, or -1 (M7L2).
     */
    private static <K> int missingItemSource(NodeType type, List<LogicNode<K>> nodes, List<Port> ports, int[] from,
            int[] fromPort) {
        int port = portIndex(ports, itemInputName(type));
        return port >= 0 && from[port] >= 0 && nodes.get(from[port]).type().op() == Op.CRAFT_FAILED
                && fromPort[port] == NodeTypes.CRAFT_FAILED_OUT_ITEM ? from[port] : -1;
    }

    /** The input a node's item, fluid or tag setting is named after; {@link NodeTypes#ITEM_NAME} by default. */
    private static String itemInputName(NodeType type) {
        for (SettingSpec spec : type.settings()) {
            if (spec.kind().holdsKey()) {
                return spec.name();
            }
        }
        return NodeTypes.ITEM_NAME;
    }

    private static <K> String resolveLabel(List<LogicNode<K>> nodes, List<Port> ports, int[] from,
            NodeSettings<K> settings, String name) {
        int port = portIndex(ports, name);
        Optional<String> label = port >= 0 && from[port] >= 0
                ? nodes.get(from[port]).settings().text(NodeTypes.LABEL_NAME)
                : settings.text(name);
        String value = label.orElse("").strip();
        return value.length() > NetworkRef.MAX_REF ? value.substring(0, NetworkRef.MAX_REF) : value;
    }

    /** The setting (and input) a node of {@code op} takes its label from. */
    static String labelName(Op op) {
        return switch (op) {
            case MOVE_ITEMS -> NodeTypes.FROM;
            case REQUEST_CRAFT -> NodeTypes.NETWORK;
            default -> NodeTypes.LABEL_NAME;
        };
    }

    /**
     * Rules across networks (M7L2 row 13): a node may name another network in its label only when its kind can reach
     * one ({@link NetworkRef#supports}); a request craft names only a network; a move's two sides share one network (an
     * empty side is the storage of the other side's network). Whether the network exists and the owner may use it is
     * the controller's check, since names change.
     */
    private static <K> void checkNetworks(List<LogicNode<K>> nodes, int i, List<Port> inputs, int[] from,
            List<GraphError> errors) {
        LogicNode<K> node = nodes.get(i);
        NodeType type = node.type();
        Op op = type.op();
        if (op == Op.RUN_RULE || NodeTypes.channel(type, node.settings()) >= 0) {
            // A run-rule node's labels name parameters; a redstone channel replaces the label.
            return;
        }
        String labelName = labelName(op);
        if (type.setting(labelName).isEmpty() && portIndex(inputs, labelName) < 0) {
            return;
        }
        String label = resolveLabel(nodes, inputs, from, node.settings(), labelName);
        String to = op == Op.MOVE_ITEMS ? resolveLabel(nodes, inputs, from, node.settings(), NodeTypes.TO) : "";
        String network = NetworkRef.network(label);
        String toNetwork = NetworkRef.network(to);
        if (network.isEmpty() && toNetwork.isEmpty()) {
            if (op == Op.REQUEST_CRAFT && !label.isEmpty()) {
                errors.add(new GraphError(node.id(), labelName, LogicLang.NET_NETWORK_ONLY, label));
            }
            return;
        }
        if (!NetworkRef.supports(op)) {
            errors.add(new GraphError(node.id(), network.isEmpty() ? NodeTypes.TO : labelName, LogicLang.NET_NOT_HERE,
                    network.isEmpty() ? toNetwork : network));
        } else if (op == Op.REQUEST_CRAFT && !NetworkRef.device(label).isEmpty()) {
            errors.add(new GraphError(node.id(), labelName, LogicLang.NET_NETWORK_ONLY, network));
        } else if (op == Op.MOVE_ITEMS && !label.isEmpty() && !to.isEmpty()
                && !NetworkRef.key(network).equals(NetworkRef.key(toNetwork))) {
            errors.add(new GraphError(node.id(), NodeTypes.TO, LogicLang.NET_MOVE_SIDES,
                    toNetwork.isEmpty() ? network : toNetwork));
        }
    }

    /**
     * A shared variable's name as the controller keys it: trimmed, lower case, at most {@link SettingSpec#MAX_LABEL}
     * characters, so "Mode" and "mode " name one variable.
     */
    public static String variableName(String typed) {
        String name = typed.strip().toLowerCase(Locale.ROOT);
        return name.length() > SettingSpec.MAX_LABEL ? name.substring(0, SettingSpec.MAX_LABEL) : name;
    }

    /** A task node's task as one text: its scope, board and task ({@link TaskNames}). */
    private static String taskName(NodeType type, NodeSettings<?> settings) {
        int scope = type.setting(NodeTypes.BOARD_SCOPE)
                .map(spec -> spec.choiceIndex(settings.text(NodeTypes.BOARD_SCOPE).orElse(""))).orElse(0);
        return TaskNames.logicName(scope == 1, settings.text(NodeTypes.BOARD).orElse(""),
                settings.text(NodeTypes.TASK).orElse(""));
    }

    /** The type's first choice setting besides the redstone channel; types have at most one. */
    private static Optional<SettingSpec> choiceSetting(NodeType type) {
        for (SettingSpec spec : type.settings()) {
            if (spec.kind() == SettingKind.CHOICE && !spec.name().equals(NodeTypes.CHANNEL)) {
                return Optional.of(spec);
            }
        }
        return Optional.empty();
    }

    private static <K> long number(NodeSettings<K> settings, SettingSpec spec) {
        return spec.clamp(settings.number(spec.name()).orElse(spec.defaultValue()));
    }

    private static <K> int channels(NodeType type, NodeSettings<K> settings) {
        int channels = 0;
        channels |= flag(type, settings, NodeTypes.LOG) ? LogicContext.CHANNEL_LOG : 0;
        channels |= flag(type, settings, NodeTypes.TOAST) ? LogicContext.CHANNEL_TOAST : 0;
        channels |= flag(type, settings, NodeTypes.CHAT) ? LogicContext.CHANNEL_CHAT : 0;
        channels |= flag(type, settings, NodeTypes.DISPLAYS) ? LogicContext.CHANNEL_DISPLAYS : 0;
        return channels;
    }

    /** A machine pool node's idle condition and limit, packed ({@link Pools#packIdle}). */
    private static <K> long idleCondition(NodeType type, NodeSettings<K> settings) {
        SettingSpec when = type.setting(NodeTypes.IDLE_WHEN).orElseThrow();
        int compare = when.choiceIndex(settings.text(NodeTypes.IDLE_WHEN).orElse(""));
        return Pools.packIdle(compare, number(settings, type.setting(NodeTypes.IDLE_LIMIT).orElseThrow()));
    }

    private static <K> boolean flag(NodeType type, NodeSettings<K> settings, String name) {
        return settings.flag(name).orElse(type.setting(name).map(spec -> spec.defaultValue() != 0).orElse(false));
    }

    private static int[] toArray(Iterable<Integer> values) {
        List<Integer> list = new ArrayList<>();
        values.forEach(list::add);
        int[] array = new int[list.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = list.get(i);
        }
        return array;
    }
}
