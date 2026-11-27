package dev.silencebound.maxtech.storage.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.client.conduit.ConduitLabels;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import dev.silencebound.maxtech.lib.config.ClientConfig;
import dev.silencebound.maxtech.lib.facade.FacadeView;
import dev.silencebound.maxtech.lib.tool.ToolTags;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableModeMarks;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.part.PartLook;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.model.data.ModelData;

/**
 * Shows storage cables' connection modes while the player holds a wrench (anything in
 * {@code maxtech_lib:acts_as/wrench}): a one-pixel band in the mode's colour where an arm meets a block
 * ({@link CableModeMarks}). Without a wrench in either hand the client tick checks the two held stacks and does nothing
 * else. With Lib's {@code conduitColorLabels} on, each band is followed by the mode's glyph in the same colour
 * ({@link ConduitLabels#addModeMark}). With a wrench, it collects the marks of the cables within {@link #RADIUS} blocks
 * every {@link #SCAN_TICKS} ticks from the loaded chunks' block entities, so a mode the wrench just changed shows
 * within half a second, and each frame draws the collected bands in one {@code debugQuads} batch. Client thread only.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
final class CableModeClient {
    /** How far from the player marks are shown, per axis. */
    static final int RADIUS = 12;
    /** How often the marks are collected again while a wrench is held. */
    static final int SCAN_TICKS = 10;
    /** Six coordinates per box, block position included: a band, or a glyph cell flat on one face. */
    private static final DoubleArrayList BANDS = new DoubleArrayList();
    /** The colour of each box, with what to draw of it in its {@link #SHAPES} entry. */
    private static final IntArrayList COLOURS = new IntArrayList();
    /**
     * Per box: a band's side ({@code Direction#get3DDataValue}, its four long sides drawn), or {@link #FACE} plus a
     * direction's {@code get3DDataValue} for a glyph cell drawn as that one face.
     */
    private static final IntArrayList SHAPES = new IntArrayList();
    private static final int FACE = 6;
    /** Reused while collecting the glyph cells of one face; client thread only. */
    private static final List<ConduitQuad> GLYPH = new ArrayList<>();
    private static final BandRenderer RENDERER = new BandRenderer();
    /** Pixels per block unit. */
    private static final double PIXEL = 16.0;
    private static int countdown;

    private CableModeClient() {
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || !holdsWrench(player)) {
            if (!COLOURS.isEmpty()) {
                clear();
            }
            countdown = 0;
            return;
        }
        if (--countdown > 0) {
            return;
        }
        countdown = SCAN_TICKS;
        collect(level, player.blockPosition());
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        countdown = 0;
    }

    @SubscribeEvent
    static void submit(SubmitCustomGeometryEvent event) {
        if (COLOURS.isEmpty()) {
            return;
        }
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        RENDERER.camera(camera.x, camera.y, camera.z);
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        collector.submitCustomGeometry(event.getPoseStack(), RenderTypes.debugQuads(), RENDERER);
    }

    private static boolean holdsWrench(LocalPlayer player) {
        return player.getMainHandItem().is(ToolTags.ACTS_AS_WRENCH)
                || player.getOffhandItem().is(ToolTags.ACTS_AS_WRENCH);
    }

    private static void clear() {
        BANDS.clear();
        COLOURS.clear();
        SHAPES.clear();
    }

    /** Collects the marks of every storage cable within {@link #RADIUS} of {@code centre} in loaded chunks. */
    private static void collect(ClientLevel level, BlockPos centre) {
        clear();
        int minChunkX = (centre.getX() - RADIUS) >> 4;
        int maxChunkX = (centre.getX() + RADIUS) >> 4;
        int minChunkZ = (centre.getZ() - RADIUS) >> 4;
        int maxChunkZ = (centre.getZ() + RADIUS) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (entity instanceof ConduitBlockEntity conduit
                            && conduit.type() instanceof StorageCableConduitType type
                            && near(entity.getBlockPos(), centre)) {
                        add(conduit, type);
                    }
                }
            }
        }
    }

    private static boolean near(BlockPos pos, BlockPos centre) {
        return Math.abs(pos.getX() - centre.getX()) <= RADIUS && Math.abs(pos.getY() - centre.getY()) <= RADIUS
                && Math.abs(pos.getZ() - centre.getZ()) <= RADIUS;
    }

    private static void add(ConduitBlockEntity conduit, StorageCableConduitType type) {
        ModelData model = conduit.getModelData();
        ConduitRenderData data = model.get(ConduitBlockEntity.RENDER_DATA);
        if (data == null || (data.facade() != null && !FacadeView.conduitsShown())) {
            return;
        }
        // A face with a part shows the part, not an arm end.
        int parts = model.get(ConduitBlockEntity.STATE_RENDER_DATA) instanceof PartLook look ? look.mask() : 0;
        BlockPos pos = conduit.getBlockPos();
        boolean labels = ClientConfig.conduitColorLabels();
        List<ConduitFaceKey> faces = data.faces();
        for (int i = 0; i < faces.size(); i++) {
            ConduitFaceKey face = faces.get(i);
            Direction side = face.side();
            if (!CableModeMarks.marked(face.mode()) || (parts & 1 << side.get3DDataValue()) != 0) {
                continue;
            }
            int argb = CableModeMarks.argb(face.mode());
            box(pos, CableModeMarks.band(type.thickness(), side), 1, argb, side.get3DDataValue());
            if (labels) {
                GLYPH.clear();
                ConduitLabels.addModeMark(GLYPH, type.thickness(), side, face.mode(), CableModeMarks.GLYPH_FROM, argb);
                for (int j = 0; j < GLYPH.size(); j++) {
                    ConduitQuad cell = GLYPH.get(j);
                    box(pos, cell.box(), PIXEL, argb, FACE + cell.facing().get3DDataValue());
                }
            }
        }
    }

    /** Adds {@code box} ({@code unit} per block unit) at {@code pos}, drawn in {@code argb} as {@code shape} says. */
    private static void box(BlockPos pos, AABB box, double unit, int argb, int shape) {
        BANDS.add(pos.getX() + box.minX / unit);
        BANDS.add(pos.getY() + box.minY / unit);
        BANDS.add(pos.getZ() + box.minZ / unit);
        BANDS.add(pos.getX() + box.maxX / unit);
        BANDS.add(pos.getY() + box.maxY / unit);
        BANDS.add(pos.getZ() + box.maxZ / unit);
        COLOURS.add(argb);
        SHAPES.add(shape);
    }

    /** Writes the four long sides of every band, relative to the camera. One instance, reused every frame. */
    private static final class BandRenderer implements SubmitNodeCollector.CustomGeometryRenderer {
        private double cameraX;
        private double cameraY;
        private double cameraZ;

        void camera(double x, double y, double z) {
            cameraX = x;
            cameraY = y;
            cameraZ = z;
        }

        @Override
        public void render(PoseStack.Pose pose, VertexConsumer buffer) {
            for (int i = 0; i < COLOURS.size(); i++) {
                int at = i * 6;
                float x0 = (float) (BANDS.getDouble(at) - cameraX);
                float y0 = (float) (BANDS.getDouble(at + 1) - cameraY);
                float z0 = (float) (BANDS.getDouble(at + 2) - cameraZ);
                float x1 = (float) (BANDS.getDouble(at + 3) - cameraX);
                float y1 = (float) (BANDS.getDouble(at + 4) - cameraY);
                float z1 = (float) (BANDS.getDouble(at + 5) - cameraZ);
                int argb = COLOURS.getInt(i);
                int shape = SHAPES.getInt(i);
                if (shape >= FACE) {
                    face(buffer, pose, argb, Direction.from3DDataValue(shape - FACE), x0, y0, z0, x1, y1, z1);
                    continue;
                }
                Direction.Axis axis = Direction.from3DDataValue(shape).getAxis();
                if (axis != Direction.Axis.X) {
                    quad(buffer, pose, argb, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
                    quad(buffer, pose, argb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
                }
                if (axis != Direction.Axis.Y) {
                    quad(buffer, pose, argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
                    quad(buffer, pose, argb, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
                }
                if (axis != Direction.Axis.Z) {
                    quad(buffer, pose, argb, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
                    quad(buffer, pose, argb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
                }
            }
        }

        /** The flat box's one face, wound to face {@code facing} as the bands' sides are. */
        @SuppressWarnings("TooManyParameters") // a box's corners, kept primitive so a frame allocates nothing
        private static void face(VertexConsumer buffer, PoseStack.Pose pose, int argb, Direction facing, float x0,
                float y0, float z0, float x1, float y1, float z1) {
            switch (facing.getAxis()) {
                case X -> quad(buffer, pose, argb, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
                case Y -> quad(buffer, pose, argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
                case Z -> quad(buffer, pose, argb, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
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
