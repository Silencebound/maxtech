package dev.silencebound.maxtech.storage.dev;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.Wire;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.world.item.Items;

/**
 * The example graphs of the showcase's logic station: one per action, one on a sensor crossing and one with a counter
 * and a latch. The labels match the devices {@link Showcase} places. Graph names and notice texts are what a player
 * would type, so they are plain English data like a sign's text, not lang keys.
 */
public final class LogicExamples {
    /** Graph names, in the controller's order. */
    public static final List<String> NAMES = List.of("Intake follows lever", "Priority follows lever",
            "Lamp over 100 cobble", "Keep 16 sticks", "Button moves 16 out", "Notice: sticks ready",
            "Chest sensor over 32", "Count lever flips");
    /** Notice text of the sticks graph. */
    public static final String STICKS_READY = "Sticks are ready";
    /** Notice text of the chest sensor graph. */
    public static final String CHEST_FULL = "The sensed chest holds over 32 items";

    private static final LogicKey COBBLE = LogicKey.of(ItemKey.of(Items.COBBLESTONE));
    private static final LogicKey STICK = LogicKey.of(ItemKey.of(Items.STICK));
    /** Canvas distance between node columns (nodes are 76 wide). */
    private static final int COLUMN = 96;
    /** Canvas distance between node rows. */
    private static final int ROW = 56;

    private LogicExamples() {
    }

    /** The eight graphs, in the order of {@link #NAMES}. */
    public static List<LogicGraph<LogicKey>> graphs() {
        List<LogicGraph<LogicKey>> graphs = new ArrayList<>();
        // The lever on the controller: on turns the intake port on, off turns it off.
        graphs.add(
                new Builder().node(1, NodeTypes.REDSTONE_CHANGES, 0, 0, s -> s).node(2, NodeTypes.COMPARE, 1, 0, s -> s)
                        .node(3, NodeTypes.DEVICE_SWITCH, 2, 0, s -> s.withText("label", "intake"))
                        .wire(1, "strength", 2, "value").wire(2, "result", 3, "on").build());
        // The drive's priority is the lever's strength: 15 on, 0 off.
        graphs.add(new Builder().node(1, NodeTypes.REDSTONE_CHANGES, 0, 0, s -> s)
                .node(2, NodeTypes.SET_PRIORITY, 1, 0, s -> s.withText("label", "bank")).wire(1, "changed", 2, "do")
                .wire(1, "strength", 2, "priority").build());
        // Over 100 cobblestone lights the lamp; it goes out again at 64 or less.
        graphs.add(
                new Builder()
                        .node(1, NodeTypes.COUNT_CROSSES, 0, 0,
                                s -> s.withItem("item", COBBLE).withNumber("threshold", 100).withNumber("switch_back",
                                        64))
                        .node(2, NodeTypes.REDSTONE_OUT, 1, 0, s -> s.withText("label", "lamp"))
                        .wire(1, "past", 2, "on").build());
        // Fewer than 16 sticks: craft 32.
        graphs.add(new Builder()
                .node(1, NodeTypes.COUNT_CROSSES, 0, 0,
                        s -> s.withItem("item", STICK).withText("mode", "below").withNumber("threshold", 16))
                .node(2, NodeTypes.REQUEST_CRAFT, 1, 0, s -> s.withItem("item", STICK).withNumber("amount", 32))
                .wire(1, "crossed", 2, "do").build());
        // The button on the outbox port moves 16 cobblestone from the network into the chest it faces.
        graphs.add(new Builder().node(1, NodeTypes.REDSTONE_CHANGES, 0, 0, s -> s.withText("label", "outbox"))
                .node(2, NodeTypes.COMPARE, 1, 0, s -> s)
                .node(3, NodeTypes.MOVE_ITEMS, 2, 0,
                        s -> s.withItem("item", COBBLE).withText("to", "outbox").withNumber("amount", 16))
                .wire(1, "strength", 2, "value").wire(2, "turned_yes", 3, "do").build());
        // A finished sticks job posts a notice to the log, the team and the display wall.
        graphs.add(new Builder().node(1, NodeTypes.JOB_FINISHED, 0, 0, s -> s.withItem("item", STICK))
                .node(2, NodeTypes.NOTIFY, 1, 0, s -> s.withText("text", STICKS_READY).withFlag("displays", true))
                .wire(1, "finished", 2, "do").build());
        // The sensor on the chest: over 32 items posts a notice to the log and the display wall.
        graphs.add(new Builder()
                .node(1, NodeTypes.SENSOR_CROSSES, 0, 0,
                        s -> s.withText("label", "chest sensor").withText("value", "items.total")
                                .withNumber("threshold", 32).withNumber("switch_back", 16))
                .node(2, NodeTypes.NOTIFY, 1, 0,
                        s -> s.withText("text", CHEST_FULL).withFlag("toast", false).withFlag("displays", true))
                .wire(1, "crossed", 2, "do").build());
        // Each time the lever over the cable turns on, count; the third sets the latch, which lights its lamp. The
        // lever on the controller resets both.
        graphs.add(new Builder()
                .node(1, NodeTypes.SENSOR_CROSSES, 0, 0,
                        s -> s.withText("label", "lever sensor").withText("value", "state.powered"))
                .node(2, NodeTypes.COUNTER, 1, 0, s -> s)
                .node(3, NodeTypes.COMPARE, 2, 0, s -> s.withNumber("threshold", 2))
                .node(4, NodeTypes.LATCH, 3, 0, s -> s)
                .node(5, NodeTypes.REDSTONE_OUT, 4, 0, s -> s.withText("label", "latch lamp"))
                .node(6, NodeTypes.REDSTONE_CHANGES, 0, 2, s -> s).wire(1, "crossed", 2, "up")
                .wire(2, "count", 3, "value").wire(3, "turned_yes", 4, "set").wire(4, "state", 5, "on")
                .wire(6, "changed", 4, "reset").wire(6, "changed", 2, "reset").build());
        return graphs;
    }

