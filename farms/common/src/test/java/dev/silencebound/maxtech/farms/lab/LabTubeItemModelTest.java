package dev.silencebound.maxtech.farms.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The lab tube's generated item model is a straight piece of tube as the world draws it (Lib's
 * {@code ConduitItemModels.shell}), not a flat item: glass panes, gunmetal frame bars and near-black tier stripes.
 */
class LabTubeItemModelTest {
    @Test
    void itemModelIsAPieceOfTube() throws IOException {
        JsonObject model = read("/assets/maxtech_farms/models/item/lab_tube.json");
        assertEquals("minecraft:block/block", model.get("parent").getAsString());
        JsonObject textures = model.getAsJsonObject("textures");
        assertEquals("maxtech_farms:block/lab_tube_glass", textures.get("glass").getAsString());
        assertEquals("maxtech_lib:block/conduit/flat", textures.get("flat").getAsString());
        assertNotNull(model.getAsJsonObject("display").get("gui"), "a GUI transform");
        Map<String, Integer> counts = new HashMap<>();
        for (JsonElement element : model.getAsJsonArray("elements")) {
            JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
            assertEquals(1, faces.size(), "one face per element");
            JsonObject face = faces.entrySet().iterator().next().getValue().getAsJsonObject();
            JsonObject data = face.getAsJsonObject("neoforge_data");
            String colour = data == null ? "none" : data.get("color").getAsString();
            counts.merge(face.get("texture").getAsString() + " " + colour, 1, Integer::sum);
        }
        // 6 panes; 4 long bars of 6 faces and 4 end bars of 4 faces at each end; 4 stripes on each of the 6 panes.
        assertEquals(Map.of("#glass none", 6, "#flat ff2c3238", 56, "#flat ff16181a", 24), counts);
        assertEquals(86, model.getAsJsonArray("elements").size());
    }

    private static JsonObject read(String path) throws IOException {
        try (InputStream in = LabTubeItemModelTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
