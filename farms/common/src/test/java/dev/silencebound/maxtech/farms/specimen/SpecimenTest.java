package dev.silencebound.maxtech.farms.specimen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** The specimen component's codecs round-trip, a fresh sample saves lean, and values stay within their bounds. */
class SpecimenTest {
    private static final Identifier ZOMBIE = Identifier.withDefaultNamespace("zombie");
    private static final Specimen BRED = new Specimen(SpecimenKind.MOB, ZOMBIE, 7, 10, 3, 1,
            EnumSet.of(SpecimenTrait.HARDY, SpecimenTrait.LUCKY));

    @Test
    void codecRoundTrips() {
        for (Specimen specimen : List.of(Specimen.fresh(SpecimenKind.MOB, ZOMBIE), BRED)) {
            JsonElement json = Specimen.CODEC.encodeStart(JsonOps.INSTANCE, specimen).getOrThrow();
            assertEquals(specimen, Specimen.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        }
    }

    @Test
    void streamCodecRoundTrips() {
        for (Specimen specimen : List.of(Specimen.fresh(SpecimenKind.MOB, ZOMBIE), BRED)) {
            ByteBuf buf = Unpooled.buffer();
            Specimen.STREAM_CODEC.encode(buf, specimen);
            assertEquals(specimen, Specimen.STREAM_CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
        }
    }

    @Test
    void freshSampleIsGenerationOneWithoutTraits() {
        Specimen fresh = Specimen.fresh(SpecimenKind.MOB, ZOMBIE);
        assertEquals(new Specimen(SpecimenKind.MOB, ZOMBIE, 1, 1, 1, 1, Set.of()), fresh);
        JsonElement json = Specimen.CODEC.encodeStart(JsonOps.INSTANCE, fresh).getOrThrow();
        assertEquals(JsonParser.parseString("{\"kind\":\"mob\",\"target\":\"minecraft:zombie\"}"), json);
    }

    @Test
    void traitOrderDoesNotMatter() {
        Specimen other = new Specimen(SpecimenKind.MOB, ZOMBIE, 7, 10, 3, 1,
                Set.of(SpecimenTrait.LUCKY, SpecimenTrait.HARDY));
        assertEquals(BRED, other);
        assertEquals(BRED.hashCode(), other.hashCode());
    }

    @Test
    void constructorRejectsValuesOutOfBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new Specimen(SpecimenKind.MOB, ZOMBIE, 0, 1, 1, 1, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Specimen(SpecimenKind.MOB, ZOMBIE, 11, 1, 1, 1, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Specimen(SpecimenKind.MOB, ZOMBIE, 1, 11, 1, 1, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Specimen(SpecimenKind.MOB, ZOMBIE, 1, 1, 0, 1, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Specimen(SpecimenKind.MOB, ZOMBIE, 1, 1, 1, 11, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Specimen(SpecimenKind.MOB, ZOMBIE, 1, 1, 1, 1, EnumSet.allOf(SpecimenTrait.class)));
        new Specimen(SpecimenKind.MOB, ZOMBIE, 10, 10, 10, 10, EnumSet.of(SpecimenTrait.FRUGAL, SpecimenTrait.HARDY));
    }

    @Test
    void codecReportsValuesOutOfBoundsAsErrors() {
        for (String bad : List.of("{\"kind\":\"mob\",\"target\":\"minecraft:zombie\",\"growth\":11}",
                "{\"kind\":\"mob\",\"target\":\"minecraft:zombie\",\"generation\":0}",
                "{\"kind\":\"mob\",\"target\":\"minecraft:zombie\",\"traits\":[\"lucky\",\"frugal\",\"hardy\"]}",
                "{\"kind\":\"plant\",\"target\":\"minecraft:zombie\"}")) {
            assertTrue(Specimen.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(bad)).isError(), bad);
        }
        assertFalse(Specimen.CODEC
                .parse(JsonOps.INSTANCE,
                        JsonParser.parseString(
                                "{\"kind\":\"mob\",\"target\":\"minecraft:zombie\",\"traits\":[\"lucky\"]}"))
                .isError());
    }
}
