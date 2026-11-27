package dev.silencebound.maxtech.lib.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceModes;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
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

    /** A new render value is a visible change: the block entity re-meshes and the body gets its own part. */
    @Test
    void renderValueChangesTheKeyAndTheBody() {
        ConduitData conduit = conduit(DyeColor.RED, 0b000011);
        ConduitRenderData plain = of(conduit);
        ConduitRenderData set = ConduitRenderData.of(conduit, 7, null);
        assertEquals(0, plain.renderValue());
        assertEquals(plain, ConduitRenderData.of(conduit, 0, null), "0 is the default");
        assertEquals(set, ConduitRenderData.of(conduit, 7, null));
        assertEquals(set.hashCode(), ConduitRenderData.of(conduit, 7, null).hashCode());
        assertNotEquals(plain, set);
        assertNotEquals(set, ConduitRenderData.of(conduit, 8, null));
        assertEquals(7, set.bodyKey().renderValue());
        assertNotEquals(plain.bodyKey(), set.bodyKey());
        assertEquals(plain.faces(), set.faces(), "the faces do not depend on the value");
    }

    @Test
    void shortConstructorsDefaultTheRenderValueToZero() {
        ConduitRenderData data = new ConduitRenderData(A, null, 0b000011, 0, List.of(), null);
        assertEquals(0, data.renderValue());
        assertEquals(new ConduitBodyKey(A, null, 0b000011, 0, 0), new ConduitBodyKey(A, null, 0b000011, 0));
        assertEquals(0, new ConduitBodyKey(A, null, 0b000011, 0).renderValue());
        assertEquals(new ConduitBodyKey(A, null, 0b000011, 0), data.bodyKey());
    }

    @Test
    void withoutFacadeDropsOnlyTheFacade() {
        ConduitRenderData plain = of(conduit(DyeColor.RED, 0b000011));
        assertSame(plain, plain.withoutFacade(), "nothing to drop");
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
