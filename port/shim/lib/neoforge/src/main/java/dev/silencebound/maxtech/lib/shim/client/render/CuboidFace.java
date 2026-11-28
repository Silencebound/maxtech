package dev.silencebound.maxtech.lib.shim.client.render;

import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 26.1's {@code CuboidFace}, the part Lib uses: a face's UVs and the corner each vertex takes. The corner lookup
 * delegates to 1.21.1's {@link BlockFaceUV}, which {@code FaceBakery} bakes with, so a check against
 * {@link #getU}/{@link #getV} checks what 1.21.1 draws.
 */
public final class CuboidFace {
    private CuboidFace() {
    }

    /** A face's UVs in pixels, as 26.1's {@code CuboidFace.UVs}. */
    public record UVs(float minU, float minV, float maxU, float maxV) {
        public float getVertexU(int index) {
            return index != 0 && index != 1 ? this.maxU : this.minU;
        }

        public float getVertexV(int index) {
            return index != 0 && index != 3 ? this.maxV : this.minV;
        }

        /** A fresh 1.21.1 face UV for these UVs turned by {@code rotation}; {@code FaceBakery} changes it in place. */
        public BlockFaceUV toBlockFaceUV(Quadrant rotation) {
            return new BlockFaceUV(new float[]{minU, minV, maxU, maxV}, rotation.degrees());
        }
    }

    public static float getU(UVs uvs, Quadrant rotation, int vertex) {
        return uvs.toBlockFaceUV(rotation).getU(vertex) / 16.0F;
    }

    public static float getV(UVs uvs, Quadrant rotation, int vertex) {
        return uvs.toBlockFaceUV(rotation).getV(vertex) / 16.0F;
    }

    /** 26.1's {@code FaceInfo.VertexInfo#select}: the corner {@code vertex} sits at in the box {@code min}-{@code max}. */
    public static Vector3f select(FaceInfo.VertexInfo vertex, Vector3fc min, Vector3fc max) {
        return new Vector3f(pick(vertex.xFace, min, max), pick(vertex.yFace, min, max), pick(vertex.zFace, min, max));
    }

    private static float pick(int face, Vector3fc min, Vector3fc max) {
        if (face == FaceInfo.Constants.MIN_X) {
            return min.x();
        } else if (face == FaceInfo.Constants.MIN_Y) {
            return min.y();
        } else if (face == FaceInfo.Constants.MIN_Z) {
            return min.z();
        } else if (face == FaceInfo.Constants.MAX_X) {
            return max.x();
        } else if (face == FaceInfo.Constants.MAX_Y) {
            return max.y();
        }
        return max.z();
    }
}
