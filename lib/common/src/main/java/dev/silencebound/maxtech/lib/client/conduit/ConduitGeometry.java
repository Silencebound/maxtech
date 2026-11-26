package dev.silencebound.maxtech.lib.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.conduit.ConduitColours;
import dev.silencebound.maxtech.lib.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.conduit.ConduitShapes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The faces of a conduit's model parts, in pixels, before they become baked quads: the body ({@link #body}) and one
 * face decoration ({@link #face}). Pure geometry with no client classes, so it is unit-tested; the loader's conduit
 * model turns each {@link ConduitQuad} into one quad and caches the result per {@link ConduitBodyKey} or
 * {@link ConduitFaceKey}, so none of this runs during chunk meshing after the first sight of a key.
 *
 * <p>
 * Boxes follow {@link ConduitShapes}, so the model and the hit shape agree. A solid conduit is the core cube plus an
 * arm per side, all in one flat colour, and an arm ending in the open shows the end sprite. Faces that lie on the block
 * face are culled against a solid neighbour like any block face.
 */
public final class ConduitGeometry {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double EPSILON = 1.0E-4;
    /** Space between a ring and its mode glyph, in pixels. */
    private static final double RING_GAP = 0.25;

    private ConduitGeometry() {
    }

    /** The body of a conduit of {@code key}'s type in {@code look}. */
    public static List<ConduitQuad> body(ConduitLook look, ConduitBodyKey key) {
        return body(look, key, false);
    }

    /**
     * The body of a conduit of {@code key}'s type in {@code look}; with {@code labels} a dyed one also shows its dye's
     * letter ({@link ConduitLabels#addDyeLetter}).
     */
    public static List<ConduitQuad> body(ConduitLook look, ConduitBodyKey key, boolean labels) {
        List<ConduitQuad> out = new ArrayList<>();
        switch (look) {
            case ConduitLook.Solid solid -> {
                solidBody(out, solid, key.type(), key.colour(), key.arms(), key.rings());
                DyeColor colour = key.colour();
                if (labels && colour != null) {
                    int argb = ConduitColours.argb(ConduitLook.resolve(solid.colour(), key.type()), colour);
                    ConduitLabels.addDyeLetter(out, key.type().thickness(), key.arms(), colour,
                            ConduitLabels.ink(argb));
                }
            }
        }
        return out;
    }

    /**
     * One face decoration: a grey stub for a disabled face, else a connector ring in the mode's colour (ADR-0005).
     * Every look draws its rings and stubs the same way, so {@code look} does not change the result yet.
     */
    public static List<ConduitQuad> face(ConduitLook look, ConduitFaceKey key) {
        return face(look, key, false);
    }

    /**
     * One face decoration as {@link #face(ConduitLook, ConduitFaceKey)}; with {@code labels} a ring also has its mode's
     * glyph ({@link ConduitLabels#addModeMark}) on the arm just inside it.
     */
    public static List<ConduitQuad> face(ConduitLook look, ConduitFaceKey key, boolean labels) {
        List<ConduitQuad> out = new ArrayList<>();
        Direction side = key.side();
        float thickness = key.type().thickness();
        int argb = ConduitColours.faceArgb(key.mode());
        if (key.isStub()) {
            AABB stub = ConduitShapes.stub(thickness, side);
            for (Direction face : DIRECTIONS) {
                if (face != side.getOpposite()) {
                    out.add(new ConduitQuad(stub, face, ConduitTexture.FLAT, argb, false));
                }
            }
        } else {
            // The ring's outer face lies on the block face, so it is culled like one.
            AABB ring = ConduitShapes.ring(thickness, side);
            for (Direction face : DIRECTIONS) {
                out.add(new ConduitQuad(ring, face, ConduitTexture.FLAT, argb, face == side));
            }
            if (labels) {
                double depth = ring.max(side.getAxis()) - ring.min(side.getAxis());
                ConduitLabels.addModeMark(out, thickness, side, key.mode(), depth + RING_GAP, argb);
            }
        }
        return out;
    }

    private static void solidBody(List<ConduitQuad> out, ConduitLook.Solid look, ConduitType type,
            @Nullable DyeColor colour, int arms, int rings) {
        float thickness = type.thickness();
        int argb = ConduitColours.argb(ConduitLook.resolve(look.colour(), type), colour);
        int overlayArgb = ARGB.opaque(ConduitLook.resolve(look.overlayColour(), type));
        AABB core = ConduitShapes.core(thickness);
        for (Direction side : DIRECTIONS) {
            if (!ConduitConnections.isConnected(arms, side)) {
                out.add(new ConduitQuad(core, side, ConduitTexture.FLAT, argb, false));
            }
        }
        for (Direction side : DIRECTIONS) {
            if (!ConduitConnections.isConnected(arms, side)) {
                continue;
            }
            boolean ringed = ConduitConnections.isConnected(rings, side);
            AABB arm = ringed ? ConduitShapes.armBeforeRing(thickness, side) : ConduitShapes.arm(thickness, side);
            if (arm.max(side.getAxis()) - arm.min(side.getAxis()) < EPSILON) {
                continue;
            }
            for (Direction face : DIRECTIONS) {
                // The inner end lies on the core's face, which is left out instead; a ringed arm's outer end lies
                // against its ring.
                if (face == side.getOpposite() || (ringed && face == side)) {
                    continue;
                }
                if (face == side) {
                    out.add(new ConduitQuad(arm, face, ConduitTexture.END, argb, true));
                    if (look.overlay() != null) {
                        out.add(new ConduitQuad(arm, face, ConduitTexture.OVERLAY, overlayArgb, true));
                    }
                } else {
                    out.add(new ConduitQuad(arm, face, ConduitTexture.FLAT, argb, false));
                }
            }
        }
    }
}
