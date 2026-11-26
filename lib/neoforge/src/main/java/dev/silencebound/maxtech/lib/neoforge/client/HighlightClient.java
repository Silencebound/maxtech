package dev.silencebound.maxtech.lib.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.config.ClientConfig;
import dev.silencebound.maxtech.lib.highlight.HighlightRequest;
import dev.silencebound.maxtech.lib.highlight.HighlightStore;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * Draws {@link HighlightStore}'s highlights: every shape of every active request goes into one of two batches per
 * frame, depth-tested lines ({@code RenderTypes.lines()}) or lines without a depth test
 * ({@link HighlightRenderTypes#seeThroughLines()}), so the cost is two submissions however many requests are shown.
 * Shapes farther than {@code highlights.renderDistance} from the camera are skipped. Ticks the store's clock and clears
 * it on leaving a world.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
final class HighlightClient {
    private static final ContextKey<HighlightStore> STORE_KEY = new ContextKey<>(MaxtechLib.id("highlights"));
    private static final ShapeRenderer DEPTH_TESTED = new ShapeRenderer(false);
    private static final ShapeRenderer SEE_THROUGH = new ShapeRenderer(true);

    private HighlightClient() {
    }

    @SubscribeEvent
    static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(HighlightRenderTypes.SEE_THROUGH_LINES_PIPELINE);
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        HighlightStore.client().tick();
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        HighlightStore.client().clearAll();
    }

    @SubscribeEvent
    static void extract(ExtractLevelRenderStateEvent event) {
        HighlightStore store = HighlightStore.client();
        if (!store.isEmpty()) {
            event.getRenderState().setRenderData(STORE_KEY, store);
        }
    }

    @SubscribeEvent
    static void submit(SubmitCustomGeometryEvent event) {
        HighlightStore store = event.getLevelRenderState().getRenderData(STORE_KEY);
        if (store == null) {
            return;
        }
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        int distance = ClientConfig.highlightRenderDistance();
        List<HighlightStore.Active> active = store.active();
        boolean anyDepthTested = false;
        boolean anySeeThrough = false;
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).request().throughWalls()) {
                anySeeThrough = true;
            } else {
                anyDepthTested = true;
            }
        }
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        if (anyDepthTested) {
            DEPTH_TESTED.prepare(active, camera, distance);
            collector.submitCustomGeometry(poseStack, RenderTypes.lines(), DEPTH_TESTED);
        }
        if (anySeeThrough) {
            SEE_THROUGH.prepare(active, camera, distance);
            collector.submitCustomGeometry(poseStack, HighlightRenderTypes.seeThroughLines(), SEE_THROUGH);
        }
    }

    /**
     * Writes the shapes of every request with a matching {@code throughWalls} into one line buffer, relative to the
     * camera. One instance per batch, reused every frame.
     */
    private static final class ShapeRenderer implements SubmitNodeCollector.CustomGeometryRenderer {
        private final boolean throughWalls;
        private List<HighlightStore.Active> active = List.of();
        private double cameraX;
        private double cameraY;
        private double cameraZ;
        private double maxDistanceSquared;

        ShapeRenderer(boolean throughWalls) {
            this.throughWalls = throughWalls;
        }

        void prepare(List<HighlightStore.Active> requests, Vec3 camera, int distance) {
            active = requests;
            cameraX = camera.x;
            cameraY = camera.y;
            cameraZ = camera.z;
            maxDistanceSquared = (double) distance * distance;
        }

        @Override
        public void render(PoseStack.Pose pose, VertexConsumer buffer) {
            float width = Minecraft.getInstance().gameRenderer
                    .getGameRenderState().windowRenderState.appropriateLineWidth;
            for (int r = 0; r < active.size(); r++) {
                HighlightRequest request = active.get(r).request();
                if (request.throughWalls() != throughWalls) {
                    continue;
                }
                for (int i = 0; i < request.boxCount(); i++) {
                    if (!near((request.box(i, 0) + request.box(i, 3)) / 2, (request.box(i, 1) + request.box(i, 4)) / 2,
                            (request.box(i, 2) + request.box(i, 5)) / 2)) {
                        continue;
                    }
                    BoxLines.box(buffer, pose, (float) (request.box(i, 0) - cameraX),
                            (float) (request.box(i, 1) - cameraY), (float) (request.box(i, 2) - cameraZ),
                            (float) (request.box(i, 3) - cameraX), (float) (request.box(i, 4) - cameraY),
                            (float) (request.box(i, 5) - cameraZ), request.boxColour(i), width);
                }
                for (int i = 0; i < request.lineCount(); i++) {
                    if (!segmentNear(request.line(i, 0), request.line(i, 1), request.line(i, 2), request.line(i, 3),
                            request.line(i, 4), request.line(i, 5))) {
                        continue;
                    }
                    BoxLines.line(buffer, pose, (float) (request.line(i, 0) - cameraX),
                            (float) (request.line(i, 1) - cameraY), (float) (request.line(i, 2) - cameraZ),
                            (float) (request.line(i, 3) - cameraX), (float) (request.line(i, 4) - cameraY),
                            (float) (request.line(i, 5) - cameraZ), request.lineColour(i), width);
                }
            }
        }

        /** Whether the point of the segment closest to the camera is in range, so long routes past the camera show. */
        private boolean segmentNear(double x0, double y0, double z0, double x1, double y1, double z1) {
            double dx = x1 - x0;
            double dy = y1 - y0;
            double dz = z1 - z0;
            double lengthSquared = dx * dx + dy * dy + dz * dz;
            double t = lengthSquared == 0
                    ? 0
                    : Math.clamp(((cameraX - x0) * dx + (cameraY - y0) * dy + (cameraZ - z0) * dz) / lengthSquared, 0,
                            1);
            return near(x0 + dx * t, y0 + dy * t, z0 + dz * t);
        }

        private boolean near(double x, double y, double z) {
            double dx = x - cameraX;
            double dy = y - cameraY;
            double dz = z - cameraZ;
            return dx * dx + dy * dy + dz * dz <= maxDistanceSquared;
        }
    }
}
