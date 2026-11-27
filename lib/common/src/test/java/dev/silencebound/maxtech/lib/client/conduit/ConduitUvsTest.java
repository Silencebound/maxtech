package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import java.util.OptionalInt;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ConduitUvsTest {
    private static final AABB BOX = new AABB(2, 3, 4, 10, 12, 14);

    /** Unturned, each face shows the pixels at its own position in the block, as vanilla's default face UVs do. */
    @Test
    void projectsEachFaceOntoItsBlockPosition() {
        assertEquals(new ConduitUv(2, 2, 10, 12), ConduitUvs.projected(BOX, Direction.DOWN));
        assertEquals(new ConduitUv(2, 4, 10, 14), ConduitUvs.projected(BOX, Direction.UP));
        assertEquals(new ConduitUv(6, 4, 14, 13), ConduitUvs.projected(BOX, Direction.NORTH));
        assertEquals(new ConduitUv(2, 4, 10, 13), ConduitUvs.projected(BOX, Direction.SOUTH));
        assertEquals(new ConduitUv(4, 4, 14, 13), ConduitUvs.projected(BOX, Direction.WEST));
        assertEquals(new ConduitUv(2, 4, 12, 13), ConduitUvs.projected(BOX, Direction.EAST));
        assertEquals(new ConduitUv(0, 0, 16, 16), ConduitUvs.projected(new AABB(0, 0, 0, 16, 16, 16), Direction.UP));
    }

    /** Turned, every corner shows the unturned corner's pixel turned about the sprite's centre. */
    @Test
    void turnedCornersShowTheTurnedProjection() {
        for (Direction face : Direction.values()) {
            ConduitUv flat = ConduitUvs.projected(BOX, face);
            for (int turns = 0; turns < 4; turns++) {
                ConduitUv turned = ConduitUvs.projected(BOX, face, turns);
                assertEquals(turns, turned.quarterTurns());
                for (int vertex = 0; vertex < 4; vertex++) {
                    assertArrayEquals(turn(corner(flat, vertex), turns), corner(turned, vertex + turns),
                            face + " turned " + turns + " vertex " + vertex);
                }
            }
        }
    }

    /** A face reaching the block's edge meets the same face of the next block at the same sprite pixel. */
    @Test
    void continuesAcrossTheBlockBoundary() {
        for (Direction face : Direction.values()) {
            for (Direction along : Direction.values()) {
                if (along.getAxis() == face.getAxis()) {
                    continue;
                }
                for (int turns = 0; turns < 4; turns++) {
                    // this block's face ends at the boundary on `along`; the neighbour's starts there
                    Vec3 here = point(face, along, 16);
                    Vec3 there = point(face, along, 0);
                    float[] a = texel(face, here, turns);
                    float[] b = texel(face, there, turns);
                    assertEquals(mod16(a[0]), mod16(b[0]), face + " along " + along + " turned " + turns);
                    assertEquals(mod16(a[1]), mod16(b[1]), face + " along " + along + " turned " + turns);
                }
            }
        }
    }

    /** After the flow turns, the sprite's v grows one pixel per pixel along the flow and u stays put. */
    @Test
    void flowTurnsPointTheSpritesVAlongTheFlow() {
        for (Direction face : Direction.values()) {
            for (Direction flow : Direction.values()) {
                OptionalInt turns = ConduitUvs.flowTurns(face, flow);
                if (flow.getAxis() == face.getAxis()) {
                    assertFalse(turns.isPresent(), face + " " + flow);
                    continue;
                }
                assertTrue(turns.isPresent(), face + " " + flow);
                Vec3 start = new Vec3(8, 8, 8);
                Vec3 step = start.add(flow.getStepX(), flow.getStepY(), flow.getStepZ());
                float[] a = texel(face, start, turns.getAsInt());
                float[] b = texel(face, step, turns.getAsInt());
                assertEquals(a[0], b[0], 1e-6, face + " " + flow);
                assertEquals(a[1] + 1, b[1], 1e-6, face + " " + flow);
            }
        }
    }

    /** A few rows of the table spelled out. */
    @Test
    void flowTurnsTable() {
        assertEquals(OptionalInt.of(0), ConduitUvs.flowTurns(Direction.UP, Direction.SOUTH));
        assertEquals(OptionalInt.of(2), ConduitUvs.flowTurns(Direction.UP, Direction.NORTH));
        assertEquals(OptionalInt.of(1), ConduitUvs.flowTurns(Direction.UP, Direction.WEST));
        assertEquals(OptionalInt.of(3), ConduitUvs.flowTurns(Direction.UP, Direction.EAST));
        assertEquals(OptionalInt.of(0), ConduitUvs.flowTurns(Direction.NORTH, Direction.DOWN));
        assertEquals(OptionalInt.of(2), ConduitUvs.flowTurns(Direction.NORTH, Direction.UP));
        assertEquals(OptionalInt.of(1), ConduitUvs.flowTurns(Direction.EAST, Direction.SOUTH));
        assertEquals(OptionalInt.of(3), ConduitUvs.flowTurns(Direction.WEST, Direction.SOUTH));
        assertEquals(OptionalInt.empty(), ConduitUvs.flowTurns(Direction.UP, Direction.DOWN));
    }

    /** A box outside the block or a turn outside 0 to 3 fails. */
    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> ConduitUvs.projected(BOX, Direction.UP, 4));
        assertThrows(IllegalArgumentException.class,
                () -> ConduitUvs.projected(new AABB(-1, 0, 0, 4, 4, 4), Direction.UP));
    }

    /** The sprite pixel at {@code p} on {@code face}: the default projection, turned. */
    private static float[] texel(Direction face, Vec3 p, int turns) {
        float x = (float) p.x;
        float y = (float) p.y;
        float z = (float) p.z;
        float[] uv = switch (face) {
            case DOWN -> new float[]{x, 16 - z};
            case UP -> new float[]{x, z};
            case NORTH -> new float[]{16 - x, 16 - y};
            case SOUTH -> new float[]{x, 16 - y};
            case WEST -> new float[]{z, 16 - y};
            case EAST -> new float[]{16 - z, 16 - y};
        };
        return turn(uv, turns);
    }

    /** {@code (u, v) -> (v, 16 - u)}, {@code turns} times. */
    private static float[] turn(float[] uv, int turns) {
        float[] out = uv;
        for (int i = 0; i < turns; i++) {
            out = new float[]{out[1], 16 - out[0]};
        }
        return out;
    }

    /** The corner FaceBakery hands to vertex {@code index} (mod 4): min/min, min/max, max/max, max/min. */
    private static float[] corner(ConduitUv uv, int index) {
        return switch (index % 4) {
            case 0 -> new float[]{uv.u0(), uv.v0()};
            case 1 -> new float[]{uv.u0(), uv.v1()};
            case 2 -> new float[]{uv.u1(), uv.v1()};
            default -> new float[]{uv.u1(), uv.v0()};
        };
    }

    /** A point on {@code face}'s plane at {@code at} along {@code along}'s axis, 5 elsewhere. */
    private static Vec3 point(Direction face, Direction along, double at) {
        double[] c = {5, 5, 5};
        c[index(face.getAxis())] = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 16 : 0;
        c[index(along.getAxis())] = along.getAxisDirection() == Direction.AxisDirection.POSITIVE ? at : 16 - at;
        return new Vec3(c[0], c[1], c[2]);
    }

    private static int index(Direction.Axis axis) {
        return switch (axis) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
    }

    private static float mod16(float value) {
        return ((value % 16) + 16) % 16;
    }
}