    /** Graph names of the monitoring scene's Logic Controller, in the order of {@link #monitoring()}. */
    public static final List<String> MONITORING_NAMES = List.of("Lamp over 100 cobble", "Lamp follows lever");

    /**
     * The monitoring scene's graphs ({@link ShowcaseScenes}): the condition switch "lamp" lights over 100 cobblestone
     * (out again at 64 or less), and "lever lamp" follows the lever on the controller.
     */
    public static List<LogicGraph<LogicKey>> monitoring() {
        List<LogicGraph<LogicKey>> graphs = new ArrayList<>();
        graphs.add(graphs().get(2));
        graphs.add(
                new Builder().node(1, NodeTypes.REDSTONE_CHANGES, 0, 0, s -> s).node(2, NodeTypes.COMPARE, 1, 0, s -> s)
                        .node(3, NodeTypes.REDSTONE_OUT, 2, 0, s -> s.withText("label", "lever lamp"))
                        .wire(1, "strength", 2, "value").wire(2, "result", 3, "on").build());
        return graphs;
    }

    private static final class Builder {
        private final List<LogicNode<LogicKey>> nodes = new ArrayList<>();
        private final List<Wire> wires = new ArrayList<>();

        Builder node(int id, NodeType type, int column, int row, UnaryOperator<NodeSettings<LogicKey>> settings) {
            nodes.add(new LogicNode<>(id, type, column * COLUMN, row * ROW, settings.apply(NodeSettings.empty())));
            return this;
        }

        Builder wire(int from, String output, int to, String input) {
            wires.add(new Wire(from, output, to, input));
            return this;
        }

        LogicGraph<LogicKey> build() {
            return new LogicGraph<>(nodes, wires);
        }
    }
}
