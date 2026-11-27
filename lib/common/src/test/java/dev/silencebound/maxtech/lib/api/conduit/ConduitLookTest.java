package dev.silencebound.maxtech.lib.api.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** A conduit block without a look of its own is drawn flat in its type's colour. */
    @Test
    void defaultIsAFlatWire() {
        assertEquals(ConduitLook.wire(), ConduitLook.DEFAULT);
    }
}
