package dev.silencebound.maxtech.storage.logic.template;

import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.Wire;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.minecraft.resources.Identifier;

/**
 * The rule templates in Storage's jar (M7L2 sharing): the logic showcase's examples as starting points. Datagen writes
 * them to {@code data/maxtech_storage/maxtech_storage/logic_templates/<path>.json}; the server loads them like any
 * pack's ({@link LogicTemplateListener}). Labels ("lamp", "intake") and item choices are examples the player changes; a
 * label no device carries shows the editor's "not on this network" note. Notice texts are plain text a player would
 * type, like the showcase's, not lang keys. Built over any item type so tests can check them without the game.
 */
public final class BuiltInTemplates {
    /** The categories the built-in templates use, with their English names, in list order. */
    public static final Map<String, String> CATEGORIES = categories();

    /** Canvas distance between node columns (nodes are 76 wide). */
    private static final int COLUMN = 96;
    /** Canvas distance between node rows. */
    private static final int ROW = 56;

    private BuiltInTemplates() {
    }

    private static Map<String, String> categories() {
        Map<String, String> categories = new LinkedHashMap<>();
        categories.put("redstone", "Redstone");
        categories.put("items", "Items");
        categories.put("crafting", "Crafting");
        categories.put("alerts", "Alerts");
        categories.put(LogicTemplate.OTHER, "Other");
        return categories;
    }

