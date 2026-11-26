package dev.silencebound.maxtech.storage.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * The generated port block models are plain full cubes (owner, 2026-10-17) with the tier's and mode's arrow front on
 * the model's north, which the {@code facing=north} variant leaves unturned, so the front sits on
 * {@link PortBlock#FACING}. The cable parts' item models keep the shape-A steps of {@link PortShapes} (MTX item 4).
 * Reads the committed {@code runData} output.
 */
class PortModelsTest {
    private static final String ASSETS = "/assets/maxtech_storage/";
    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};

    @Test
    void blockModelsArePlainCubes() throws IOException {
        for (PortTier tier : PortTier.values()) {
            for (PortMode mode : PortMode.values()) {
                String name = tier.blockName() + (mode == PortMode.IMPORT ? "" : "_" + mode.getSerializedName());
                JsonObject model = read("models/block/" + name + ".json");
                List<JsonObject> elements = elements(model);
                assertEquals(1, elements.size(), name + " is one box");
                assertBox(new AABB(0, 0, 0, 16, 16, 16), elements.get(0), name);
                JsonObject faces = elements.get(0).getAsJsonObject("faces");
                assertEquals(FACES.length, faces.size(), name);
                for (String face : FACES) {
                    JsonObject f = faces.getAsJsonObject(face);
                    assertEquals(face, f.get("cullface").getAsString(), name + " " + face + " culls");
                    String slot = switch (face) {
                        case "north" -> "#front";
                        case "south" -> "#back";
                        default -> "#side";
                    };
                    assertEquals(slot, f.get("texture").getAsString(), name + " " + face);
                }
                String front = model.getAsJsonObject("textures").get("front").getAsString();
                String suffix = tier == PortTier.TIER_1 ? "" : "_tier_" + tier.number();
                assertTrue(front.endsWith("port/front_" + mode.getSerializedName() + suffix), name + " " + front);
            }
            JsonObject variants = read("blockstates/" + tier.blockName() + ".json").getAsJsonObject("variants");
            for (PortMode mode : PortMode.values()) {
                JsonObject north = variants.getAsJsonObject("facing=north,mode=" + mode.getSerializedName());
                assertFalse(north.has("x") || north.has("y"), tier + " facing north is turned");
            }
        }
    }

    @Test
    void partItemsHaveTheImportSteps() throws IOException {
        for (PortTier tier : PortTier.values()) {
            String name = tier.blockName() + "_part";
            JsonObject model = read("models/item/" + name + ".json");
            List<JsonObject> elements = elements(model);
            List<AABB> steps = PortShapes.partSteps(PortMode.IMPORT);
            assertEquals(steps.size(), elements.size(), name);
            for (int i = 0; i < steps.size(); i++) {
                assertBox(steps.get(i), elements.get(i), name + " step " + i);
                assertEquals(PortShapes.tip(steps.get(i)) ? "#tip" : "#step", texture(elements.get(i)), name);
            }
        }
    }

    @Test
    void exporterPointsAndImporterWidens() {
        List<AABB> export = PortShapes.partSteps(PortMode.EXPORT);
        assertTrue(PortShapes.tip(export.get(0)));
        assertEquals(0, export.get(0).minZ);
        assertTrue(export.get(0).getXsize() < export.get(1).getXsize());
        List<AABB> steps = PortShapes.partSteps(PortMode.IMPORT);
        assertTrue(PortShapes.tip(steps.get(steps.size() - 1)));
        assertTrue(steps.get(0).getXsize() > steps.get(1).getXsize());
    }

    /** The storage bus's part is a spool: the cyan tip between two equal plates, its own faces hidden by them. */
    @Test
    void storageBusIsASpool() {
        List<AABB> steps = PortShapes.partSteps(PortMode.STORAGE);
        assertEquals(3, steps.size());
        assertTrue(PortShapes.tip(steps.get(1)));
        assertEquals(steps.get(0).getXsize(), steps.get(2).getXsize());
        assertTrue(PortShapes.hidden(steps, 1, true) && PortShapes.hidden(steps, 1, false));
        for (int i = 0; i < steps.size(); i++) {
            assertEquals(i * PortShapes.STEP_DEPTH, steps.get(i).minZ, "step " + i);
        }
    }

    private static void assertBox(AABB expected, JsonObject element, String what) {
        JsonArray from = element.getAsJsonArray("from");
        JsonArray to = element.getAsJsonArray("to");
        AABB actual = new AABB(from.get(0).getAsDouble(), from.get(1).getAsDouble(), from.get(2).getAsDouble(),
                to.get(0).getAsDouble(), to.get(1).getAsDouble(), to.get(2).getAsDouble());
        assertEquals(expected, actual, what);
    }

    /** The texture every face of {@code element} names (a step uses one). */
    private static String texture(JsonObject element) {
        String texture = "";
        for (var face : element.getAsJsonObject("faces").entrySet()) {
            String name = face.getValue().getAsJsonObject().get("texture").getAsString();
            assertTrue(texture.isEmpty() || texture.equals(name), "faces with " + texture + " and " + name);
            texture = name;
        }
        return texture;
    }

    private static List<JsonObject> elements(JsonObject model) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement element : model.getAsJsonArray("elements")) {
            out.add(element.getAsJsonObject());
        }
        return out;
    }

    private static JsonObject read(String path) throws IOException {
        try (InputStream in = PortModelsTest.class.getResourceAsStream(ASSETS + path)) {
            if (in == null) {
                throw new IOException("not on the classpath: " + ASSETS + path);
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
