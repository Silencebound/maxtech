package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture;
import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.ConduitModelHook;
import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.RegisterConduitModelsEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import dev.silencebound.maxtech.lib.MaxtechLib;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.jspecify.annotations.Nullable;

/**
 * The model hook of {@link ConduitApiGameTests#GLASS_TUBE}, on the NeoForge api alone as Farms' lab tube will be: a
 * clear glass tube with an original look (textures under {@code textures/block/glass_tube_*}), with a slightly smaller
 * translucent core inside in the render value's colour (an RGB; 0 leaves the tube empty). Set a tube's value in the dev
 * client with {@code /maxtech_lib_demo tube <pos> <rrggbb>}. Part of the GameTest source set, so it is in dev runs
 * only.
 * <p>
 * The body is laid out here from the key rather than taken from Lib's parts, because glass has no frame on a face of
 * its own. Side faces use a texture whose edge lines run along the tube ({@code _h} where the run is along a face's u
 * axis, {@code _v} where along its v axis), so the lines continue over joints without crossing them. A joint with
 * another tube is left open. A rim (an outer edge band and a lighter inner band, 1 px each) marks where a tube meets a
 * block, drawn on the arm just inside Lib's connector ring, and an end cap marks a side nobody connects to. Lib's rings
 * and stubs stay as they are.
 */
@EventBusSubscriber(modid = ConduitApiGameTests.MOD_ID, value = Dist.CLIENT)
public final class GlassTubeDemoClient {
    private static final Identifier GLASS_H = MaxtechLib.id("block/glass_tube_h");
    private static final Identifier GLASS_V = MaxtechLib.id("block/glass_tube_v");
    private static final Identifier END = MaxtechLib.id("block/glass_tube_end");
    private static final Identifier RIM_EDGE = MaxtechLib.id("block/glass_tube_rim_edge");
    private static final Identifier RIM_INNER = MaxtechLib.id("block/glass_tube_rim_inner");
    // How far the contents sit inside the glass, in pixels.
    private static final double INSET = 0.5;
    private static final int CONTENTS_ALPHA = 0xB0;
    private static final double EPSILON = 1.0E-4;

    private GlassTubeDemoClient() {
    }

    @SubscribeEvent
    static void register(RegisterConduitModelsEvent event) {
        event.register(ConduitApiGameTests.GLASS_TUBE, new ConduitModelHook() {
            @Override
            public Set<Identifier> sprites() {
                return Set.of(GLASS_H, GLASS_V, END, RIM_EDGE, RIM_INNER);
            }

            @Override
            public List<ConduitQuad> body(ConduitLook look, ConduitBodyKey key, boolean labels) {
                return GlassTubeDemoClient.body(key);
            }
        });
    }

    static List<ConduitQuad> body(ConduitBodyKey key) {
        float thickness = key.type().thickness();
        List<ConduitQuad> quads = new ArrayList<>();
        AABB core = ConduitShapes.core(thickness);
        for (Direction side : Direction.values()) {
            if (ConduitConnections.isConnected(key.arms(), side)) {
                continue;
            }
            // A side of the core is a long side of the run that passes it, or the end of a run along its own axis.
            Direction.Axis run = runPassing(key.arms(), side.getAxis());
            quads.add(run == null ? glass(core, side, END) : glass(core, side, run));
            addContents(quads, core, side, key);
        }
        for (Direction side : Direction.values()) {
            if (!ConduitConnections.isConnected(key.arms(), side)) {
                continue;
            }
            boolean ringed = ConduitConnections.isConnected(key.rings(), side);
            AABB arm = ringed ? ConduitShapes.armBeforeRing(thickness, side) : ConduitShapes.arm(thickness, side);
            Direction.Axis axis = side.getAxis();
            double length = arm.max(axis) - arm.min(axis);
            if (length < EPSILON) {
                continue;
            }
            // Where the arm meets a block, its last two pixels (before Lib's ring) are the rim: an edge band, then a
            // lighter one inside it. Where it meets another tube it stays open and the glass runs on.
            double band = ringed ? Math.min(1.0, length / 2) : 0;
            for (Direction face : Direction.values()) {
                if (face.getAxis() == axis) {
                    // The inner end lies on the core; the outer one is open, or under the ring.
                    continue;
                }
                if (length - 2 * band > EPSILON) {
                    quads.add(glass(range(arm, side, 2 * band, length), face, axis));
                }
                if (ringed) {
                    quads.add(ConduitQuad.ofSprite(range(arm, side, band, 2 * band), face, RIM_INNER, 0xFFFFFFFF, false,
                            ConduitLayer.TRANSLUCENT));
                    quads.add(ConduitQuad.ofSprite(range(arm, side, 0, band), face, RIM_EDGE, 0xFFFFFFFF, false,
                            ConduitLayer.TRANSLUCENT));
                }
                addContents(quads, arm, face, key);
            }
        }
        return quads;
    }

    /** The axis of a connected arm that is not along {@code side}, or null if no run passes that side of the core. */
    private static Direction.@Nullable Axis runPassing(int arms, Direction.Axis side) {
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() != side && ConduitConnections.isConnected(arms, direction)) {
                return direction.getAxis();
            }
        }
        return null;
    }

    /** The side face of a box in a run along {@code axis}: glass whose edge lines run along it. */
    private static ConduitQuad glass(AABB box, Direction face, Direction.Axis axis) {
        // A face's texture u runs along x on the up, down, north and south faces and along z on the east and west ones;
        // v runs along z on up and down and along y on the others.
        Direction.Axis u = face.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        return glass(box, face, axis == u ? GLASS_H : GLASS_V);
    }

    private static ConduitQuad glass(AABB box, Direction face, Identifier sprite) {
        return ConduitQuad.ofSprite(box, face, sprite, 0xFFFFFFFF, false, ConduitLayer.TRANSLUCENT);
    }

    /** The coloured contents behind one face of {@code box}, if the tube has a render value. */
    private static void addContents(List<ConduitQuad> quads, AABB box, Direction face, ConduitBodyKey key) {
        if (key.renderValue() != 0) {
            int argb = CONTENTS_ALPHA << 24 | (key.renderValue() & 0xFFFFFF);
            quads.add(new ConduitQuad(inset(box), face, ConduitTexture.FLAT, argb, false, ConduitLayer.TRANSLUCENT));
        }
    }

    /** The slice of an arm between {@code near} and {@code far} px from its outer end (the one away from the core). */
    private static AABB range(AABB arm, Direction side, double near, double far) {
        Direction.Axis axis = side.getAxis();
        double min = arm.min(axis);
        double max = arm.max(axis);
        return side.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? with(arm, axis, max - far, max - near)
                : with(arm, axis, min + near, min + far);
    }

    private static AABB with(AABB box, Direction.Axis axis, double from, double to) {
        return switch (axis) {
            case X -> new AABB(from, box.minY, box.minZ, to, box.maxY, box.maxZ);
            case Y -> new AABB(box.minX, from, box.minZ, box.maxX, to, box.maxZ);
            case Z -> new AABB(box.minX, box.minY, from, box.maxX, box.maxY, to);
        };
    }

    /** {@code box} shrunk by {@link #INSET} on every side that is not on the block's face. */
    private static AABB inset(AABB box) {
        return new AABB(in(box.minX, 1), in(box.minY, 1), in(box.minZ, 1), in(box.maxX, -1), in(box.maxY, -1),
                in(box.maxZ, -1));
    }

    private static double in(double bound, int direction) {
        return bound <= 0 || bound >= 16 ? bound : bound + direction * INSET;
    }
}
