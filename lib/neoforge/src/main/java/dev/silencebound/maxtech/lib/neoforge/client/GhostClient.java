package dev.silencebound.maxtech.lib.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.client.ghost.GhostBlocks;
import dev.silencebound.maxtech.lib.client.ghost.GhostSet;
import dev.silencebound.maxtech.lib.config.ClientConfig;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * Draws {@link GhostBlocks}: the faces of every filled cell in one {@code debugQuads} batch (translucent, depth-tested,
 * no depth write, so real blocks hide ghosts behind them) and the outlines of every cell in one {@code lines} batch.
 * Cells farther than {@code highlights.renderDistance} from the camera are skipped. Two submissions per frame however
 * many cells are shown, and nothing is allocated per frame. Clears the ghosts on leaving a world. Client thread.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
final class GhostClient {
    /** How far a filled cell's cube sits inside its block, so it does not fight with neighbouring block faces. */
    private static final float INSET = 0.125F;
    /** How far an outline cell's box sits outside its block, so the block's own faces do not cover it. */
    private static final float OUTSET = 0.004F;
    private static final Faces FACES = new Faces();
    private static final Outlines OUTLINES = new Outlines();

    private GhostClient() {
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        GhostBlocks.client().clearAll();
    }

    @SubscribeEvent
    static void submit(SubmitCustomGeometryEvent event) {
        GhostBlocks ghosts = GhostBlocks.client();
        if (ghosts.isEmpty()) {
            return;
        }
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        int distance = ClientConfig.highlightRenderDistance();
        List<GhostSet> shown = ghosts.shown();
        boolean anyFilled = false;
        for (int s = 0; s < shown.size() && !anyFilled; s++) {
            GhostSet set = shown.get(s);
            for (int i = 0; i < set.size(); i++) {
                if (set.filled(i)) {
                    anyFilled = true;
                    break;
                }
            }
        }
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        PoseStack poseStack = event.getPoseStack();
        OUTLINES.prepare(shown, camera, distance);
        collector.submitCustomGeometry(poseStack, RenderTypes.lines(), OUTLINES);
        if (anyFilled) {
            FACES.prepare(shown, camera, distance);
            collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), FACES);
        }
    }

    /** What both batches share: the shown sets, the camera and the range. One instance per batch, reused. */
    private abstract static class Batch implements SubmitNodeCollector.CustomGeometryRenderer {
        List<GhostSet> shown = List.of();
        double cameraX;
        double cameraY;
        double cameraZ;
        double maxDistanceSquared;

        final void prepare(List<GhostSet> sets, Vec3 camera, int distance) {
            shown = sets;
            cameraX = camera.x;
            cameraY = camera.y;
            cameraZ = camera.z;
            maxDistanceSquared = (double) distance * distance;
        }

        /** Whether the centre of the block at {@code pos} is in range. */
        final boolean near(long pos) {
            double dx = BlockPos.getX(pos) + 0.5 - cameraX;
            double dy = BlockPos.getY(pos) + 0.5 - cameraY;
            double dz = BlockPos.getZ(pos) + 0.5 - cameraZ;
            return dx * dx + dy * dy + dz * dz <= maxDistanceSquared;
        }
    }

    /** The outline of every cell: inset for filled cells, outset for outline cells, in the opaque colour. */
    private static final class Outlines extends Batch {
        @Override
        public void render(PoseStack.Pose pose, VertexConsumer buffer) {
            float width = Minecraft.getInstance().gameRenderer
                    .getGameRenderState().windowRenderState.appropriateLineWidth;
            for (int s = 0; s < shown.size(); s++) {
                GhostSet set = shown.get(s);
                for (int i = 0; i < set.size(); i++) {
                    long pos = set.position(i);
                    if (!near(pos)) {
                        continue;
                    }
                    float grow = set.filled(i) ? -INSET : OUTSET;
                    float x = (float) (BlockPos.getX(pos) - cameraX);
                    float y = (float) (BlockPos.getY(pos) - cameraY);
                    float z = (float) (BlockPos.getZ(pos) - cameraZ);
                    BoxLines.box(buffer, pose, x - grow, y - grow, z - grow, x + 1 + grow, y + 1 + grow, z + 1 + grow,
                            set.colour(i) | 0xFF000000, width);
                }
            }
        }
    }

    /** The six faces of every filled cell's inset cube in its translucent colour. */
    private static final class Faces extends Batch {
        @Override
        public void render(PoseStack.Pose pose, VertexConsumer buffer) {
            for (int s = 0; s < shown.size(); s++) {
                GhostSet set = shown.get(s);
                for (int i = 0; i < set.size(); i++) {
                    long pos = set.position(i);
                    if (!set.filled(i) || !near(pos)) {
                        continue;
                    }
                    float x0 = (float) (BlockPos.getX(pos) - cameraX) + INSET;
                    float y0 = (float) (BlockPos.getY(pos) - cameraY) + INSET;
                    float z0 = (float) (BlockPos.getZ(pos) - cameraZ) + INSET;
                    float x1 = x0 + 1 - 2 * INSET;
                    float y1 = y0 + 1 - 2 * INSET;
                    float z1 = z0 + 1 - 2 * INSET;
                    int argb = set.colour(i);
                    quad(buffer, pose, argb, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
                    quad(buffer, pose, argb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
                    quad(buffer, pose, argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
                    quad(buffer, pose, argb, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
                    quad(buffer, pose, argb, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
                    quad(buffer, pose, argb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
                }
            }
        }

        @SuppressWarnings("TooManyParameters") // four corners of a quad, kept primitive so a frame allocates nothing
        private static void quad(VertexConsumer buffer, PoseStack.Pose pose, int argb, float ax, float ay, float az,
                float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
            buffer.addVertex(pose, ax, ay, az).setColor(argb);
            buffer.addVertex(pose, bx, by, bz).setColor(argb);
            buffer.addVertex(pose, cx, cy, cz).setColor(argb);
            buffer.addVertex(pose, dx, dy, dz).setColor(argb);
        }
    }
}
