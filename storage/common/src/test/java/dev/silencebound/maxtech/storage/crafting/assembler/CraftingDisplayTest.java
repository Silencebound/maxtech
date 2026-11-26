package dev.silencebound.maxtech.storage.crafting.assembler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** The item display's window check (B09 item 29): which camera positions see into a block through its glass. */
class CraftingDisplayTest {
    /**
     * A crafter facing north is seen from in front of its window only, never from behind, the sides, above or below.
     */
    @Test
    void oneWindowIsSeenFromItsSideOnly() {
        int north = CraftingDisplay.bit(Direction.NORTH);
        assertTrue(CraftingDisplay.seenThroughWindow(north, 0, 0, -3), "in front");
        assertTrue(CraftingDisplay.seenThroughWindow(north, 2, 1, -0.6), "in front, off to one side");
        assertFalse(CraftingDisplay.seenThroughWindow(north, 0, 0, 3), "behind");
        assertFalse(CraftingDisplay.seenThroughWindow(north, 3, 0, 0), "beside");
        assertFalse(CraftingDisplay.seenThroughWindow(north, 0, 5, 0), "above");
        assertFalse(CraftingDisplay.seenThroughWindow(north, 0, 0, -0.5), "in the window's plane");
    }

    /** The assembler's four windows are seen from every side but straight above or below. */
    @Test
    void fourWindowsAreSeenFromEverySideButAboveAndBelow() {
        int mask = CraftingDisplay.HORIZONTAL;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            assertTrue(CraftingDisplay.seenThroughWindow(mask, side.getStepX() * 2, 0, side.getStepZ() * 2),
                    side.toString());
        }
        assertFalse(CraftingDisplay.seenThroughWindow(mask, 0.2, 4, -0.3), "above");
        assertFalse(CraftingDisplay.seenThroughWindow(mask, 0, -4, 0), "below");
        assertEquals(4, Integer.bitCount(mask), "four sides");
        assertEquals(0, mask & (CraftingDisplay.bit(Direction.UP) | CraftingDisplay.bit(Direction.DOWN)),
                "no glass on top or bottom");
    }
}
