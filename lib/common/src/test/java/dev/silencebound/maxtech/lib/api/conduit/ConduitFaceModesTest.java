package dev.silencebound.maxtech.lib.api.conduit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ItemLike;
import org.junit.jupiter.api.Test;

class ConduitFaceModesTest {
    @Test
    void everySideKeepsItsOwnMode() {
        int packed = ConduitFaceModes.all(ConduitFaceMode.BOTH);
        ConduitFaceMode[] modes = ConduitFaceMode.values();
        for (Direction side : Direction.values()) {
            packed = ConduitFaceModes.with(packed, side, modes[side.get3DDataValue() % modes.length]);
        }
        for (Direction side : Direction.values()) {
            assertEquals(modes[side.get3DDataValue() % modes.length], ConduitFaceModes.get(packed, side), side.name());
        }
        assertEquals(0, packed & ~ConduitFaceModes.ALL_BITS);
    }

    /** Saved data holds ids, so the packing must follow {@link ConduitFaceMode#id()}, not the ordinal. */
    @Test
    void packsStableIds() {
        int packed = ConduitFaceModes.with(0, Direction.NORTH, ConduitFaceMode.DISABLED);
        assertEquals(ConduitFaceMode.DISABLED.id() << (2 * Direction.NORTH.get3DDataValue()), packed);
        assertEquals(0, ConduitFaceModes.all(ConduitFaceMode.BOTH), "BOTH has id 0, so old data reads as BOTH");
    }

    @Test
    void sidesWithFindsTheMode() {
        int packed = ConduitFaceModes.with(ConduitFaceModes.all(ConduitFaceMode.BOTH), Direction.UP,
                ConduitFaceMode.DISABLED);
        packed = ConduitFaceModes.with(packed, Direction.EAST, ConduitFaceMode.DISABLED);
        assertEquals(ConduitConnections.bit(Direction.UP) | ConduitConnections.bit(Direction.EAST),
                ConduitFaceModes.sidesWith(packed, ConduitFaceMode.DISABLED));
        assertEquals(ConduitConnections.ALL_SIDES & ~ConduitFaceModes.sidesWith(packed, ConduitFaceMode.DISABLED),
                ConduitFaceModes.sidesWith(packed, ConduitFaceMode.BOTH));
    }

    @Test
    void sanitiseResetsUnsupportedModes() {
        ConduitType linkOnly = type(EnumSet.of(ConduitFaceMode.BOTH, ConduitFaceMode.DISABLED), ConduitFaceMode.BOTH);
        int packed = ConduitFaceModes.with(ConduitFaceModes.all(ConduitFaceMode.INSERT), Direction.DOWN,
                ConduitFaceMode.DISABLED);
        int clean = ConduitFaceModes.sanitise(linkOnly, packed, 0);
        assertEquals(ConduitFaceMode.DISABLED, ConduitFaceModes.get(clean, Direction.DOWN));
        for (Direction side : Direction.values()) {
            assertTrue(linkOnly.supportedFaceModes().contains(ConduitFaceModes.get(clean, side)), side.name());
        }
    }

    /**
     * Towards a conduit only BOTH and DISABLED apply, whatever the type supports: a type without BOTH keeps a stored
     * BOTH there, and an INSERT there becomes BOTH rather than the type's default.
     */
    @Test
    void sanitiseKeepsLinkModesOnConduitFaces() {
        ConduitType noBoth = type(EnumSet.of(ConduitFaceMode.EXTRACT, ConduitFaceMode.DISABLED),
                ConduitFaceMode.EXTRACT);
        int east = ConduitConnections.bit(Direction.EAST);
        int west = ConduitConnections.bit(Direction.WEST);
        int packed = ConduitFaceModes.with(ConduitFaceModes.all(ConduitFaceMode.BOTH), Direction.WEST,
                ConduitFaceMode.INSERT);
        int clean = ConduitFaceModes.sanitise(noBoth, packed, east | west);
        assertEquals(ConduitFaceMode.BOTH, ConduitFaceModes.get(clean, Direction.EAST), "stored link mode");
        assertEquals(ConduitFaceMode.BOTH, ConduitFaceModes.get(clean, Direction.WEST), "INSERT on a link");
        assertEquals(ConduitFaceMode.EXTRACT, ConduitFaceModes.get(clean, Direction.UP), "block side");

        assertTrue(ConduitFaceModes.allowed(noBoth, ConduitFaceMode.BOTH, true));
        assertTrue(ConduitFaceModes.allowed(noBoth, ConduitFaceMode.DISABLED, true));
        assertFalse(ConduitFaceModes.allowed(noBoth, ConduitFaceMode.EXTRACT, true));
        assertFalse(ConduitFaceModes.allowed(noBoth, ConduitFaceMode.BOTH, false));
    }

    @Test
    void validateNamesTheBrokenType() {
        assertDoesNotThrow(() -> ConduitFaceModes
                .validate(type(EnumSet.allOf(ConduitFaceMode.class), ConduitFaceMode.BOTH), "test:fine"));
        IllegalStateException noDefault = assertThrows(IllegalStateException.class, () -> ConduitFaceModes
                .validate(type(EnumSet.of(ConduitFaceMode.DISABLED), ConduitFaceMode.BOTH), "test:no_default"));
        assertTrue(String.valueOf(noDefault.getMessage()).contains("test:no_default"), noDefault.getMessage());
        IllegalStateException noDisabled = assertThrows(IllegalStateException.class, () -> ConduitFaceModes
                .validate(type(EnumSet.of(ConduitFaceMode.BOTH), ConduitFaceMode.BOTH), "test:no_disabled"));
        assertTrue(String.valueOf(noDisabled.getMessage()).contains("test:no_disabled"), noDisabled.getMessage());
    }

    static ConduitType type(Set<ConduitFaceMode> supported, ConduitFaceMode defaultMode) {
        return new ConduitType(Identifier.fromNamespaceAndPath("test", "modes"), "basic", 0x808080,
                ConduitFaceModesTest::noItem) {
            @Override
            public Set<ConduitFaceMode> supportedFaceModes() {
                return supported;
            }

            @Override
            public ConduitFaceMode defaultFaceMode() {
                return defaultMode;
            }
        };
    }

    private static ItemLike noItem() {
        throw new UnsupportedOperationException();
    }
}
