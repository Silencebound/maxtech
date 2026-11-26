package dev.silencebound.maxtech.storage.logic.editor;

import dev.silencebound.maxtech.storage.logic.ForEach;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.Port;
import dev.silencebound.maxtech.storage.logic.Sequences;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import dev.silencebound.maxtech.storage.logic.Wire;

/**
 * Which of a node's settings the settings panel shows and whether one is set by a wire (M7L area F). A constant shows
 * only the value of its kind; a compare shows the upper limit only for "between" and the switch-back value only for
 * "above" and "below", where it applies; statistics show the size of the window they use; a port filter node shows its
 * item only while it names a filter slot; a shared variable shows its number value only for the number kind; a pool
 * send or status shows its idle condition only once an idle sensor value is named; a For each shows the tag, the list
 * or the stock keeper's label by its source. An input with a setting of the same name reads the wire while one goes in,
 * so the panel shows that setting as set by the wire. Pure.
 */
public final class SettingRules {
    private SettingRules() {
    }

    /** Whether the panel shows {@code spec} for a node of {@code type} with {@code settings}. */
    public static boolean shows(NodeType type, NodeSettings<?> settings, SettingSpec spec) {
        String name = spec.name();
        if (type == NodeTypes.CONSTANT || type == NodeTypes.PARAMETER) {
            String kind = choice(type, settings, "kind");
            return switch (name) {
                case "value" -> kind.equals("number");
                case "yes" -> kind.equals("yes_no");
                case "item" -> kind.equals("item");
                case "label" -> kind.equals("label");
                default -> true;
            };
        }
        if (type == NodeTypes.COMPARE) {
            String mode = choice(type, settings, "mode");
            return switch (name) {
                case "upper" -> mode.equals("between");
                case "switch_back" -> mode.equals("above") || mode.equals("below");
                default -> true;
            };
        }
        if ((type == NodeTypes.MOVE_TO_POOL || type == NodeTypes.POOL_STATUS)
                && (name.equals("idle_when") || name.equals("idle_limit"))) {
            // The idle condition applies only once a sensor value says what idle is.
            return settings.text("idle_value").filter(text -> !text.isBlank()).isPresent();
        }
        if (type == NodeTypes.STATS) {
            String window = choice(type, settings, "window");
            return switch (name) {
                case "samples" -> window.equals("samples");
                case "seconds" -> window.equals("seconds");
                default -> true;
            };
        }
        if (type == NodeTypes.SEQUENCE && !name.equals("steps")) {
            // Each step's name and time limit, for the steps the sequence has.
            int steps = Sequences.steps(settings);
            for (int k = steps + 1; k <= Sequences.MAX_STEPS; k++) {
                if (name.equals(Sequences.name(k)) || name.equals(Sequences.timeout(k))) {
                    return false;
                }
            }
            return true;
        }
        if (type == NodeTypes.FOR_EACH && !name.equals("source")) {
            // The tag, the list (as many items as it holds) or the stock keeper's label, by the source.
            int source = ForEach.source(settings);
            if (name.equals("tag")) {
                return source == ForEach.SOURCE_TAG;
            }
            if (name.equals("label")) {
                return source == ForEach.SOURCE_STOCK;
            }
            if (source != ForEach.SOURCE_LIST) {
                return false;
            }
            int size = ForEach.listSize(settings);
            for (int k = size + 1; k <= ForEach.MAX_LIST; k++) {
                if (name.equals(ForEach.item(k))) {
                    return false;
                }
            }
            return true;
        }
        if (type == NodeTypes.SET_SHARED_VARIABLE && name.equals("value")) {
            // A yes/no variable takes its "yes" input; the number setting is for numbers.
            return choice(type, settings, "kind").equals("number");
        }
        if (name.equals("label") && NodeTypes.channel(type, settings) >= 0) {
            // A network redstone channel (M7L2) replaces the label.
            return false;
        }
        if (type == NodeTypes.SET_FILTER && name.equals("item")) {
            // The item goes into the filter slot; with no slot picked there is nowhere for it.
            return type.setting("slot").map(slot -> slot.clamp(settings.number("slot").orElse(slot.defaultValue())))
                    .orElse(0L) != 0;
        }
        return true;
    }

    /** The choice id {@code name} holds, or the spec's default. */
    public static String choice(NodeType type, NodeSettings<?> settings, String name) {
        return type.setting(name).map(spec -> {
            int index = spec.choiceIndex(settings.text(name).orElse(""));
            return index >= 0 && index < spec.choices().size() ? spec.choices().get(index) : "";
        }).orElse("");
    }

    /** Whether {@code node}'s setting {@code spec} is an input that a wire feeds in {@code graph}. */
    public static boolean wired(LogicGraph<?> graph, LogicNode<?> node, SettingSpec spec) {
        boolean isInput = false;
        for (Port port : node.type().inputs()) {
            isInput |= port.name().equals(spec.name());
        }
        if (!isInput) {
            return false;
        }
        for (Wire wire : graph.wires()) {
            if (wire.to() == node.id() && wire.input().equals(spec.name())) {
                return true;
            }
        }
        return false;
    }
}
