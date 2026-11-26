package dev.silencebound.maxtech.lib.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ConduitRenderDataTest {
    private static final ConduitType A = type("a", 0x336699);
    private static final ConduitType B = type("b", 0xCC8800);

    /** The model's part cache and geometry key rely on value equality: same look, same key. */
    @Test
    void equalConduitsGiveEqualKeys() {
        ConduitRenderData first = of(conduit(DyeColor.RED, 0b000011));
        ConduitRenderData second = of(conduit(DyeColor.RED, 0b000011));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void everyVisibleChangeChangesTheKey() {
        ConduitRenderData base = of(conduit(DyeColor.RED, 0b000011));
        assertNotEquals(base, of(conduit(DyeColor.BLUE, 0b000011)), "colour");
        assertNotEquals(base, of(conduit(null, 0b000011)), "uncoloured");
        assertNotEquals(base, of(conduit(DyeColor.RED, 0b000111)), "connections");
        assertNotEquals(base, of(new ConduitData(B, DyeColor.RED, 0b000011)), "type");
    }

    /** Block faces draw an arm with a ring coloured by the face's mode; links draw an arm without one. */
    @Test
    void blockFacesGetArmsAndRings() {
        int west = ConduitConnections.bit(Direction.WEST);
        int east = ConduitConnections.bit(Direction.EAST);
        int modes = ConduitFaceModes.with(ConduitFaceModes.all(ConduitFaceMode.BOTH), Direction.EAST,
                ConduitFaceMode.INSERT);
        ConduitRenderData data = of(new ConduitData(A, null, west, west, east, modes));
        assertEquals(west | east, data.arms());
        assertEquals(east, data.rings());
        assertEquals(List.of(new ConduitFaceKey(A, Direction.EAST, ConduitFaceMode.INSERT)), data.faces());
    }

    /**
     * A disabled face towards a block, or towards a conduit it could link with, draws a stub and no arm; a disabled
     * face towards nothing draws nothing.
     */
    @Test
    void disabledFacesDrawStubs() {
        int west = ConduitConnections.bit(Direction.WEST);
        int east = ConduitConnections.bit(Direction.EAST);
        int modes = ConduitFaceModes.all(ConduitFaceMode.DISABLED);
        // The link to the west is cut, so it is a conduit face but no connection.
        ConduitData conduit = new ConduitData(A, null, 0, west, east, modes);
        ConduitRenderData data = of(conduit);
        assertEquals(0, data.arms());
        assertEquals(List.of(new ConduitFaceKey(A, Direction.WEST, ConduitFaceMode.DISABLED),
                new ConduitFaceKey(A, Direction.EAST, ConduitFaceMode.DISABLED)), data.faces());
        assertEquals(west | east, conduit.stubMask());
        assertEquals(east, conduit.renderMask());
    }

    @Test
    void modeChangesTheFaceKeyButNotTheBody() {
        int east = ConduitConnections.bit(Direction.EAST);
        ConduitRenderData both = of(new ConduitData(A, null, 0, 0, east, ConduitFaceModes.all(ConduitFaceMode.BOTH)));
        ConduitRenderData extract = of(
                new ConduitData(A, null, 0, 0, east, ConduitFaceModes.all(ConduitFaceMode.EXTRACT)));
        assertNotEquals(both, extract);
        assertEquals(both.arms(), extract.arms(), "the body does not depend on the mode");
        assertEquals(both.rings(), extract.rings(), "the body does not depend on the mode");
    }

    @Test
    void withoutFacadeDropsOnlyTheFacade() {
        ConduitRenderData plain = of(conduit(DyeColor.RED, 0b000011));
        assertSame(plain, plain.withoutFacade(), "nothing to drop");
    }

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

    private static ConduitRenderData of(ConduitData conduit) {
        return ConduitRenderData.of(conduit, null);
    }

    private static ConduitData conduit(@Nullable DyeColor colour, int connections) {
        return new ConduitData(A, colour, connections);
    }

    private static ConduitType type(String network, int colour) {
        return new ConduitType(Identifier.fromNamespaceAndPath("test", network), "basic", colour,
                ConduitRenderDataTest::noItem);
    }

    // Items need a bootstrapped registry; rendering data never asks for one.
    private static ItemLike noItem() {
        throw new UnsupportedOperationException();
    }
}
