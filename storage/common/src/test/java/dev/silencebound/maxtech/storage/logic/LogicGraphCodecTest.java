package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class LogicGraphCodecTest {
    private static final Codec<LogicGraph<String>> CODEC = LogicGraph.codec(Codec.STRING);

    private static LogicGraph<String> roundTrip(LogicGraph<String> graph) {
        JsonElement json = CODEC.encodeStart(JsonOps.INSTANCE, graph).getOrThrow();
        return CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }

    @Test
    void everySettingKindSurvivesARoundTrip() {
        LogicGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", "minecraft:cobblestone").withText("label", "chest")
                                .withText("mode", "below").withNumber("threshold", Long.MAX_VALUE / 16)
                                .withNumber("switch_back", -5))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "Low on cobble").withFlag("chat", true))
                .wire(1, "crossed", 2, "do").build();
        LogicGraph<String> loaded = roundTrip(graph);
        assertEquals(graph, loaded);
        assertEquals("minecraft:cobblestone",
                java.util.Objects.requireNonNull(loaded.node(1)).settings().item("item").orElseThrow());
    }

    @Test
    void savedFormatCarriesItsVersion() {
        JsonObject json = CODEC.encodeStart(JsonOps.INSTANCE, LogicGraph.empty()).getOrThrow().getAsJsonObject();
        assertEquals(LogicGraph.FORMAT_VERSION, json.get("version").getAsInt());
    }

    @Test
    void newerFormatIsRefusedRatherThanHalfRead() {
        JsonElement json = JsonParser.parseString("{\"version\": 2, \"nodes\": [], \"wires\": []}");
        assertTrue(CODEC.parse(JsonOps.INSTANCE, json).isError());
    }

    @Test
    void unknownNodeTypeIsAnError() {
        JsonElement json = JsonParser
                .parseString("{\"version\": 1, \"nodes\": [{\"id\": 1, \"type\": \"teleport\"}], \"wires\": []}");
        assertTrue(CODEC.parse(JsonOps.INSTANCE, json).isError());
    }

    @Test
    void oversizedListsAreRefused() {
        StringBuilder nodes = new StringBuilder();
        for (int i = 0; i <= LogicGraph.HARD_MAX_NODES; i++) {
            nodes.append(i == 0 ? "" : ",").append("{\"id\": ").append(i).append(", \"type\": \"constant\"}");
        }
        JsonElement json = JsonParser.parseString("{\"version\": 1, \"nodes\": [" + nodes + "]}");
        assertTrue(CODEC.parse(JsonOps.INSTANCE, json).isError());
    }

    @Test
    void settingsAreSavedWithTheirKind() {
        LogicGraph<String> graph = new TestGraph().node(1, NodeTypes.DELAY, s -> s.withNumber("ticks", 40)).build();
        JsonObject node = CODEC.encodeStart(JsonOps.INSTANCE, graph).getOrThrow().getAsJsonObject()
                .getAsJsonArray("nodes").get(0).getAsJsonObject();
        JsonObject ticks = node.getAsJsonObject("settings").getAsJsonObject("ticks");
        assertEquals("number", ticks.get("kind").getAsString());
        assertEquals(40, ticks.get("value").getAsLong());
        assertEquals("delay", node.get("type").getAsString());
    }
}
