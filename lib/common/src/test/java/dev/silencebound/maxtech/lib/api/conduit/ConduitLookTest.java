package dev.silencebound.maxtech.lib.api.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ConduitLookTest {
    /** Datagen writes looks into block state files and the client reads them back unchanged. */
    @Test
    void looksRoundTripThroughJson() {
        List<ConduitLook> looks = List.of(ConduitLook.wire(),
                ConduitLook.solid(Identifier.fromNamespaceAndPath("test", "end"),
                        Identifier.fromNamespaceAndPath("test", "core"), 0x353529, 0xFF6400),
                ConduitLook.solid(Identifier.fromNamespaceAndPath("test", "end"), null, ConduitLook.TYPE_COLOUR,
                        ConduitLook.TYPE_COLOUR));
        for (ConduitLook look : looks) {
            JsonElement json = ConduitLook.CODEC.codec().encodeStart(JsonOps.INSTANCE, look).getOrThrow();
            assertEquals(look, ConduitLook.CODEC.codec().parse(JsonOps.INSTANCE, json).getOrThrow(), json.toString());
        }
    }

    /** The block state file stays readable: colours as hex, and the type's colour as an absent field. */
    @Test
    void looksWriteReadableJson() {
        JsonObject wire = ConduitLook.CODEC.codec().encodeStart(JsonOps.INSTANCE, ConduitLook.wire()).getOrThrow()
                .getAsJsonObject();
        assertEquals("solid", wire.get("look").getAsString());
        assertTrue(!wire.has("colour"), "the type's colour is left out");
        JsonObject cable = ConduitLook.CODEC.codec().encodeStart(JsonOps.INSTANCE, ConduitLook
                .solid(Identifier.fromNamespaceAndPath("test", "end"), null, 0x353529, ConduitLook.TYPE_COLOUR))
                .getOrThrow().getAsJsonObject();
        assertEquals("#353529", cable.get("colour").getAsString());
        ConduitLook parsed = ConduitLook.CODEC.codec()
                .parse(JsonOps.INSTANCE,
                        JsonParser.parseString("{\"look\": \"solid\", \"end\": \"test:end\", \"colour\": \"#FFFFFF\"}"))
                .getOrThrow();
        assertEquals(ConduitLook.solid(Identifier.fromNamespaceAndPath("test", "end"), null, 0xFFFFFF,
                ConduitLook.TYPE_COLOUR), parsed, "white is a colour, not the type's");
    }

    /**
     * Pins the whole format documented in {@code docs/api/conduit-addon.md}: field names, the optional overlay and the
     * hex colours. Addons that ship their own block state file rely on it.
     */
    @Test
    void lookJsonMatchesTheDocumentedFormat() {
        JsonElement full = ConduitLook.CODEC.codec()
                .encodeStart(JsonOps.INSTANCE,
                        ConduitLook.solid(Identifier.fromNamespaceAndPath("farms", "block/lab_tube_end"),
                                Identifier.fromNamespaceAndPath("farms", "block/lab_tube_core"), 0xD0D8DC, 0xFF6400))
                .getOrThrow();
        assertEquals(JsonParser.parseString("""
                {"look": "solid", "end": "farms:block/lab_tube_end", "overlay": "farms:block/lab_tube_core",
                 "colour": "#d0d8dc", "overlay_colour": "#ff6400"}
                """), full);
        JsonElement wire = ConduitLook.CODEC.codec().encodeStart(JsonOps.INSTANCE, ConduitLook.wire()).getOrThrow();
        assertEquals(JsonParser.parseString("{\"look\": \"solid\", \"end\": \"maxtech_lib:block/conduit/flat\"}"),
                wire);
    }

    /**
     * Pins the shell format documented in {@code docs/api/conduit-addon.md}: only the sprite is required, and each
     * field at its default is left out, so later optional fields keep these files valid.
     */
    @Test
    void shellJsonMatchesTheDocumentedFormat() {
        Identifier glass = Identifier.fromNamespaceAndPath("farms", "block/lab_tube_glass");
        JsonElement plain = ConduitLook.CODEC.codec().encodeStart(JsonOps.INSTANCE, ConduitLook.shell(glass))
                .getOrThrow();
        assertEquals(JsonParser.parseString("{\"look\": \"shell\", \"shell\": \"farms:block/lab_tube_glass\"}"), plain);
        ConduitLook.Shell full = ConduitLook.shell(glass).withBorderColour(0x2E4A52).withBorder(1).withRim(2);
        JsonElement json = ConduitLook.CODEC.codec().encodeStart(JsonOps.INSTANCE, full).getOrThrow();
        assertEquals(JsonParser.parseString("""
                {"look": "shell", "shell": "farms:block/lab_tube_glass", "border_colour": "#2e4a52", "border": 1.0,
                 "rim": 2.0}
                """), json);
        assertEquals(full, ConduitLook.CODEC.codec().parse(JsonOps.INSTANCE, json).getOrThrow());
        assertEquals(ConduitLook.shell(glass), ConduitLook.CODEC.codec().parse(JsonOps.INSTANCE, plain).getOrThrow());
        ConduitLook.Shell defaults = ConduitLook.shell(glass);
        assertEquals(ConduitLook.TYPE_COLOUR, defaults.borderColour());
        assertEquals(0.5F, defaults.border());
        assertEquals(1.5F, defaults.rim());
        assertEquals(0.5F, defaults.inset());
        assertEquals(1.0F, defaults.laneWidth());
    }

    /** The contents fields are optional: written only when changed, read back, and absent means the defaults. */
    @Test
    void shellContentsFieldsAreOptional() {
        ConduitLook.Shell shell = ConduitLook.shell(Identifier.fromNamespaceAndPath("farms", "block/lab_tube_glass"))
                .withInset(1).withLaneWidth(0.75F);
        JsonElement json = ConduitLook.CODEC.codec().encodeStart(JsonOps.INSTANCE, shell).getOrThrow();
        assertEquals(JsonParser.parseString("""
                {"look": "shell", "shell": "farms:block/lab_tube_glass", "inset": 1.0, "lane_width": 0.75}
                """), json);
        assertEquals(shell, ConduitLook.CODEC.codec().parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    /** The junction cage is off by default, written only when on, and read back. */
    @Test
    void shellJunctionCageIsOptional() {
        Identifier glass = Identifier.fromNamespaceAndPath("farms", "block/lab_tube_glass");
        assertTrue(!ConduitLook.shell(glass).junctionCage(), "off by default");
        ConduitLook.Shell caged = ConduitLook.shell(glass).withJunctionCage().withBorder(1);
        assertTrue(caged.junctionCage(), "kept by later withers");
        JsonElement json = ConduitLook.CODEC.codec().encodeStart(JsonOps.INSTANCE, caged).getOrThrow();
        assertEquals(JsonParser.parseString("""
                {"look": "shell", "shell": "farms:block/lab_tube_glass", "border": 1.0, "junction_cage": true}
                """), json);
        assertEquals(caged, ConduitLook.CODEC.codec().parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    /** A shell without its sprite, or with a width out of range, is not read. */
    @Test
    void badShellsAreRejected() {
        for (String bad : List.of("{\"look\": \"shell\"}", "{\"look\": \"shell\", \"shell\": \"a:b\", \"border\": -1}",
                "{\"look\": \"shell\", \"shell\": \"a:b\", \"rim\": 5}",
                "{\"look\": \"shell\", \"shell\": \"a:b\", \"inset\": -0.5}",
                "{\"look\": \"shell\", \"shell\": \"a:b\", \"lane_width\": 4.5}")) {
            assertTrue(ConduitLook.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(bad)).isError(), bad);
        }
        assertThrows(IllegalArgumentException.class,
                () -> ConduitLook.shell(Identifier.fromNamespaceAndPath("a", "b")).withRim(Float.NaN));
    }

    /** A conduit block without a look of its own is drawn flat in its type's colour. */
    @Test
    void defaultIsAFlatWire() {
        assertEquals(ConduitLook.wire(), ConduitLook.DEFAULT);
    }
}