    /**
     * The built-in templates by file path (under the {@code maxtech_storage} namespace), in list order; {@code item}
     * turns an item id into an item setting.
     */
    public static <K> Map<String, LogicTemplate<K>> all(Function<String, K> item) {
        K cobble = item.apply("minecraft:cobblestone");
        K stick = item.apply("minecraft:stick");
        Map<String, LogicTemplate<K>> all = new LinkedHashMap<>();
        all.put("lamp_over_count", template("Lamp over a count",
                "Lights the lamp labelled \"lamp\" while the network holds over 100 cobblestone, and turns it off again "
                        + "at 64. Pick your item, limits and label.",
                "items", "minecraft:redstone_lamp",
                new Builder<K>()
                        .node(1, NodeTypes.COUNT_CROSSES, 0, 0,
                                s -> s.withItem("item", cobble).withNumber("threshold", 100).withNumber("switch_back",
                                        64))
                        .node(2, NodeTypes.REDSTONE_OUT, 1, 0, s -> s.withText("label", "lamp"))
                        .wire(1, "past", 2, "on").build()));
        all.put("keep_stocked", template("Keep N of an item",
                "When the network has fewer than 16 sticks, asks the crafting system for 32 more. Change the item, the "
                        + "limit and the amount.",
                "crafting", "minecraft:crafting_table",
                new Builder<K>()
                        .node(1, NodeTypes.COUNT_CROSSES, 0, 0,
                                s -> s.withItem("item", stick).withText("mode", "below").withNumber("threshold", 16))
                        .node(2, NodeTypes.REQUEST_CRAFT, 1, 0, s -> s.withItem("item", stick).withNumber("amount", 32))
                        .wire(1, "crossed", 2, "do").build()));
        all.put("button_moves_out", template("Button moves items out",
                "A button on the port labelled \"outbox\" moves 16 cobblestone from the network into the block that "
                        + "port faces.",
                "items", "minecraft:stone_button",
                new Builder<K>().node(1, NodeTypes.REDSTONE_CHANGES, 0, 0, s -> s.withText("label", "outbox"))
                        .node(2, NodeTypes.COMPARE, 1, 0, s -> s)
                        .node(3, NodeTypes.MOVE_ITEMS, 2, 0,
                                s -> s.withItem("item", cobble).withText("to", "outbox").withNumber("amount", 16))
                        .wire(1, "strength", 2, "value").wire(2, "turned_yes", 3, "do").build()));
        all.put("craft_finished_notice",
                template("Notice when a craft finishes",
                        "Posts a notice to the network log, the team and bound displays when a crafting job for sticks "
                                + "finishes.",
                        "alerts", "minecraft:bell",
                        new Builder<K>().node(1, NodeTypes.JOB_FINISHED, 0, 0, s -> s.withItem("item", stick))
                                .node(2, NodeTypes.NOTIFY, 1, 0,
                                        s -> s.withText("text", "Sticks are ready").withFlag("displays", true))
                                .wire(1, "finished", 2, "do").build()));
        all.put("lever_switches_device", template("Turn a device on with a lever",
                "A lever on the controller turns the device labelled \"intake\" on, and off again when the lever goes "
                        + "off.",
                "redstone", "minecraft:lever",
                new Builder<K>().node(1, NodeTypes.REDSTONE_CHANGES, 0, 0, s -> s)
                        .node(2, NodeTypes.COMPARE, 1, 0, s -> s)
                        .node(3, NodeTypes.DEVICE_SWITCH, 2, 0, s -> s.withText("label", "intake"))
                        .wire(1, "strength", 2, "value").wire(2, "result", 3, "on").build()));
        all.put("low_power_alert", template("Alert when power is low",
                "Posts a notice when the network's stored energy falls below 20 percent; it can fire again once the "
                        + "store is back over 30 percent.",
                "alerts", "minecraft:redstone_torch",
                new Builder<K>().node(1, NodeTypes.ENERGY, 0, 0, s -> s).node(2, NodeTypes.COMPARE, 1, 0,
                        s -> s.withText("mode", "below").withNumber("threshold", 20).withNumber("switch_back", 30))
                        .node(3, NodeTypes.NOTIFY, 2, 0, s -> s.withText("text", "Network power is low"))
                        .wire(1, "percent", 2, "value").wire(2, "turned_yes", 3, "do").build()));
        all.put("sensor_alert", template("Notice on a sensor value",
                "Posts a notice when the sensor labelled \"chest sensor\" sees more than 32 items in its block; it can "
                        + "fire again after it fell to 16.",
                "alerts", "minecraft:observer",
                new Builder<K>()
                        .node(1, NodeTypes.SENSOR_CROSSES, 0, 0,
                                s -> s.withText("label", "chest sensor").withText("value", "items.total")
                                        .withNumber("threshold", 32).withNumber("switch_back", 16))
                        .node(2, NodeTypes.NOTIFY, 1, 0, s -> s.withText("text", "The sensed chest holds over 32 items")
                                .withFlag("toast", false).withFlag("displays", true))
                        .wire(1, "crossed", 2, "do").build()));
        all.put("priority_follows_lever", template("Priority follows a lever",
                "Sets the priority of the storage labelled \"bank\" to the redstone strength on the controller: 15 "
                        + "with a lever on, 0 off.",
                "redstone", "minecraft:comparator",
                new Builder<K>().node(1, NodeTypes.REDSTONE_CHANGES, 0, 0, s -> s)
                        .node(2, NodeTypes.SET_PRIORITY, 1, 0, s -> s.withText("label", "bank"))
                        .wire(1, "changed", 2, "do").wire(1, "strength", 2, "priority").build()));
        all.put("batch_process", template("Batch process: fill, wait, empty",
                "Three steps in turn: the device labelled \"inlet\" runs until the storage labelled \"batch\" holds 64 "
                        + "cobblestone, the batch then waits 30 seconds, and \"outlet\" runs until \"batch\" is empty; "
                        + "then it starts again. Change the labels, item, amounts and time.",
                "items", "minecraft:cauldron",
                new Builder<K>()
                        .node(1, NodeTypes.ITEM_COUNT, 0, 0, s -> s.withItem("item", cobble).withText("label", "batch"))
                        .node(2, NodeTypes.COMPARE, 1, 0, s -> s.withText("mode", "above").withNumber("threshold", 63))
                        .node(3, NodeTypes.COMPARE, 1, 2, s -> s.withText("mode", "below").withNumber("threshold", 1))
                        .node(4, NodeTypes.SEQUENCE, 2, 0,
                                s -> s.withNumber("steps", 3).withText("name_1", "fill").withText("name_2", "wait")
                                        .withNumber("timeout_2", 30).withText("name_3", "empty"))
                        .node(5, NodeTypes.STEP_IS, 4, 0, s -> s.withText("which", "fill"))
                        .node(6, NodeTypes.STEP_IS, 4, 2, s -> s.withText("which", "empty"))
                        .node(7, NodeTypes.DEVICE_SWITCH, 5, 0, s -> s.withText("label", "inlet"))
                        .node(8, NodeTypes.DEVICE_SWITCH, 5, 2, s -> s.withText("label", "outlet"))
                        .node(9, NodeTypes.NOTIFY, 5, 4, s -> s.withText("text", "Batch ready, emptying it"))
                        .wire(1, "count", 2, "value").wire(1, "count", 3, "value").wire(2, "result", 4, "next_1")
                        .wire(3, "result", 4, "next_3").wire(4, "step", 5, "step").wire(4, "step", 6, "step")
                        .wire(5, "result", 7, "on").wire(6, "result", 8, "on").wire(4, "entered_3", 9, "do").build()));
        K rawIron = item.apply("minecraft:raw_iron");
        all.put("feed_furnace_array", template("Feed a furnace array",
                "Sends raw iron to the furnaces labelled \"furnaces\": 8 to one as soon as it stops burning, and 16 "
                        + "every 30 seconds spread over those not burning. Put a port labelled \"furnaces\" on top of "
                        + "each furnace; it reads whether its furnace burns.",
                "items", "minecraft:furnace",
                new Builder<K>()
                        .node(1, NodeTypes.POOL_MEMBER_IDLE, 0, 0,
                                s -> s.withText("label", "furnaces").withText("idle_value", "state.lit"))
                        .node(2, NodeTypes.MOVE_TO_POOL, 1, 0,
                                s -> s.withText("label", "furnaces").withItem("item", rawIron).withNumber("amount", 8)
                                        .withText("idle_value", "state.lit"))
                        .node(3, NodeTypes.TIMER, 0, 2, s -> s.withNumber("interval", 30))
                        .node(4, NodeTypes.MOVE_TO_POOL, 1, 2,
                                s -> s.withText("label", "furnaces").withItem("item", rawIron).withNumber("amount", 16)
                                        .withText("strategy", "spread_evenly").withText("idle_value", "state.lit"))
                        .wire(1, "turned_idle", 2, "do").wire(3, "fired", 4, "do").build()));
        K ores = item.apply("#c:ores");
        all.put("keep_every_ore_stocked", template("Keep every ore stocked",
                "Every 30 seconds, goes through each item of the tag c:ores and asks the crafting system for 64 of "
                        + "each one the network holds fewer than 1,000 of. Change the tag (or list the items), the limit "
                        + "and the amount; items nothing can craft are skipped.",
                "crafting", "minecraft:iron_ore",
                new Builder<K>().node(1, NodeTypes.TIMER, 0, 0, s -> s.withNumber("interval", 30))
                        .node(2, NodeTypes.FOR_EACH, 1, 0, s -> s.withText("source", "tag").withItem("tag", ores))
                        .node(3, NodeTypes.ITEM_COUNT, 2, 1, s -> s)
                        .node(4, NodeTypes.COMPARE, 3, 1,
                                s -> s.withText("mode", "below").withNumber("threshold", 1_000))
                        .node(5, NodeTypes.IF, 3, 0, s -> s)
                        .node(6, NodeTypes.REQUEST_CRAFT, 4, 0, s -> s.withNumber("amount", 64))
                        .wire(1, "fired", 2, "do").wire(2, "item", 3, "item").wire(3, "count", 4, "value")
                        .wire(2, "each", 5, "when").wire(4, "result", 5, "condition").wire(5, "then", 6, "do")
                        .wire(2, "item", 6, "item").build()));
        all.put("keep_item_above", template("Keep an item above an amount",
                "A rule with inputs: when the network's count of the item drops below the amount, asks the crafting "
                        + "system for a batch. Adding it asks for the item, the amount and the batch; Copy with values "
                        + "makes more rules like it for other items.",
                "crafting", "minecraft:crafter", keepAbove(stick)));
        return all;
    }

