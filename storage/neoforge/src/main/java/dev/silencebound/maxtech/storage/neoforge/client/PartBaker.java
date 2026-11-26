package dev.silencebound.maxtech.storage.neoforge.client;

import com.mojang.math.Quadrant;
import dev.silencebound.maxtech.storage.cable.CableGeometry;
import dev.silencebound.maxtech.storage.part.PartBoxes;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortShapes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * Draws one kind of cable part on one face of a cable, for {@link StorageCableModel}: the faces of the part's own boxes
 * (the cable's arm behind it is the cable model's). One baker per part type, made with the sprites when models bake.
 */
@FunctionalInterface
interface PartBaker {
    CuboidFace.UVs WHOLE = new CuboidFace.UVs(0, 0, 16, 16);
    int FULL_BRIGHT = 15;
    /** Where a thin side of a port's grey step samples its texture: the plain inset in the top-left corner. */
    CuboidFace.UVs STEP_EDGE = new CuboidFace.UVs(0, 0, 2, 2);

    /** The faces of a part of {@code type} on {@code side}, drawn in look {@code variant}. */
    List<QuadBaking.Face> bake(PartType type, Direction side, int variant);

    /**
     * A flat panel like the terminal's screen: the face of a box lying on the block face shows one of {@code fronts}
     * (one per look variant, the last one for higher variants), full-bright when {@code glowing}, culled against a
     * solid neighbour; every other face shows {@code side} as an element's default UVs would.
     */
    static PartBaker panel(Material.Baked[] fronts, Material.Baked side, boolean glowing) {
        return (type, face, variant) -> {
            Material.Baked front = fronts[Math.min(Math.max(variant, 0), fronts.length - 1)];
            List<QuadBaking.Face> out = new ArrayList<>();
            for (AABB box : type.boxes(face)) {
                for (Direction facing : Direction.values()) {
                    if (facing == face && onBlockFace(box, face)) {
                        out.add(new QuadBaking.Face(box, facing, front, WHOLE, Quadrant.R0, -1, true,
                                glowing ? FULL_BRIGHT : 0));
                    } else {
                        float[] uv = CableGeometry.defaultUv(box, facing);
                        out.add(new QuadBaking.Face(box, facing, side, new CuboidFace.UVs(uv[0], uv[1], uv[2], uv[3]),
                                Quadrant.R0, -1, false));
                    }
                }
            }
            return out;
        };
    }

    /**
     * A part drawn in one sprite on every face, one texel per block pixel ({@link CableGeometry#defaultUv}): the toggle
     * bus's collar, whose sprite ({@code sprites}, one per look variant, the last one for higher variants) is laid out
     * so every face of the collar shows its band. The face on the block face is culled against a solid neighbour.
     */
    static PartBaker uniform(Material.Baked[] sprites) {
        return (type, face, variant) -> {
            Material.Baked sprite = sprites[Math.min(Math.max(variant, 0), sprites.length - 1)];
            List<QuadBaking.Face> out = new ArrayList<>();
            for (AABB box : type.boxes(face)) {
                for (Direction facing : Direction.values()) {
                    float[] uv = CableGeometry.defaultUv(box, facing);
                    out.add(new QuadBaking.Face(box, facing, sprite, new CuboidFace.UVs(uv[0], uv[1], uv[2], uv[3]),
                            Quadrant.R0, -1, facing == face && onBlockFace(box, face)));
                }
            }
            return out;
        };
    }

    /**
     * A port part (MTX item 4): the steps of {@link PortShapes#partSteps} for the mode whose {@link PortMode#id()} is
     * the look variant, the tip in {@code tip} and the others in {@code step}. Front and back faces show the step's own
     * crop, one texel per pixel; thin sides the step texture's plain top-left corner. Faces lying wholly against a
     * wider step are left out; the face on the block face is culled against a solid neighbour.
     */
    static PartBaker portSteps(Material.Baked step, Material.Baked tip) {
        return (type, face, variant) -> {
            List<AABB> steps = PortShapes.partSteps(PortMode.byId(variant));
            List<QuadBaking.Face> out = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                AABB north = steps.get(i);
                AABB box = PartBoxes.turn(north, face);
                Material.Baked sprite = PortShapes.tip(north) ? tip : step;
                for (Direction facing : Direction.values()) {
                    boolean front = facing == face;
                    if (facing.getAxis() == face.getAxis()) {
                        if (PortShapes.hidden(steps, i, front)) {
                            continue;
                        }
                        float[] uv = CableGeometry.defaultUv(box, facing);
                        out.add(new QuadBaking.Face(box, facing, sprite, new CuboidFace.UVs(uv[0], uv[1], uv[2], uv[3]),
                                Quadrant.R0, -1, front && onBlockFace(box, face)));
                    } else {
                        out.add(new QuadBaking.Face(box, facing, sprite, STEP_EDGE, Quadrant.R0, -1, false));
                    }
                }
            }
            return out;
        };
    }

    /** Whether the {@code face} side of {@code box} (pixels) lies on the block's face. */
    private static boolean onBlockFace(AABB box, Direction face) {
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? box.max(face.getAxis()) >= 16
                : box.min(face.getAxis()) <= 0;
    }
}
