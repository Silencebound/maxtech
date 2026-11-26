package dev.silencebound.maxtech.storage.neoforge.client;

import com.mojang.math.Quadrant;
import dev.silencebound.maxtech.storage.controller.ControllerBlock;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerFace;
import dev.silencebound.maxtech.storage.controller.ControllerStatus;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;

/**
 * The storage controller's model (M7R item 7, design "Runic sigil" on plate B): the same face on all six sides of every
 * controller block, with connected textures and a glow that rolls outward through the multiblock. No block entity
 * renderer, nothing ticks; all parts are baked once when models bake.
 *
 * <p>
 * Each face is two quads. The plate ({@code controller/face/plate_<edges>}, cutout) lies on the block face; its grooves
 * are cut out and its 1 px frame drawn only on the edges {@link ControllerFace#edges} picks from the neighbours at
 * chunk meshing (16 sprites, one per edge mask). Behind it, {@link #GLOW_INSET} pixels into the block, lies the glow
 * plane ({@code glow_<status>_<phase>}), opaque and full-bright, seen through the grooves; it is never coplanar with
 * the plate. The glow sprites are vanilla texture animations that all run on the block atlas's one clock; each status
 * has {@link ControllerFace#PHASES} copies started at different frames, and every face takes the copy for its distance
 * from the multiblock's centre ({@link ControllerFace#phase}, from the block entity's synced
 * {@link ControllerBlockEntity#GLOW_CENTRE}), so faces further out light up later and the waves travel outward block by
 * block. Off has one still, unlit glow plane.
 */
final class ControllerFaceModel implements DynamicBlockStateModel {
    /** How far the glow plane lies behind the plate, in pixels. */
    static final float GLOW_INSET = 0.25F;
    private static final CuboidFace.UVs WHOLE = new CuboidFace.UVs(0, 0, 16, 16);
    private static final Direction[] FACES = Direction.values();
    private static final int FULL_BRIGHT = 15;

    /** By face ({@link Direction#get3DDataValue()}) * 16 + edge mask. */
    private final BlockStateModelPart[] plates = new BlockStateModelPart[FACES.length * 16];
    /** Per status: by face * PHASES + phase (off: one per face). */
    private final EnumMap<ControllerStatus, BlockStateModelPart[]> glows = new EnumMap<>(ControllerStatus.class);
    private final Material.Baked particle;

    /**
     * {@code plates} holds the 16 plate sprites by edge mask; {@code glows} the glow sprites per status, one per phase
     * for an animated status, a single one for off.
     */
    ControllerFaceModel(List<Material.Baked> plates, Map<ControllerStatus, List<Material.Baked>> glows) {
        this.particle = plates.get(ControllerFace.ALL);
        for (Direction face : FACES) {
            for (int mask = 0; mask < 16; mask++) {
                this.plates[face.get3DDataValue() * 16 + mask] = QuadBaking
                        .part(List.of(new QuadBaking.Face(new AABB(0, 0, 0, 16, 16, 16), face, plates.get(mask), WHOLE,
                                Quadrant.R0, -1, true)), particle);
            }
        }
        for (ControllerStatus status : ControllerStatus.values()) {
            List<Material.Baked> sprites = glows.get(status);
            if (sprites == null) {
                throw new IllegalArgumentException("No glow sprites for " + status);
            }
            int emission = status == ControllerStatus.OFF ? 0 : FULL_BRIGHT;
            BlockStateModelPart[] parts = new BlockStateModelPart[FACES.length * sprites.size()];
            for (Direction face : FACES) {
                for (int phase = 0; phase < sprites.size(); phase++) {
                    parts[face.get3DDataValue() * sprites.size() + phase] = QuadBaking
                            .part(List.of(new QuadBaking.Face(inset(face), face, sprites.get(phase), WHOLE, Quadrant.R0,
                                    -1, true, emission)), particle);
                }
            }
            this.glows.put(status, parts);
        }
    }

    /** The full block with {@code face}'s side moved {@link #GLOW_INSET} pixels inwards. */
    static AABB inset(Direction face) {
        return switch (face) {
            case DOWN -> new AABB(0, GLOW_INSET, 0, 16, 16, 16);
            case UP -> new AABB(0, 0, 0, 16, 16 - GLOW_INSET, 16);
            case NORTH -> new AABB(0, 0, GLOW_INSET, 16, 16, 16);
            case SOUTH -> new AABB(0, 0, 0, 16, 16, 16 - GLOW_INSET);
            case WEST -> new AABB(GLOW_INSET, 0, 0, 16, 16, 16);
            case EAST -> new AABB(0, 0, 0, 16 - GLOW_INSET, 16, 16);
        };
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
            List<BlockStateModelPart> output) {
        int occupancy = occupancy(level, pos);
        Integer packed = level.getModelData(pos).get(ControllerBlockEntity.GLOW_CENTRE);
        int centre = packed == null ? 0 : packed;
        ControllerStatus status = state.hasProperty(ControllerBlock.STATUS)
                ? state.getValue(ControllerBlock.STATUS)
                : ControllerStatus.OFF;
        BlockStateModelPart[] glow = glows.get(status);
        if (glow == null) {
            return;
        }
        int phases = glow.length / FACES.length;
        for (Direction face : FACES) {
            output.add(plates[face.get3DDataValue() * 16 + ControllerFace.edges(face, occupancy)]);
            int phase = phases == 1 ? 0 : ControllerFace.phase(centre, face);
            output.add(glow[face.get3DDataValue() * phases + phase]);
        }
    }

    /**
     * The controller blocks among the 18 neighbours a face's edges read (the six beside the block and the twelve along
     * its edges), as {@link ControllerFace}'s occupancy cube. Meshing reads a region one section around, so all lie
     * inside it.
     */
    private static int occupancy(BlockAndTintGetter level, BlockPos pos) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int bits = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int away = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if ((away == 1 || away == 2) && level.getBlockState(at.setWithOffset(pos, dx, dy, dz))
                            .getBlock() instanceof ControllerBlock) {
                        bits |= ControllerFace.bit(dx, dy, dz);
                    }
                }
            }
        }
        return bits;
    }

    @Override
    public Material.Baked particleMaterial() {
        return particle;
    }

    @Override
    public int materialFlags() {
        // The glow strips are animated.
        return BakedQuad.FLAG_ANIMATED;
    }
}