    /**
     * "Keep {item} above {amount}" with its inputs as parameters (M7L2 row 19): the item's count below the amount turns
     * the check yes, which asks for a batch of the item.
     */
    public static <K> LogicGraph<K> keepAbove(K item) {
        return new Builder<K>()
                .node(1, NodeTypes.PARAMETER, 0, 0,
                        s -> s.withText("name", "item").withText("kind", "item").withItem("item", item))
                .node(2, NodeTypes.PARAMETER, 0, 1,
                        s -> s.withText("name", "amount").withText("kind", "number").withNumber("value", 64))
                .node(3, NodeTypes.PARAMETER, 0, 2,
                        s -> s.withText("name", "batch").withText("kind", "number").withNumber("value", 32))
                .node(4, NodeTypes.ITEM_COUNT, 1, 0, s -> s)
                .node(5, NodeTypes.COMPARE, 2, 0, s -> s.withText("mode", "below"))
                .node(6, NodeTypes.REQUEST_CRAFT, 3, 0, s -> s).wire(1, "value", 4, "item").wire(4, "count", 5, "value")
                .wire(2, "value", 5, "threshold").wire(5, "turned_yes", 6, "do").wire(1, "value", 6, "item")
                .wire(3, "value", 6, "amount").build();
    }

    private static <K> LogicTemplate<K> template(String name, String description, String category, String icon,
            LogicGraph<K> graph) {
        return new LogicTemplate<>(name, description, category, Optional.of(Identifier.parse(icon)), graph);
    }

    private static final class Builder<K> {
        private final List<LogicNode<K>> nodes = new ArrayList<>();
        private final List<Wire> wires = new ArrayList<>();

        Builder<K> node(int id, NodeType type, int column, int row, UnaryOperator<NodeSettings<K>> settings) {
            nodes.add(new LogicNode<>(id, type, column * COLUMN, row * ROW, settings.apply(NodeSettings.empty())));
            return this;
        }

        Builder<K> wire(int from, String output, int to, String input) {
            wires.add(new Wire(from, output, to, input));
            return this;
        }

        LogicGraph<K> build() {
            return new LogicGraph<>(nodes, wires);
        }
    }
}
