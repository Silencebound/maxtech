package dev.silencebound.maxtech.storage.neoforge.client;

import com.mojang.blaze3d.platform.Transparency;
import com.mojang.math.Quadrant;
import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.FaceBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.ExtraFaceData;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Bakes the faces of Storage's code-built model parts (cable bodies, drive cells) into quads, the way Lib's conduit
 * model does: baked lazily on meshing threads with no interning, a colour baked into the quad instead of a tint source,
 * and the chunk layer taken from the sprite's own pixels (an overlay with transparent pixels lands in the cutout
 * layer).
 */
final class QuadBaking {
    private static final ModelBaker.Interner NO_INTERNING = new ModelBaker.Interner() {
        @Override
        public Vector3fc vector(Vector3fc vector) {
            return vector;
        }

        @Override
        public BakedQuad.MaterialInfo materialInfo(BakedQuad.MaterialInfo material) {
            return material;
        }
    };

    private QuadBaking() {
    }

    /**
     * One face to bake: the {@code facing} side of {@code box} (pixels) showing {@code uvs} of {@code sprite}, lit at
     * least at {@code emission} (0 to 15; 15 is full-bright, as a screen).
     */
    record Face(AABB box, Direction facing, Material.Baked sprite, CuboidFace.UVs uvs, Quadrant rotation, int argb,
            boolean culled, int emission) {
        Face(AABB box, Direction facing, Material.Baked sprite, CuboidFace.UVs uvs, Quadrant rotation, int argb,
                boolean culled) {
            this(box, facing, sprite, uvs, rotation, argb, culled, 0);
        }
    }

    /** A model part of {@code faces}, its particle sprite {@code particle}. */
    static BlockStateModelPart part(List<Face> faces, Material.Baked particle) {
        QuadCollection.Builder quads = new QuadCollection.Builder();
        for (Face face : faces) {
            BakedQuad quad = quad(face);
            if (face.culled()) {
                quads.addCulledFace(face.facing(), quad);
            } else {
                quads.addUnculledFace(quad);
            }
        }
        return new SimpleModelWrapper(quads.build(), true, particle);
    }

    private static BakedQuad quad(Face face) {
        Transparency transparency = face.sprite().sprite().contents().transparency();
        BakedQuad.MaterialInfo info = BakedQuad.MaterialInfo.of(face.sprite(), transparency, -1, true, face.emission(),
                true);
        AABB box = face.box();
        Vector3f from = new Vector3f((float) box.minX, (float) box.minY, (float) box.minZ);
        Vector3f to = new Vector3f((float) box.maxX, (float) box.maxY, (float) box.maxZ);
        return FaceBakery.bakeQuad(NO_INTERNING, from, to, face.uvs(), face.rotation(), info, face.facing(),
                BlockModelRotation.IDENTITY, null, new ExtraFaceData(face.argb(), face.emission(), true));
    }
}
