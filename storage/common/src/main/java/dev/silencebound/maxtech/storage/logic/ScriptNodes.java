package dev.silencebound.maxtech.storage.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Node types a pack adds with scripts (KubeJS through {@code compat.kubejs}; tests directly). Types are registered
 * once, while the game starts, before any graph loads, and never change after {@link #close}: saved graphs name them by
 * id. Their ids are {@code namespace:path}, so they never clash with Storage's own (which have no namespace).
 *
 * <p>
 * A graph that names a script type no script registers now (the script was removed) still loads: the node becomes a
 * stand-in ({@link NodeType#missingScript()}) that keeps its settings and wires and stops the graph from compiling
 * until the script is back. Pasting or importing such a graph as text fails as for any unknown node type.
 *
 * <p>
 * Texts: each type's name, description, sentence, port and setting names are lang keys under
 * {@code gui.maxtech_storage.logic.node.<id>.*}; {@link #english()} holds the script's English for them, which the
 * KubeJS compat adds to the game's language. Registering happens on the thread that runs the startup scripts; reads are
 * on any thread after that.
 */
public final class ScriptNodes {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern PORT = Pattern.compile("[a-z0-9_]+");
    /** Longest id a script node type may have. */
    public static final int MAX_ID = 64;
    /** Stand-ins kept per missing id at most, so odd ids sent to the server can't grow this without bound. */
    private static final int MAX_MISSING = 256;

    private static final Map<String, NodeType> BY_ID = new LinkedHashMap<>();
    private static final Map<String, String> ENGLISH = new LinkedHashMap<>();
    private static final Map<String, NodeType> MISSING = new LinkedHashMap<>();
    private static volatile boolean closed;

    private ScriptNodes() {
    }

    /**
     * Registers {@code spec} as a node type and returns it. Throws {@link IllegalArgumentException} with a message for
     * the script author when the spec is incomplete or clashes, and {@link IllegalStateException} after {@link #close}.
     */
    public static synchronized NodeType register(ScriptNodeSpec spec) {
        if (closed) {
            throw new IllegalStateException(
                    "Script node types are registered while the game starts; restart the game to change them");
        }
        String id = spec.id();
        if (id.length() > MAX_ID || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Script node id " + id + " must look like mypack:my_node (lower case, at most " + MAX_ID + ")");
        }
        if (BY_ID.containsKey(id) || NodeTypes.builtIn(id)) {
            throw new IllegalArgumentException("Node type " + id + " is registered twice");
        }
        ScriptNodeLogic logic = spec.logic();
        if (logic == null) {
            throw new IllegalArgumentException("Script node " + id + " has no evaluate function");
        }
        String base = LogicLang.PREFIX + "node." + id;
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put(base, spec.english());
        if (!spec.descriptionText().isBlank()) {
            texts.put(base + ".desc", spec.descriptionText());
        }
        texts.put(base + ".sentence", spec.sentenceText());
        List<Port> inputs = new ArrayList<>();
        List<SettingSpec> settings = new ArrayList<>();
        for (ScriptNodeSpec.PortSpec in : ports(id, spec.inputSpecs(), "inputs")) {
            String key = base + ".port." + in.name();
            texts.put(key, words(in.name()));
            inputs.add(new Port(in.name(), in.type(), false, in.defaultValue(), key));
            if (in.setting()) {
                String settingKey = base + ".setting." + in.name();
                texts.put(settingKey, words(in.name()));
                settings.add(in.type() == PortType.NUMBER
                        ? new SettingSpec(in.name(), SettingKind.NUMBER, -SettingSpec.MAX_NUMBER,
                                SettingSpec.MAX_NUMBER, in.defaultValue(), List.of(), false, settingKey)
                        : new SettingSpec(in.name(), SettingKind.FLAG, 0, 1, in.defaultValue(), List.of(), false,
                                settingKey));
            }
        }
        List<Port> outputs = new ArrayList<>();
        for (ScriptNodeSpec.PortSpec out : ports(id, spec.outputSpecs(), "outputs")) {
            String key = base + ".port." + out.name();
            texts.put(key, words(out.name()));
            outputs.add(new Port(out.name(), out.type(), false, 0, key));
        }
        NodeType type = new NodeType(id, base, spec.categoryValue(), Op.SCRIPT, inputs, outputs, settings,
                spec.costValue(), spec.memoryValue(), false, false, logic, false);
        BY_ID.put(id, type);
        ENGLISH.putAll(texts);
        return type;
    }

    private static List<ScriptNodeSpec.PortSpec> ports(String id, List<ScriptNodeSpec.PortSpec> ports, String what) {
        if (ports.size() > ScriptNodeSpec.MAX_PORTS) {
            throw new IllegalArgumentException(
                    "Script node " + id + " has more than " + ScriptNodeSpec.MAX_PORTS + " " + what);
        }
        Set<String> names = new HashSet<>();
        for (ScriptNodeSpec.PortSpec port : ports) {
            if (port.name().length() > 32 || !PORT.matcher(port.name()).matches()) {
                throw new IllegalArgumentException("Script node " + id + ": port name " + port.name()
                        + " must be lower case letters, digits and _ (at most 32)");
            }
            if (!names.add(port.name())) {
                throw new IllegalArgumentException("Script node " + id + " has two " + what + " called " + port.name());
            }
        }
        return ports;
    }

    /** {@code some_name} as {@code Some name}, the English of a port the script names only by id. */
    private static String words(String name) {
        String spaced = name.replace('_', ' ').strip();
        return spaced.isEmpty() ? name : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** Ends registering; the KubeJS compat calls it once the startup scripts ran. */
    public static void close() {
        closed = true;
    }

    /** The registered type {@code id}. */
    static Optional<NodeType> byId(String id) {
        synchronized (ScriptNodes.class) {
            return Optional.ofNullable(BY_ID.get(id));
        }
    }

    /** Every registered type in registration order. */
    static synchronized List<NodeType> all() {
        return List.copyOf(BY_ID.values());
    }

    /**
     * The stand-in for script type {@code id}, which no script registers now, or empty when {@code id} is not a script
     * id. Stand-ins have no ports or settings; see {@link NodeType#missingScript()}.
     */
    static synchronized Optional<NodeType> missing(String id) {
        if (id.length() > MAX_ID || !ID.matcher(id).matches()) {
            return Optional.empty();
        }
        NodeType known = MISSING.get(id);
        if (known != null) {
            return Optional.of(known);
        }
        NodeType type = new NodeType(id, LogicLang.MISSING_SCRIPT_NODE, NodeCategory.CHECK, Op.SCRIPT, List.of(),
                List.of(), List.of(), 1, 0, false, false, null, true);
        if (MISSING.size() < MAX_MISSING) {
            MISSING.put(id, type);
        }
        return Optional.of(type);
    }

    /** Every script text key with the script's English, for the language. */
    public static synchronized Map<String, String> english() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(ENGLISH));
    }

    /** Forgets every script type; tests only. */
    static synchronized void clearForTests() {
        BY_ID.clear();
        ENGLISH.clear();
        MISSING.clear();
        closed = false;
    }
}
