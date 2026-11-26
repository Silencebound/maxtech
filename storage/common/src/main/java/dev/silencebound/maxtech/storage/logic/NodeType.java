package dev.silencebound.maxtech.storage.logic;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A kind of node: its ports, settings, cost and behaviour. Registered once in {@link NodeTypes}; immutable and safe on
 * any thread. Internal to Storage for now (no api in M7L).
 */
public final class NodeType {
    private final String id;
    private final NodeCategory category;
    private final Op op;
    private final List<Port> inputs;
    private final List<Port> outputs;
    private final List<SettingSpec> settings;
    private final int cost;
    private final int memory;
    private final boolean breaksLoops;
    private final boolean common;
    private final String nameKey;
    /** For a script node type, what runs it; null for built-in types and a missing script's stand-in. */
    private final @Nullable ScriptNodeLogic script;
    private final boolean missingScript;

    NodeType(String id, String english, NodeCategory category, Op op, List<Port> inputs, List<Port> outputs,
            List<SettingSpec> settings, int cost, int memory, boolean breaksLoops, boolean common) {
        this(id, LogicLang.add("node." + id, english), category, op, inputs, outputs, settings, cost, memory,
                breaksLoops, common, null, false);
    }

    /**
     * A type whose name key is already declared: script node types ({@link ScriptNodes}), whose texts come from the
     * pack, and the stand-in for a script node type no script registers now ({@code missingScript}).
     */
    NodeType(String id, String nameKey, NodeCategory category, Op op, List<Port> inputs, List<Port> outputs,
            List<SettingSpec> settings, int cost, int memory, boolean breaksLoops, boolean common,
            @Nullable ScriptNodeLogic script, boolean missingScript) {
        this.id = id;
        this.category = category;
        this.op = op;
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.settings = List.copyOf(settings);
        this.cost = cost;
        this.memory = memory;
        this.breaksLoops = breaksLoops;
        this.common = common;
        this.nameKey = nameKey;
        this.script = script;
        this.missingScript = missingScript;
    }

    /** The saved id, stable across versions. */
    public String id() {
        return id;
    }

    public NodeCategory category() {
        return category;
    }

    Op op() {
        return op;
    }

    /**
     * Every input port the type declares, in display order. For a step sequence that is the ports of its most steps; a
     * node shows {@link #inputs(NodeSettings)}.
     */
    public List<Port> inputs() {
        return inputs;
    }

    /**
     * The input ports a node with {@code settings} has: the declared ones, except that a step sequence has those of its
     * own step count ({@link Sequences}), always the first ones, so a port's position never depends on the settings.
     */
    public List<Port> inputs(NodeSettings<?> settings) {
        if (op != Op.SEQUENCE) {
            return inputs;
        }
        return inputs.subList(0, Sequences.inputCount(Sequences.steps(settings)));
    }

    /**
     * The output ports a node with {@code settings} has. Fixed for every type but the constant and the parameter, whose
     * single output takes the type its kind setting picks, and the step sequence, which has the first ones for its step
     * count.
     */
    public List<Port> outputs(NodeSettings<?> settings) {
        if (op == Op.SEQUENCE) {
            return outputs.subList(0, Sequences.outputCount(Sequences.steps(settings)));
        }
        if (op != Op.CONSTANT && op != Op.PARAMETER) {
            return outputs;
        }
        int kind = setting(NodeTypes.KIND).map(spec -> spec.choiceIndex(settings.text(NodeTypes.KIND).orElse("")))
                .orElse(0);
        return List.of(outputs.get(kind));
    }

    /** The output ports as declared (for the constant, every kind's); see {@link #outputs(NodeSettings)}. */
    List<Port> declaredOutputs() {
        return outputs;
    }

    /** The settings in display order. */
    public List<SettingSpec> settings() {
        return settings;
    }

    /** The setting called {@code name}. */
    public Optional<SettingSpec> setting(String name) {
        for (SettingSpec spec : settings) {
            if (spec.name().equals(name)) {
                return Optional.of(spec);
            }
        }
        return Optional.empty();
    }

    /** Checks one evaluation of a node of this type costs against the team budget. */
    public int cost() {
        return cost;
    }

    /**
     * Action points each action a node of this type sends costs against the team's action budget, separate from its
     * {@link #cost} in checks; 0 for types that do not act.
     */
    public int actionPoints() {
        return op.actionPoints();
    }

    /** Longs of memory a node of this type keeps between runs (and saves). */
    public int memory() {
        return memory;
    }

    /**
     * Whether a loop may pass through this node: its inputs inside a loop are read one run later, so a loop through it
     * steps once per run instead of spinning (Remember and Delay nodes).
     */
    public boolean breaksLoops() {
        return breaksLoops;
    }

    /**
     * Whether the editor's palette lists the type under "Common", its default view; "All" lists every type.
     */
    public boolean common() {
        return common;
    }

    /** The node's player-facing name. */
    public String nameKey() {
        return nameKey;
    }

    /** One or two plain sentences on what the node does, shown when the palette row is hovered. */
    public String descriptionKey() {
        return LogicLang.PREFIX + "node." + id + ".desc";
    }

    /**
     * The node as a sentence, for the card view ({@link RuleCards}): a template whose {@code {name}} placeholders name
     * the type's settings and inputs, such as "every {interval} seconds". {@link NodeSentence} fills it in.
     */
    public String sentenceKey() {
        return LogicLang.PREFIX + "node." + id + ".sentence";
    }

    /** For a script node type, what runs it; null otherwise. */
    @Nullable
    ScriptNodeLogic script() {
        return script;
    }

    /** Whether this is a pack's script node type ({@link ScriptNodes}), registered or missing. */
    public boolean scripted() {
        return op == Op.SCRIPT;
    }

    /**
     * Whether this stands in for a script node type no script registers now (the script was removed or failed to load):
     * the node keeps its settings and wires, but the graph does not compile until the script is back.
     */
    public boolean missingScript() {
        return missingScript;
    }

    @Override
    public String toString() {
        return id;
    }
}
