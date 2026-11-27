package dev.silencebound.maxtech.lib.content;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
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

    @Test
    void itemDefinitionNamesTheItemModel() {
        JsonObject model = ConduitAssets.itemDefinition(Identifier.fromNamespaceAndPath("test", "tube"))
                .getAsJsonObject("model");
        assertEquals("minecraft:model", model.get("type").getAsString());
        assertEquals("test:item/tube", model.get("model").getAsString());
    }
}
