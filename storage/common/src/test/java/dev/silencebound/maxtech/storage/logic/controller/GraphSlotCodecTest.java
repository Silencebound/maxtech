package dev.silencebound.maxtech.storage.logic.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.storage.logic.GraphChange;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.LogicMemory;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import java.util.ArrayList;
import dev.silencebound.maxtech.storage.logic.SettingValue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import org.junit.jupiter.api.Test;

/**
 * Saved graph slots (M7L2 chunk 8): a slot saved before simulate mode, the last working version and pins existed loads
 * unchanged with those off and empty; the new fields round-trip; pins on unknown or valueless nodes are dropped.
 */
class GraphSlotCodecTest {
    private static LogicGraph<LogicKey> graph(String tag) {
        LogicKey key = LogicKey.tag(TagKey.create(Registries.ITEM, Identifier.parse(tag)));
        return new LogicGraph<>(List.of(
                new LogicNode<>(1, NodeTypes.COUNT_BY_TAG, 10, 20, NodeSettings.<LogicKey>empty().withItem("tag", key)),
                new LogicNode<>(2, NodeTypes.NOTIFY, 30, 20, NodeSettings.<LogicKey>empty().withText("text", "hi"))),
                List.of());
    }

    private static final LogicMemory MEMORY = new LogicMemory(
            List.of(new LogicMemory.Entry(1, "count_by_tag", List.of(5L))));

    @Test
    void oldFormatLoadsUnchanged() {
        GraphSlot plain = new GraphSlot("Old rule", false, graph("c:ingots/iron"), MEMORY);
        JsonObject json = GraphSlot.CODEC.encodeStart(JsonOps.INSTANCE, plain).getOrThrow().getAsJsonObject();
        // The shape saved before chunk 8: only these four fields.
        json.remove("simulate");
        json.remove("previous");
        json.remove("pins");
        json.remove("history");
        assertEquals(List.of("name", "enabled", "graph", "memory"), List.copyOf(json.keySet()));
        GraphSlot loaded = GraphSlot.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(plain, loaded);
        assertFalse(loaded.simulate());
        assertTrue(loaded.previous().isEmpty());
        assertTrue(loaded.pins().isEmpty());
        assertEquals(MEMORY, loaded.memory());
    }

    @Test
    void newFieldsRoundTrip() {
        GraphSlot slot = new GraphSlot("Rule", true, graph("c:ingots/iron"), MEMORY, true,
                Optional.of(graph("c:ingots/gold")), List.of(1), List.of(
                        new HistoryEntry(48_000, 1_700_000_000_000L, Optional.of(UUID.randomUUID()), "Steve",
                                List.of(GraphChange.of(LogicLang.HISTORY_NODES_ADDED, "2"),
                                        new GraphChange(LogicLang.HISTORY_SETTING,
                                                List.of(GraphChange.Arg.key("node"), GraphChange.Arg.key("setting"),
                                                        GraphChange.Arg.text("100"), GraphChange.Arg.text("200"))))),
                        new HistoryEntry(48_100, 0, Optional.empty(), "",
                                List.of(GraphChange.of(LogicLang.HISTORY_REVERTED)))));
        JsonElement json = GraphSlot.CODEC.encodeStart(JsonOps.INSTANCE, slot).getOrThrow();
        assertEquals(slot, GraphSlot.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    /** M7L2 row 19: a copy with values saves its link and values and an empty graph; an ordinary rule no link. */
    @Test
    void aCopySavesOnlyItsLinkAndValues() {
        RuleInstance link = new RuleInstance(2, 42L,
                Map.of("amount", new SettingValue.Num<>(500), "item", new SettingValue.Item<>(
                        LogicKey.tag(TagKey.create(Registries.ITEM, Identifier.parse("c:ingots/gold"))))));
        GraphSlot copy = new GraphSlot("Copy", true, graph("c:ingots/iron"), MEMORY, false,
                Optional.of(graph("c:ingots/gold")), List.of(1, 7), List.of(), false, Optional.of(link), 7L);
        assertTrue(copy.previous().isEmpty(), "a copy keeps no earlier version");
        JsonObject json = GraphSlot.CODEC.encodeStart(JsonOps.INSTANCE, copy).getOrThrow().getAsJsonObject();
        GraphSlot loaded = GraphSlot.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(LogicGraph.empty(), loaded.graph(), "the worker makes the graph from the template's");
        assertEquals(Optional.of(link), loaded.instance());
        assertEquals(List.of(1, 7), loaded.pins(), "pins wait for the made graph to be cleaned");
        assertEquals(MEMORY, loaded.memory());
        assertEquals(7L, loaded.id(), "the rule id is kept");
        GraphSlot plain = new GraphSlot("Rule", true, graph("c:ingots/iron"), MEMORY);
        assertFalse(
                GraphSlot.CODEC.encodeStart(JsonOps.INSTANCE, plain).getOrThrow().getAsJsonObject().has("instance"));
    }

    @Test
    void pinsOnUnknownOrValuelessNodesAreDropped() {
        GraphSlot slot = new GraphSlot("Rule", true, graph("c:ingots/iron"), MEMORY, false, Optional.empty(),
                List.of(2, 1, 1, 99), List.of());
        assertEquals(List.of(1), slot.pins());
    }

    @Test
    void historyKeepsTheNewest() {
        List<HistoryEntry> history = List.of();
        for (int i = 0; i < 5; i++) {
            history = HistoryEntry.append(history, entry(i), 3);
        }
        assertEquals(List.of(entry(2), entry(3), entry(4)), history);
        assertEquals(List.of(), HistoryEntry.append(history, entry(5), 0));
        // A slot never holds more than the hard cap, whatever a save says.
        List<HistoryEntry> many = new ArrayList<>();
        for (int i = 0; i < HistoryEntry.HARD_MAX + 5; i++) {
            many.add(entry(i));
        }
        GraphSlot slot = new GraphSlot("Rule", true, graph("c:ingots/iron"), MEMORY, false, Optional.empty(), List.of(),
                many);
        assertEquals(HistoryEntry.HARD_MAX, slot.history().size());
        assertEquals(entry(HistoryEntry.HARD_MAX + 4), slot.history().getLast());
        assertEquals(3, new HistoryEntry(48_000, 0, Optional.empty(), "", List.of()).day(),
                "day 3 starts at tick 48000");
    }

    private static HistoryEntry entry(int i) {
        return new HistoryEntry(i * 1_000L, 0, Optional.empty(), "p" + i,
                List.of(GraphChange.of(LogicLang.HISTORY_PINS)));
    }
}
