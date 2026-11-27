package dev.silencebound.maxtech.lib.neoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.math.Quadrant;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import dev.silencebound.maxtech.lib.client.conduit.ConduitUvs;
import java.util.OptionalInt;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Checks the UVs a quad bakes with against vanilla's own vertex walk ({@code FaceInfo}) and corner assignment
 * ({@code CuboidFace.getU/getV}), the two steps {@code FaceBakery.bakeVertex} takes before mapping into the sprite.
 */
class ConduitFaceUvsTest {
    private static final AABB BOX = new AABB(2, 3, 4, 10, 12, 14);

    /** Without UVs a quad bakes as before: the whole sprite, unturned. */
    @Test
    void noUvIsTheWholeSpriteUnturned() {
        assertEquals(new CuboidFace.UVs(0, 0, 16, 16), ConduitFaceUvs.uvs(null));
        assertSame(Quadrant.R0, ConduitFaceUvs.rotation(null));
        assertEquals(new CuboidFace.UVs(1, 2, 3, 4), ConduitFaceUvs.uvs(new ConduitUv(1, 2, 3, 4, 2)));
        assertSame(Quadrant.R270, ConduitFaceUvs.rotation(new ConduitUv(1, 2, 3, 4, 3)));
    }

    /** Projected UVs bake so each vertex shows the sprite pixel at its block position, turned as asked. */
    @Test
    void projectedUvsBakeToTheVertexPositions() {
        for (Direction face : Direction.values()) {
            for (int turns = 0; turns < 4; turns++) {
                ConduitUv uv = ConduitUvs.projected(BOX, face, turns);
                for (int vertex = 0; vertex < 4; vertex++) {
                    Vector3f p = position(face, vertex);
                    float[] expected = turn(defaultUv(face, p), turns);
                    String at = face + " turned " + turns + " vertex " + vertex;
                    assertEquals(expected[0], u(uv, vertex), 1e-5, at);
                    assertEquals(expected[1], v(uv, vertex), 1e-5, at);
                }
            }
        }
    }

    /** With the flow turns, the baked sprite v grows along the flow by one pixel per pixel. */
    @Test
    void flowTurnsBakeTheSpritesVAlongTheFlow() {
        for (Direction face : Direction.values()) {
            for (Direction flow : Direction.values()) {
                OptionalInt turns = ConduitUvs.flowTurns(face, flow);
                if (turns.isEmpty()) {
                    continue;
                }
                ConduitUv uv = ConduitUvs.projected(BOX, face, turns.getAsInt());
                Vector3f p0 = position(face, 0);
                for (int vertex = 1; vertex < 4; vertex++) {
                    Vector3f d = position(face, vertex).sub(p0);
                    float along = d.x * flow.getStepX() + d.y * flow.getStepY() + d.z * flow.getStepZ();
                    assertEquals(along, v(uv, vertex) - v(uv, 0), 1e-5, face + " flowing " + flow);
                }
            }
        }
    }

    private static Vector3f position(Direction face, int vertex) {
        Vector3f from = new Vector3f((float) BOX.minX, (float) BOX.minY, (float) BOX.minZ);
        Vector3f to = new Vector3f((float) BOX.maxX, (float) BOX.maxY, (float) BOX.maxZ);
        return FaceInfo.fromFacing(face).getVertexInfo(vertex).select(from, to);
    }

    private static float u(ConduitUv uv, int vertex) {
        return CuboidFace.getU(ConduitFaceUvs.uvs(uv), ConduitFaceUvs.rotation(uv), vertex) * 16;
    }

    private static float v(ConduitUv uv, int vertex) {
        return CuboidFace.getV(ConduitFaceUvs.uvs(uv), ConduitFaceUvs.rotation(uv), vertex) * 16;
    }

    private static float[] defaultUv(Direction face, Vector3f p) {
        return switch (face) {
            case DOWN -> new float[]{p.x, 16 - p.z};
            case UP -> new float[]{p.x, p.z};
            case NORTH -> new float[]{16 - p.x, 16 - p.y};
            case SOUTH -> new float[]{p.x, 16 - p.y};
            case WEST -> new float[]{p.z, 16 - p.y};
            case EAST -> new float[]{16 - p.z, 16 - p.y};
        };
    }

    private static float[] turn(float[] uv, int turns) {
        float[] out = uv;
        for (int i = 0; i < turns; i++) {
            out = new float[]{out[1], 16 - out[0]};
        }
        return out;
    }
}
