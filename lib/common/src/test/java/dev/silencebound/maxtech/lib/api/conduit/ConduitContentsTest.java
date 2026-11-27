package dev.silencebound.maxtech.lib.api.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** Validation of {@link ConduitContents} and its packed layout, pinned bit by bit. */
class ConduitContentsTest {
    @Test
    void emptyIsEmptyAndPacksToZero() {
        assertTrue(ConduitContents.EMPTY.isEmpty());
        assertEquals(0, ConduitContents.EMPTY.packed());
        assertEquals(0, ConduitContents.pack(-1, 0, null, false));
        assertFalse(new ConduitContents(Optional.empty(), 0, Optional.of(Direction.UP), false).isEmpty());
        assertFalse(new ConduitContents(Optional.empty(), 0, Optional.empty(), true).isEmpty());
    }

    /** Fluids can't load in JUnit; the fluid checks run in the {@code conduit_api_contents_*} GameTests. */
    @Test
    void rejectsInvalidContents() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConduitContents(Optional.empty(), -1, Optional.empty(), false));
        assertThrows(IllegalArgumentException.class,
                () -> new ConduitContents(Optional.empty(), 5, Optional.empty(), false));
        assertThrows(IllegalArgumentException.class,
                () -> new ConduitContents(Optional.empty(), 2, Optional.empty(), false), "fill without a fluid");
    }

    @Test
    void layoutIsPinned() {
        assertEquals(0x0000_0001, ConduitContents.pack(0, 0, null, false), "fluid id + 1 in bits 0-15");
        assertEquals(0x0000_FFFF, ConduitContents.pack(0xFFFE, 0, null, false));
        assertEquals(0x0004_0000, ConduitContents.pack(-1, 4, null, false), "fill in bits 16-18");
        assertEquals(0x0008_0000, ConduitContents.pack(-1, 0, Direction.DOWN, false), "upstream in bits 19-21");
        assertEquals(0x0030_0000, ConduitContents.pack(-1, 0, Direction.EAST, false), "EAST is 3D value 5");
        assertEquals(0x0040_0000, ConduitContents.pack(-1, 0, null, true), "lane in bit 22");
        assertEquals(0x0062_0008, ConduitContents.pack(7, 2, Direction.SOUTH, true));
        assertThrows(IllegalArgumentException.class, () -> ConduitContents.pack(0xFFFF, 0, null, false));
        assertThrows(IllegalArgumentException.class, () -> ConduitContents.pack(-2, 0, null, false));
        assertThrows(IllegalArgumentException.class, () -> ConduitContents.pack(0, 5, null, false));
    }

    @Test
    void oversizedFluidIdPacksAsNoFluid() {
        assertEquals(ConduitContents.pack(-1, 0, Direction.UP, true),
                ConduitContents.packClamped(0xFFFF, 0, Direction.UP, true));
        assertEquals(ConduitContents.pack(-1, 0, null, false), ConduitContents.packClamped(1_000_000, 0, null, false));
        assertEquals(ConduitContents.pack(0xFFFE, 2, null, false), ConduitContents.packClamped(0xFFFE, 2, null, false));
    }

    @Test
    void packedValuesRoundTrip() {
        Direction[] sides = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST,
                Direction.EAST};
        for (int fluid : new int[]{-1, 0, 1, 300, 0xFFFE}) {
            for (int fill = 0; fill <= ConduitContents.FULL; fill++) {
                for (Direction side : sides) {
                    for (boolean lane : new boolean[]{false, true}) {
                        int packed = ConduitContents.pack(fluid, fill, side, lane);
                        assertEquals(0, packed >>> 23, "reserved bits");
                        assertEquals(fluid, ConduitContents.packedFluidId(packed));
                        assertEquals(fill, ConduitContents.packedFill(packed));
                        assertEquals(Optional.ofNullable(side), ConduitContents.packedUpstream(packed));
                        assertEquals(lane, ConduitContents.packedLane(packed));
                    }
                }
            }
        }
        assertSame(ConduitContents.packedUpstream(0x0008_0000), ConduitContents.packedUpstream(0x0008_0001),
                "shared, not allocated");
    }
}
