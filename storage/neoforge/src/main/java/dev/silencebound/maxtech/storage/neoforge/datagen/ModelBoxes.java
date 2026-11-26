package dev.silencebound.maxtech.storage.neoforge.datagen;

import com.mojang.math.Quadrant;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.generators.template.ElementBuilder;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;
import net.neoforged.neoforge.client.model.generators.template.FaceBuilder;
import org.jspecify.annotations.Nullable;

/**
 * Element helpers for Storage's block models. Cable-like parts (a port's or terminal's stub, a cable segment) follow
 * the owner's stripe rule: the stripe texture runs along the part's axis on every side face, unrotated where the face's
 * u axis is that axis and turned 90 degrees where its v axis is (north and south faces: u = x, v = y; east and west: u
 * = z, v = y; up and down: u = x, v = z). Along the axis the UVs follow the real coordinates, so dashes keep their
 * spacing on short and long parts; across it the face takes the whole texture, which keeps the centre line thin. With
 * 90 degrees, Minecraft maps the texture's u onto the face's v ({@code CuboidFace.getU} shifts the vertex index), so
 * the same {@code (along0, 0, along1, 16)} rectangle serves both cases.
 */
final class ModelBoxes {
    private ModelBoxes() {
    }

    /**
     * A box from {@code from} to {@code to} (pixels) whose faces all show {@code texture} on the part of it they cover,
     * as vanilla's default UVs would; {@code cull}, if not null, culls the face lying on that block face.
     */
    static void box(ExtendedModelTemplateBuilder builder, float[] from, float[] to, TextureSlot texture,
            @Nullable Direction cull, Direction... skip) {
        builder.element(element -> {
            element.from(from[0], from[1], from[2]).to(to[0], to[1], to[2]);
            for (Direction face : Direction.values()) {
                if (contains(skip, face)) {
                    continue;
                }
                element.face(face, f -> {
                    float[] uv = faceUv(face, from, to);
                    f.texture(texture).uvs(uv[0], uv[1], uv[2], uv[3]);
                    if (face == cull) {
                        f.cullface(face);
                    }
                });
            }
        });
    }

    /**
     * A cable-like part along {@code axis} from {@code from} to {@code to}: side faces show {@code stripe} by the rule
     * above, the faces across the axis {@code end} (skipping those in {@code skip}). The end face lying on a block face
     * is culled.
     */
    static void stripedBox(ExtendedModelTemplateBuilder builder, Direction.Axis axis, float[] from, float[] to,
            TextureSlot stripe, TextureSlot end, Direction... skip) {
        builder.element(element -> stripedFaces(element, axis, from, to, stripe, end, skip));
    }

    private static void stripedFaces(ElementBuilder element, Direction.Axis axis, float[] from, float[] to,
            TextureSlot stripe, TextureSlot end, Direction[] skip) {
        element.from(from[0], from[1], from[2]).to(to[0], to[1], to[2]);
        for (Direction face : Direction.values()) {
            if (contains(skip, face)) {
                continue;
            }
            element.face(face, f -> stripedFace(f, face, axis, from, to, stripe, end));
        }
    }

    private static void stripedFace(FaceBuilder f, Direction face, Direction.Axis axis, float[] from, float[] to,
            TextureSlot stripe, TextureSlot end) {
        if (face.getAxis() == axis) {
            f.texture(end).uvs(0, 0, 16, 16);
            if (onBlockFace(face, from, to)) {
                f.cullface(face);
            }
            return;
        }
        float[] uv = faceUv(face, from, to);
        if (uAxis(face) == axis) {
            f.texture(stripe).uvs(uv[0], 0, uv[2], 16);
        } else {
            f.texture(stripe).uvs(uv[1], 0, uv[3], 16).rotation(Quadrant.R90);
        }
    }

    /** The axis a face's texture u runs along. */
    static Direction.Axis uAxis(Direction face) {
        return switch (face) {
            case NORTH, SOUTH, UP, DOWN -> Direction.Axis.X;
            case EAST, WEST -> Direction.Axis.Z;
        };
    }

    /** Vanilla's default UVs of {@code face} of a box: the part of the texture the face covers in world space. */
    static float[] faceUv(Direction face, float[] from, float[] to) {
        return switch (face) {
            case NORTH -> new float[]{16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
            case SOUTH -> new float[]{from[0], 16 - to[1], to[0], 16 - from[1]};
            case WEST -> new float[]{from[2], 16 - to[1], to[2], 16 - from[1]};
            case EAST -> new float[]{16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
            case UP -> new float[]{from[0], from[2], to[0], to[2]};
            case DOWN -> new float[]{from[0], 16 - to[2], to[0], 16 - from[2]};
        };
    }

    private static boolean onBlockFace(Direction face, float[] from, float[] to) {
        int axis = switch (face.getAxis()) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? to[axis] >= 16 : from[axis] <= 0;
    }

    private static boolean contains(Direction[] faces, Direction face) {
        for (Direction candidate : faces) {
            if (candidate == face) {
                return true;
            }
        }
        return false;
    }

    /** Shorthand for a corner in pixels. */
    static float[] at(float x, float y, float z) {
        return new float[]{x, y, z};
    }
}
