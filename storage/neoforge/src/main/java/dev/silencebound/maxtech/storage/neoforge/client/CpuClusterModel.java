package dev.silencebound.maxtech.storage.neoforge.client;

import com.mojang.math.Quadrant;
import dev.silencebound.maxtech.storage.controller.ControllerFace;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuConnections;
import java.util.List;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;

/**
 * A crafting CPU block's model (B09 item 28): connected textures, so a formed cluster reads as one machine. Every face
 * is one quad whose sprite has the 1 px frame only on the edges {@link ControllerFace#edges} picks (16 sprites per
 * block, one per edge mask; the block's own symbol stays in the middle of each). The neighbours come from the
 * {@link CpuConnections} block states, never from the block types around it: only blocks of the same formed cluster
 * connect. An edge neighbour (two steps away, along an edge) counts when a connected direct neighbour is connected
 * towards it. Inner corners of a flat wall keep a 1 px notch where the frames meet, as on the controller; corner-aware
 * sprites (47 per block) would need the diagonal neighbours too. Static: nothing animates, nothing ticks; all parts are
 * baked once when models bake.
 */
final class CpuClusterModel implements DynamicBlockStateModel {
    private static final CuboidFace.UVs WHOLE = new CuboidFace.UVs(0, 0, 16, 16);
    private static final Direction[] FACES = Direction.values();

    /** By face ({@link Direction#get3DDataValue()}) * 16 + edge mask. */
    private final BlockStateModelPart[] faces = new BlockStateModelPart[FACES.length * 16];
    private final Material.Baked particle;

    /** {@code sprites} holds the block's 16 face sprites by edge mask ({@link ControllerFace#ALL} is a lone block). */
    CpuClusterModel(List<Material.Baked> sprites) {
        this.particle = sprites.get(ControllerFace.ALL);
        for (Direction face : FACES) {
            for (int mask = 0; mask < 16; mask++) {
                faces[face.get3DDataValue() * 16 + mask] = QuadBaking
                        .part(List.of(new QuadBaking.Face(new AABB(0, 0, 0, 16, 16, 16), face, sprites.get(mask), WHOLE,
                                Quadrant.R0, -1, true)), particle);
            }
        }
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
            List<BlockStateModelPart> output) {
        int occupancy = occupancy(level, pos, CpuConnections.mask(state));
        for (Direction face : FACES) {
            output.add(faces[face.get3DDataValue() * 16 + ControllerFace.edges(face, occupancy)]);
        }
    }

    /**
     * The same-cluster blocks among the 18 neighbours the face edges read, as {@link ControllerFace}'s occupancy cube:
     * the six beside the block from its own connections, and the twelve along its edges from the connections of the
     * connected blocks beside it. Meshing reads a region one section around, so all lie inside it.
     */
    static int occupancy(BlockAndTintGetter level, BlockPos pos, int connections) {
        int bits = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (Direction side : FACES) {
            if ((connections & CpuConnections.bit(side)) == 0) {
                continue;
            }
            bits |= ControllerFace.bit(side.getStepX(), side.getStepY(), side.getStepZ());
            int beyond = CpuConnections.mask(level.getBlockState(at.setWithOffset(pos, side)));
            for (Direction along : FACES) {
                if (along.getAxis() != side.getAxis() && (beyond & CpuConnections.bit(along)) != 0) {
                    bits |= ControllerFace.bit(side.getStepX() + along.getStepX(), side.getStepY() + along.getStepY(),
                            side.getStepZ() + along.getStepZ());
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
        return 0;
    }
}
