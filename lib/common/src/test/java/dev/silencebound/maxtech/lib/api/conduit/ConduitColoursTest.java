package dev.silencebound.maxtech.lib.api.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import org.junit.jupiter.api.Test;

class ConduitColoursTest {
    private static final ConduitType A = type("a", 0x336699);
    private static final ConduitType B = type("b", 0xCC8800);

    @Test
    void faceColoursFollowTheMode() {
        Set<Integer> colours = new HashSet<>();
        for (ConduitFaceMode mode : ConduitFaceMode.values()) {
            assertEquals(0xFF, ARGB.alpha(ConduitColours.faceArgb(mode)), mode.name());
            colours.add(ConduitColours.faceArgb(mode));
        }
        assertEquals(ConduitFaceMode.values().length, colours.size(), "every mode has its own colour");
    }

    @Test
    void uncolouredUsesTheBaseColour() {
        assertEquals(0xFF336699, ConduitColours.argb(A, null));
    }

    @Test
    void dyeTintsTowardsTheDyeAndKeepsTypesApart() {
        int red = DyeColor.RED.getTextureDiffuseColor();
        int aRed = ConduitColours.argb(A, DyeColor.RED);
        assertEquals(0xFF, ARGB.alpha(aRed));
        assertEquals(ARGB.srgbLerp(ConduitColours.DYE_WEIGHT, 0xFF336699, red), aRed);
        assertNotEquals(aRed, ConduitColours.argb(B, DyeColor.RED));
        assertNotEquals(aRed, ConduitColours.argb(A, DyeColor.BLUE));
    }

    private static ConduitType type(String network, int colour) {
        return new ConduitType(Identifier.fromNamespaceAndPath("test", network), "basic", colour,
                ConduitColoursTest::noItem);
    }

    // Items need a bootstrapped registry; colours never ask for one.
    private static ItemLike noItem() {
        throw new UnsupportedOperationException();
    }
}
