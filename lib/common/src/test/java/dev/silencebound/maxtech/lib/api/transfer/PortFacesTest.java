package dev.silencebound.maxtech.lib.api.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class PortFacesTest {
    @Test
    void facesPackTwoBitsEachAtTheirDataValue() {
        int packed = PortFaces.with(PortFaces.NONE, Direction.UP, PortFaceMode.PUSH);
        packed = PortFaces.with(packed, Direction.EAST, PortFaceMode.PULL);
        assertEquals((1 << 2) | (2 << 10), packed);
        for (Direction face : Direction.values()) {
            PortFaceMode expected = face == Direction.UP
                    ? PortFaceMode.PUSH
                    : face == Direction.EAST ? PortFaceMode.PULL : PortFaceMode.OFF;
            assertEquals(expected, PortFaces.get(packed, face), face.toString());
        }
        assertEquals(PortFaceMode.OFF,
                PortFaces.get(PortFaces.with(packed, Direction.UP, PortFaceMode.OFF), Direction.UP));
        assertEquals(1 << Direction.UP.get3DDataValue(), PortFaces.facesWith(packed, PortFaceMode.PUSH));
        assertEquals(0b11_1111 & ~((1 << 1) | (1 << 5)), PortFaces.facesWith(packed, PortFaceMode.OFF));
    }

    @Test
    void allSetsEveryFace() {
        int packed = PortFaces.all(PortFaceMode.PULL);
        assertEquals(0b10_1010_1010_10, packed);
        assertEquals(0b11_1111, PortFaces.facesWith(packed, PortFaceMode.PULL));
    }

    @Test
    void sanitiseTurnsOffBadAndDisallowedFaces() {
        int packed = PortFaces.with(PortFaces.all(PortFaceMode.PUSH), Direction.NORTH, PortFaceMode.PULL);
        packed |= 3 << (2 * Direction.DOWN.get3DDataValue()); // an id no mode has
        packed |= 1 << 20; // bits past the six faces
        int sane = PortFaces.sanitise(packed, EnumSet.of(PortFaceMode.OFF, PortFaceMode.PUSH));
        assertEquals(PortFaceMode.OFF, PortFaces.get(sane, Direction.DOWN));
        assertEquals(PortFaceMode.OFF, PortFaces.get(sane, Direction.NORTH));
        assertEquals(PortFaceMode.PUSH, PortFaces.get(sane, Direction.UP));
        assertEquals(0, sane & ~PortFaces.ALL_BITS);
        assertEquals(PortFaces.with(packed & PortFaces.ALL_BITS, Direction.DOWN, PortFaceMode.OFF),
                PortFaces.sanitise(packed, EnumSet.allOf(PortFaceMode.class)));
    }
}
