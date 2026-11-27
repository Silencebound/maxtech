package dev.silencebound.maxtech.lib.api.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import org.junit.jupiter.api.Test;

class ConduitConnectionsTest {
    private static final ConduitType ENERGY_LV = type("energy", "lv");
    private static final ConduitType ENERGY_MV = type("energy", "mv");
    private static final ConduitType ITEM = type("item", "basic");

    @Test
    void uncolouredConnectsToAnything() {
        assertTrue(ConduitConnections.canConnect(ENERGY_LV, null, ENERGY_LV, null));
        assertTrue(ConduitConnections.canConnect(ENERGY_LV, DyeColor.RED, ENERGY_LV, null));
        assertTrue(ConduitConnections.canConnect(ENERGY_LV, null, ENERGY_LV, DyeColor.RED));
    }

    @Test
    void colouredConnectsOnlyToTheSameColour() {
        for (DyeColor a : DyeColor.values()) {
            for (DyeColor b : DyeColor.values()) {
                assertEquals(a == b, ConduitConnections.canConnect(ENERGY_LV, a, ENERGY_LV, b), a + "/" + b);
            }
        }
    }

    @Test
    void tiersOfOneNetworkTypeConnect() {
        assertTrue(ConduitConnections.canConnect(ENERGY_LV, null, ENERGY_MV, null));
    }

    @Test
    void differentNetworkTypesNeverConnect() {
        assertFalse(ConduitConnections.canConnect(ENERGY_LV, null, ITEM, null));
        assertFalse(ConduitConnections.canConnect(ENERGY_LV, DyeColor.RED, ITEM, DyeColor.RED));
    }

    @Test
    void maskBitsAreDistinctPerSide() {
        int all = 0;
        for (Direction side : Direction.values()) {
            int bit = ConduitConnections.bit(side);
            assertEquals(0, all & bit, side.getName());
            all |= bit;
            assertTrue(ConduitConnections.isConnected(bit, side));
        }
        assertEquals(ConduitConnections.ALL_SIDES, all);
    }

    private static ConduitType type(String network, String tier) {
        return new ConduitType(Identifier.fromNamespaceAndPath("test", network), tier, 0xFFFFFF,
                ConduitConnectionsTest::noItem);
    }

    // Items need a bootstrapped registry; the connection rule never asks for one.
    private static ItemLike noItem() {
        throw new UnsupportedOperationException();
    }
}
