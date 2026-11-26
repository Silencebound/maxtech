package dev.silencebound.maxtech.storage.logic.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.storage.logic.CompileResult;
import dev.silencebound.maxtech.storage.logic.LogicCompiler;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class LogicTemplateTest {
    private static final Codec<LogicTemplate<String>> CODEC = LogicTemplate.codec(LogicGraph.codec(Codec.STRING));
    private static final String GRAPH = "{\"version\": 1, \"nodes\": [{\"id\": 1, \"type\": \"count_crosses\", "
            + "\"settings\": {\"item\": {\"kind\": \"item\", \"value\": \"minecraft:cobblestone\"}, "
            + "\"threshold\": {\"kind\": \"number\", \"value\": 100}}}, "
            + "{\"id\": 2, \"type\": \"redstone_out\", \"x\": 96, "
            + "\"settings\": {\"label\": {\"kind\": \"text\", \"value\": \"lamp\"}}}], "
            + "\"wires\": [{\"from\": 1, \"out\": \"past\", \"to\": 2, \"in\": \"on\"}]}";

    private static DataResult<LogicTemplate<String>> parse(String json) {
        return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    @Test
    void aFullFileParses() {
        LogicTemplate<String> template = parse("{\"name\": \"Lamp\", \"description\": \"Lights a lamp\", "
                + "\"category\": \"redstone\", \"icon\": \"minecraft:redstone_lamp\", \"graph\": " + GRAPH + "}")
                .getOrThrow();
        assertEquals("Lamp", template.name());
        assertEquals("Lights a lamp", template.description());
        assertEquals("redstone", template.category());
        assertEquals(Optional.of(Identifier.parse("minecraft:redstone_lamp")), template.icon());
        assertEquals(2, template.graph().nodes().size());
        assertEquals(1, template.graph().wires().size());
    }

    @Test
    void onlyNameAndGraphAreNeeded() {
        LogicTemplate<String> template = parse("{\"name\": \"Lamp\", \"graph\": " + GRAPH + "}").getOrThrow();
        assertEquals("", template.description());
        assertEquals(LogicTemplate.OTHER, template.category());
        assertTrue(template.icon().isEmpty());
    }

    @Test
    void badFilesAreRefused() {
        assertTrue(parse("{\"graph\": " + GRAPH + "}").isError(), "no name");
        assertTrue(parse("{\"name\": \"\", \"graph\": " + GRAPH + "}").isError(), "empty name");
        assertTrue(parse("{\"name\": \"" + "x".repeat(LogicTemplate.MAX_NAME + 1) + "\", \"graph\": " + GRAPH + "}")
                .isError(), "name too long");
        assertTrue(parse("{\"name\": \"Lamp\", \"description\": \"" + "x".repeat(LogicTemplate.MAX_DESCRIPTION + 1)
                + "\", \"graph\": " + GRAPH + "}").isError(), "description too long");
        assertTrue(parse("{\"name\": \"Lamp\", \"category\": \"Red Stone\", \"graph\": " + GRAPH + "}").isError(),
                "category with capitals and a space");
        assertTrue(parse("{\"name\": \"Lamp\"}").isError(), "no graph");
        assertTrue(parse("{\"name\": \"Lamp\", \"graph\": " + GRAPH.replace("redstone_out", "no_such_node") + "}")
                .isError(), "an unknown node type");
        assertTrue(parse("{\"name\": \"Lamp\", \"graph\": " + GRAPH.replace("\"version\": 1", "\"version\": 99") + "}")
                .isError(), "a newer graph format");
    }

    @Test
    void langKeysComeFromTheFileId() {
        Identifier id = Identifier.parse("mypack:power/low_alert");
        assertEquals("logic_template.mypack.power.low_alert.name", LogicTemplate.nameKey(id));
        assertEquals("logic_template.mypack.power.low_alert.description", LogicTemplate.descriptionKey(id));
        assertEquals("gui.maxtech_storage.logic.template.category.alerts", LogicTemplate.categoryKey("alerts"));
    }

    /** Every built-in template survives the file codec, fits the limits and compiles without errors. */
    @Test
    void builtInTemplatesAreValid() {
        Map<String, LogicTemplate<String>> all = BuiltInTemplates.all(item -> item);
        assertTrue(all.size() >= 6, "six to eight templates: " + all.size());
        for (Map.Entry<String, LogicTemplate<String>> entry : all.entrySet()) {
            LogicTemplate<String> template = entry.getValue();
            JsonElement json = CODEC.encodeStart(JsonOps.INSTANCE, template).getOrThrow();
            assertEquals(template, CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(), entry.getKey());
            assertTrue(BuiltInTemplates.CATEGORIES.containsKey(template.category()), entry.getKey());
            assertTrue(template.icon().isPresent(), entry.getKey());
            CompileResult<String> result = LogicCompiler.compile(template.graph(), NodeTypes.all().size());
            assertTrue(result.errors().isEmpty(), entry.getKey() + ": " + result.errors());
        }
    }
}
