package dev.silencebound.maxtech.storage.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A pack's description of a script node type, filled in by a startup script and turned into a {@link NodeType} by
 * {@link ScriptNodes#register}. Every method returns this, so a script chains them:
 *
 * <pre>
 * event.create('mypack:clamp').name('Clamp').sentence('{value} kept between {low} and {high}')
 *     .numberInput('value', 0).numberInput('low', 0).numberInput('high', 100).output('result', 'number')
 *     .evaluate(ctx => ctx.setNumber('result', Math.min(Math.max(ctx.number('value'), ctx.number('low')), ctx.number('high'))))
 * </pre>
 *
 * Port types are {@code number}, {@code yes_no} and {@code event}. Texts are English; other languages go in the pack's
 * lang files under the keys {@link ScriptNodes} lists. Not thread-safe; used only while scripts register types.
 */
public final class ScriptNodeSpec {
    /** Most inputs, and most outputs, a script node type has. */
    public static final int MAX_PORTS = 8;
    /** Most memory slots a script node type keeps. */
    public static final int MAX_MEMORY = 8;
    /** The most checks one run of a script node may cost. */
    public static final int MAX_COST = 64;
    /** The checks a run costs when the script does not say. */
    public static final int DEFAULT_COST = 4;

    /** One port as the script declared it; {@code setting} gives an input a same-named setting. */
    record PortSpec(String name, PortType type, boolean setting, long defaultValue) {
    }

    private final String id;
    private String name;
    private String description = "";
    private String sentence = "";
    private NodeCategory category = NodeCategory.CHECK;
    private int cost = DEFAULT_COST;
    private int memory;
    private final List<PortSpec> inputs = new ArrayList<>();
    private final List<PortSpec> outputs = new ArrayList<>();
    private @Nullable ScriptNodeLogic logic;

    ScriptNodeSpec(String id) {
        this.id = id;
        this.name = id;
    }

    /** A new description of the type {@code id} ({@code namespace:path}); {@link ScriptNodes#register} checks it. */
    public static ScriptNodeSpec of(String id) {
        return new ScriptNodeSpec(id);
    }

    /** The node's name in the palette and on the node; the id when not set. */
    public ScriptNodeSpec name(String english) {
        this.name = english;
        return this;
    }

    /** One or two sentences shown when the palette row is hovered. */
    public ScriptNodeSpec description(String english) {
        this.description = english;
        return this;
    }

    /**
     * The node in the card view: a template whose {@code {name}} placeholders name inputs, such as
     * {@code "{value} kept between {low} and {high}"}. The name when not set.
     */
    public ScriptNodeSpec sentence(String english) {
        this.sentence = english;
        return this;
    }

    /** The palette group: {@code read}, {@code check} (the default), {@code remember} or {@code time}. */
    public ScriptNodeSpec category(String category) {
        this.category = switch (category.toLowerCase(Locale.ROOT)) {
            case "read" -> NodeCategory.READ;
            case "check" -> NodeCategory.CHECK;
            case "remember" -> NodeCategory.REMEMBER;
            case "time" -> NodeCategory.TIME;
            default -> throw new IllegalArgumentException(
                    "Script node category " + category + " is not one of read, check, remember, time");
        };
        return this;
    }

    /**
     * Checks of the team budget one run of the node costs, 1 to {@link #MAX_COST}; {@link #DEFAULT_COST} if not set.
     */
    public ScriptNodeSpec cost(int checks) {
        if (checks < 1 || checks > MAX_COST) {
            throw new IllegalArgumentException("Script node cost " + checks + " is outside 1 to " + MAX_COST);
        }
        this.cost = checks;
        return this;
    }

    /** Memory slots ({@link ScriptNodeContext#memory}) kept between runs and saved, 0 to {@link #MAX_MEMORY}. */
    public ScriptNodeSpec memory(int slots) {
        if (slots < 0 || slots > MAX_MEMORY) {
            throw new IllegalArgumentException("Script node memory " + slots + " is outside 0 to " + MAX_MEMORY);
        }
        this.memory = slots;
        return this;
    }

    /** An input only a wire fills: {@code number}, {@code yes_no} or {@code event}. */
    public ScriptNodeSpec input(String name, String type) {
        inputs.add(new PortSpec(name, portType(type), false, 0));
        return this;
    }

    /** A number input with a same-named setting the player fills in, used while no wire goes into it. */
    public ScriptNodeSpec numberInput(String name, long defaultValue) {
        inputs.add(new PortSpec(name, PortType.NUMBER, true, defaultValue));
        return this;
    }

    /** A yes/no input with a same-named setting, used while no wire goes into it. */
    public ScriptNodeSpec yesNoInput(String name, boolean defaultValue) {
        inputs.add(new PortSpec(name, PortType.YES_NO, true, defaultValue ? 1 : 0));
        return this;
    }

    /** An output: {@code number}, {@code yes_no} or {@code event}. */
    public ScriptNodeSpec output(String name, String type) {
        outputs.add(new PortSpec(name, portType(type), false, 0));
        return this;
    }

    /** What the node does when it runs. Required. */
    public ScriptNodeSpec evaluate(ScriptNodeLogic logic) {
        this.logic = logic;
        return this;
    }

    private static PortType portType(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "number" -> PortType.NUMBER;
            case "yes_no", "yesno", "boolean" -> PortType.YES_NO;
            case "event", "trigger" -> PortType.EVENT;
            default -> throw new IllegalArgumentException("Port type " + type + " is not one of number, yes_no, event");
        };
    }

    String id() {
        return id;
    }

    String english() {
        return name;
    }

    String descriptionText() {
        return description;
    }

    String sentenceText() {
        return sentence.isBlank() ? name : sentence;
    }

    NodeCategory categoryValue() {
        return category;
    }

    int costValue() {
        return cost;
    }

    int memoryValue() {
        return memory;
    }

    List<PortSpec> inputSpecs() {
        return inputs;
    }

    List<PortSpec> outputSpecs() {
        return outputs;
    }

    @Nullable
    ScriptNodeLogic logic() {
        return logic;
    }
}
