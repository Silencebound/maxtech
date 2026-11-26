package dev.silencebound.maxtech.lib.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;

import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import org.junit.jupiter.api.Test;

/**
 * The conduit model's geometry key with a facade. Facade block states need a bootstrapped registry, so the facade's
 * presence is the {@code covered} flag and its geometry an opaque key, as the model passes them.
 */
class FacadeViewTest {
    private static final ConduitType CABLE = new ConduitType(Identifier.fromNamespaceAndPath("test", "cable"), "basic",
            0x336699, FacadeViewTest::noItem);
    private static final ConduitRenderData RED = data(DyeColor.RED);
    private static final ConduitRenderData BLUE = data(DyeColor.BLUE);
    private static final Object STONE = "stone geometry";
    private static final Object BRICKS = "bricks geometry";

    @Test
    void uncoveredConduitKeysOnItsRenderData() {
        assertSame(RED, FacadeView.geometryKey(RED, false, false, null));
        assertSame(RED, FacadeView.geometryKey(RED, false, true, null), "the toggle only matters under a facade");
    }

    /** Hidden conduits add nothing to the geometry, so conduits under the same facade share a key. */
    @Test
    void coveredConduitKeysOnTheFacadeOnly() {
        Object red = FacadeView.geometryKey(RED, true, false, STONE);
        assertEquals(red, FacadeView.geometryKey(BLUE, true, false, STONE));
        assertNotEquals(red, FacadeView.geometryKey(RED, true, false, BRICKS), "another facade");
        assertNotEquals(red, FacadeView.geometryKey(RED, false, false, null), "the same conduit uncovered");
        assertNotEquals(STONE, red, "the facade block's own key");
    }

    /** With the conduits shown through the facade, the key is the conduits', as if there were no facade. */
    @Test
    void conduitsShownThroughTheFacadeKeyOnTheConduits() {
        Object shown = FacadeView.geometryKey(RED, true, true, null);
        assertEquals(RED, shown);
        assertNotEquals(shown, FacadeView.geometryKey(BLUE, true, true, null), "different conduits");
        assertNotEquals(shown, FacadeView.geometryKey(RED, true, false, STONE), "the toggle");
    }

    @Test
    void facadeModelWithoutKeyGivesNone() {
        assertNull(FacadeView.geometryKey(RED, true, false, null));
    }

    private static ConduitRenderData data(DyeColor colour) {
        return new ConduitRenderData(CABLE, colour, 0b000011, 0, List.of(), null);
    }

    // Items need a bootstrapped registry; rendering data never asks for one.
    private static ItemLike noItem() {
        throw new UnsupportedOperationException();
    }
}
