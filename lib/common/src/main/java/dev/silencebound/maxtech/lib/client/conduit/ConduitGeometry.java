package dev.silencebound.maxtech.lib.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitColours;
import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture;
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
 *
 * <p>
 * A shell conduit's glass is laid out the same way (a straight run is one box), every face translucent and showing the
 * shell sprite projected onto the block ({@link ConduitUvs#projected}), turned so its rows run along the tube. Its
 * border lines and rims ({@link ConduitSilhouette}) are opaque flat strips {@value #STRIP_OFFSET} px outside the glass,
 * one on each face beside an edge.
 */
public final class ConduitGeometry {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double EPSILON = 1.0E-4;
    /** Space between a ring and its mode glyph, in pixels. */
    private static final double RING_GAP = 0.25;
    /** How far a shell's border strips sit outside its glass, in pixels, so they never fight with it. */
    static final double STRIP_OFFSET = 0.02;
    private static final int WHITE = 0xFFFFFFFF;

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
            case ConduitLook.Shell shell -> {
                int argb = ConduitColours.argb(ConduitLook.resolve(shell.borderColour(), key.type()), key.colour());
                shellBody(out, shell, key.type().thickness(), key.arms(), key.rings(), argb);
                DyeColor colour = key.colour();
                if (labels && colour != null) {
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

    private static void shellBody(List<ConduitQuad> out, ConduitLook.Shell look, float thickness, int arms, int rings,
            int borderArgb) {
        AABB core = ConduitShapes.core(thickness);
        Direction.Axis straight = straightAxis(arms);
        if (straight != null) {
            Direction negative = Direction.fromAxisAndDirection(straight, Direction.AxisDirection.NEGATIVE);
            AABB box = core.minmax(glassArm(thickness, negative, rings))
                    .minmax(glassArm(thickness, negative.getOpposite(), rings));
            for (Direction face : DIRECTIONS) {
                if (face.getAxis() != straight) {
                    out.add(glass(look, box, face, straight));
                }
            }
        } else {
            for (Direction side : DIRECTIONS) {
                if (!ConduitConnections.isConnected(arms, side)) {
                    out.add(glass(look, core, side, runPassing(arms, side.getAxis())));
                }
            }
            for (Direction side : DIRECTIONS) {
                if (!ConduitConnections.isConnected(arms, side)) {
                    continue;
                }
                AABB arm = glassArm(thickness, side, rings);
                if (arm.max(side.getAxis()) - arm.min(side.getAxis()) < EPSILON) {
                    continue;
                }
                // Neither end: the inner one lies on the core, the outer one on the next tube's glass or the ring.
                for (Direction face : DIRECTIONS) {
                    if (face.getAxis() != side.getAxis()) {
                        out.add(glass(look, arm, face, side.getAxis()));
                    }
                }
            }
        }
        ConduitSilhouette.Outline outline = ConduitSilhouette.of(thickness, arms, rings, look.rim());
        double half = ConduitShapes.width(thickness) / 2;
        for (ConduitSilhouette.Line line : outline.lines()) {
            double width = Math.min(half, line.kind() == ConduitSilhouette.Kind.RIM ? look.rim() : look.border());
            if (width < EPSILON) {
                continue;
            }
            addStrip(out, core, line.axis(), line.from(), line.to(), line.first(), line.second(), width, borderArgb);
            addStrip(out, core, line.axis(), line.from(), line.to(), line.second(), line.first(), width, borderArgb);
        }
        for (ConduitSilhouette.Band band : outline.bands()) {
            Direction.Axis axis = band.side().getAxis();
            for (Direction face : DIRECTIONS) {
                if (face.getAxis() != axis) {
                    out.add(new ConduitQuad(pane(core, face, axis, band.from(), band.to(), STRIP_OFFSET), face,
                            ConduitTexture.FLAT, borderArgb, false));
                }
            }
        }
    }

    /** The one axis both arms of a straight conduit run along, or null if the arms are not exactly one pair. */
    private static Direction.@Nullable Axis straightAxis(int arms) {
        for (Direction.Axis axis : Direction.Axis.values()) {
            Direction negative = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
            if (arms == (ConduitConnections.bit(negative) | ConduitConnections.bit(negative.getOpposite()))) {
                return axis;
            }
        }
        return null;
    }

    private static AABB glassArm(float thickness, Direction side, int rings) {
        return ConduitConnections.isConnected(rings, side)
                ? ConduitShapes.armBeforeRing(thickness, side)
                : ConduitShapes.arm(thickness, side);
    }

    /** The axis of an arm not along {@code side}, or null if no run passes that side of the core. */
    private static Direction.@Nullable Axis runPassing(int arms, Direction.Axis side) {
        for (Direction direction : DIRECTIONS) {
            if (direction.getAxis() != side && ConduitConnections.isConnected(arms, direction)) {
                return direction.getAxis();
            }
        }
        return null;
    }

    /**
     * A glass face, its sprite turned so that the sprite's rows (its u axis) run along {@code run}, the axis of the
     * tube it belongs to, and a shell's streaks follow the tube; unturned without a run.
     */
    private static ConduitQuad glass(ConduitLook.Shell look, AABB box, Direction face, Direction.@Nullable Axis run) {
        int turns = 0;
        if (run != null) {
            // flowTurns puts the sprite's v along the run; one more quarter puts its u there
            turns = (ConduitUvs.flowTurns(face, Direction.fromAxisAndDirection(run, Direction.AxisDirection.POSITIVE))
                    .orElse(3) + 1) % 4;
        }
        return new ConduitQuad(box, face, ConduitTexture.FLAT, WHITE, false, ConduitLayer.TRANSLUCENT, look.shell(),
                ConduitUvs.projected(box, face, turns));
    }

    /**
     * The strip on face {@code on} beside the edge of {@code on} and {@code beside}: from {@code from} to {@code to}
     * along {@code axis}, {@code width} px in from the edge and reaching {@link #STRIP_OFFSET} past it, where the strip
     * on {@code beside} meets it.
     */
    private static void addStrip(List<ConduitQuad> out, AABB core, Direction.Axis axis, double from, double to,
            Direction on, Direction beside, double width, int argb) {
        Direction.Axis across = beside.getAxis();
        double edge = beside.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? core.max(across)
                : core.min(across);
        double inward = beside.getAxisDirection() == Direction.AxisDirection.POSITIVE ? -width : width;
        double outward = beside.getAxisDirection() == Direction.AxisDirection.POSITIVE ? STRIP_OFFSET : -STRIP_OFFSET;
        double min = Math.min(edge + inward, edge + outward);
        double max = Math.max(edge + inward, edge + outward);
        AABB pane = pane(core, on, axis, from, to, STRIP_OFFSET);
        out.add(new ConduitQuad(with(pane, across, min, max), on, ConduitTexture.FLAT, argb, false));
    }

    /**
     * A pane flat on {@code face} of the glass, {@code offset} px outside it, from {@code from} to {@code to} along
     * {@code axis} and across the whole face plus {@code offset} on either side.
     */
    private static AABB pane(AABB core, Direction face, Direction.Axis axis, double from, double to, double offset) {
        Direction.Axis normal = face.getAxis();
        double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? core.max(normal) + offset
                : core.min(normal) - offset;
        AABB box = new AABB(core.minX - offset, core.minY - offset, core.minZ - offset, core.maxX + offset,
                core.maxY + offset, core.maxZ + offset);
        return with(with(box, normal, plane, plane), axis, from, to);
    }

    private static AABB with(AABB box, Direction.Axis axis, double min, double max) {
        return switch (axis) {
            case X -> new AABB(min, box.minY, box.minZ, max, box.maxY, box.maxZ);
            case Y -> new AABB(box.minX, min, box.minZ, box.maxX, max, box.maxZ);
            case Z -> new AABB(box.minX, box.minY, min, box.maxX, box.maxY, max);
        };
    }
}
