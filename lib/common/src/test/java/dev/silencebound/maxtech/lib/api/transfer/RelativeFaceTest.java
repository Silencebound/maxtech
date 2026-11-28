package dev.silencebound.maxtech.lib.api.transfer;

import static net.minecraft.core.Direction.DOWN;
import static net.minecraft.core.Direction.EAST;
import static net.minecraft.core.Direction.NORTH;
import static net.minecraft.core.Direction.SOUTH;
import static net.minecraft.core.Direction.UP;
import static net.minecraft.core.Direction.WEST;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * Relative face names for every facing (owner, 2026-11-28): left and right as a player in front of the block sees them,
 * top towards north for blocks facing up or down.
 */
class RelativeFaceTest {
    /** Per facing: front, back, left, right, top, bottom. */
    private static final Map<Direction, Direction[]> EXPECTED = Map.of(NORTH,
            new Direction[]{NORTH, SOUTH, EAST, WEST, UP, DOWN}, SOUTH,
            new Direction[]{SOUTH, NORTH, WEST, EAST, UP, DOWN}, EAST,
            new Direction[]{EAST, WEST, SOUTH, NORTH, UP, DOWN}, WEST,
            new Direction[]{WEST, EAST, NORTH, SOUTH, UP, DOWN}, UP,
            new Direction[]{UP, DOWN, WEST, EAST, NORTH, SOUTH}, DOWN,
            new Direction[]{DOWN, UP, EAST, WEST, NORTH, SOUTH});

    @Test
    void everyFacingMapsAsSeenFromTheFront() {
        RelativeFace[] faces = {RelativeFace.FRONT, RelativeFace.BACK, RelativeFace.LEFT, RelativeFace.RIGHT,
                RelativeFace.TOP, RelativeFace.BOTTOM};
        for (Direction facing : Direction.values()) {
            Direction[] expected = Objects.requireNonNull(EXPECTED.get(facing));
            for (int i = 0; i < faces.length; i++) {
                assertEquals(expected[i], faces[i].toAbsolute(facing), faces[i] + " facing " + facing);
                assertEquals(faces[i], RelativeFace.of(facing, expected[i]), expected[i] + " facing " + facing);
            }
        }
    }
}
