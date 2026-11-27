package dev.silencebound.maxtech.lib.content;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ConduitAssetsTest {
    /**
     * The block state file has one variant for every state naming Lib's conduit model, with the look's fields beside
     * the type as datagen writes them for Storage's cables; the model reads the look back unchanged.
     */
    @Test
    void blockStateNamesTheConduitModelWithTheLook() {
        ConduitLook look = ConduitLook.solid(Identifier.fromNamespaceAndPath("test", "end"), null, 0x353529,
                ConduitLook.TYPE_COLOUR);
        JsonObject model = ConduitAssets.blockState(look).getAsJsonObject("variants").getAsJsonObject("");
        assertEquals(ConduitAssets.MODEL_TYPE, model.get("type").getAsString());
        model.remove("type");
        assertEquals(look, ConduitLook.CODEC.codec().parse(JsonOps.INSTANCE, model).getOrThrow());
    }

    /** Pins the whole block state file as {@code docs/api/conduit-addon.md} shows it. */
    @Test
    void blockStateMatchesTheDocumentedFile() {
        ConduitLook look = ConduitLook.solid(Identifier.fromNamespaceAndPath("farms", "block/lab_tube_end"),
                Identifier.fromNamespaceAndPath("farms", "block/lab_tube_core"), 0xD0D8DC, 0xFF6400);
        assertEquals(JsonParser.parseString("""
                {"variants": {"": {"type": "maxtech_lib:conduit", "look": "solid", "end": "farms:block/lab_tube_end",
                 "overlay": "farms:block/lab_tube_core", "colour": "#d0d8dc", "overlay_colour": "#ff6400"}}}
                """), ConduitAssets.blockState(look));
    }

    /** Pins a shell conduit's block state file as {@code docs/api/conduit-addon.md} shows it. */
    @Test
    void shellBlockStateMatchesTheDocumentedFile() {
        ConduitLook look = ConduitLook.shell(Identifier.fromNamespaceAndPath("farms", "block/lab_tube_glass"))
                .withBorderColour(0x2E4A52);
        assertEquals(JsonParser.parseString("""
                {"variants": {"": {"type": "maxtech_lib:conduit", "look": "shell",
                 "shell": "farms:block/lab_tube_glass", "border_colour": "#2e4a52"}}}
                """), ConduitAssets.blockState(look));
        JsonObject model = ConduitAssets.blockState(look).getAsJsonObject("variants").getAsJsonObject("");
        model.remove("type");
        assertEquals(look, ConduitLook.CODEC.codec().parse(JsonOps.INSTANCE, model).getOrThrow());
    }

    @Test
    void itemDefinitionNamesTheItemModel() {
        JsonObject model = ConduitAssets.itemDefinition(Identifier.fromNamespaceAndPath("test", "tube"))
                .getAsJsonObject("model");
        assertEquals("minecraft:model", model.get("type").getAsString());
        assertEquals("test:item/tube", model.get("model").getAsString());
    }
}
