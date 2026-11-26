package dev.silencebound.maxtech.storage.tool.layer;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * The positions the cable layer fills between two points: along X first, then Z, then Y, so a run between two points on
 * one level is an L on the floor and a riser comes last. Both ends are included; consecutive positions always touch
 * face to face, so the laid cables connect. Pure.
 */
public final class LayerPath {
    private LayerPath() {
    }

    /** Positions in the path from {@code from} to {@code to}, both included. */
    public static int length(BlockPos from, BlockPos to) {
        return from.distManhattan(to) + 1;
    }

    /** The path from {@code from} to {@code to}, in laying order, both ends included. */
    public static List<BlockPos> between(BlockPos from, BlockPos to) {
        List<BlockPos> path = new ArrayList<>(length(from, to));
        int x = from.getX();
        int y = from.getY();
        int z = from.getZ();
        path.add(new BlockPos(x, y, z));
        while (x != to.getX()) {
            x += Integer.signum(to.getX() - x);
            path.add(new BlockPos(x, y, z));
        }
        while (z != to.getZ()) {
            z += Integer.signum(to.getZ() - z);
            path.add(new BlockPos(x, y, z));
        }
        while (y != to.getY()) {
            y += Integer.signum(to.getY() - y);
            path.add(new BlockPos(x, y, z));
        }
        return path;
    }
}
