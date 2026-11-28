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
 * one on each face beside an edge. With a {@link ConduitLook.Shell#junctionCage junction cage}, a bend or branch also
 * frames each face of its core that no arm leaves from, and its arms' lines start {@value #STRIP_OFFSET} px past the
 * core, where the frame's strips end. Nothing is drawn on a face of the plane an arm leaves from: neither a frame on
 * the core's face, which would lie inside the arm's glass, nor a strip on another arm's side facing that way into the
 * crease. Both would be seen through the neighbouring glass and look like they float.
 */
public final class ConduitGeometry {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double EPSILON = 1.0E-4;
    /** Space between a ring and its mode glyph, in pixels. */
    private static final double RING_GAP = 0.25;
    /** How far a shell's border strips sit outside its glass, in pixels, so they never fight with it. */
    static final double STRIP_OFFSET = 0.02;
    /** How far a shell's label marks sit outside its glass, in pixels: in front of its strips. */
    static final double MARK_LIFT = STRIP_OFFSET + ConduitLabels.LIFT;
    private static final int WHITE = 0xFFFFFFFF;

    private ConduitGeometry() {
    }

    /**
     * The key Lib's own looks cache {@code key}'s body under: they draw neither the render value nor the contents in
     * the body (a shell's contents are a part of their own, {@link ConduitContentsGeometry}), so both are dropped and
     * conduits that differ only there share one part. Returns {@code key} itself when there is nothing to drop.
     */
    public static ConduitBodyKey cacheKey(ConduitBodyKey key) {
        if (key.renderValue() == 0 && key.contents() == 0) {
            return key;
        }
        return new ConduitBodyKey(key.type(), key.colour(), key.arms(), key.rings());
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
                    // The letter sits on the glass, mostly away from the borders, so its ink suits the glass.
                    ConduitLabels.addDyeLetter(out, key.type().thickness(), key.arms(), colour,
                            ConduitLabels.ink(WHITE), MARK_LIFT);
                }
            }
        }
        return out;
    }

    /**
     * One face decoration: a grey stub for a disabled face, else a connector ring in the mode's colour (ADR-0005).
     * Every look draws its rings and stubs the same way; only the mode glyphs of labels sit further out on a
     * {@link ConduitLook.Shell}, in front of its rim band.
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
                double lift = look instanceof ConduitLook.Shell ? MARK_LIFT : ConduitLabels.LIFT;
                ConduitLabels.addModeMark(out, thickness, side, key.mode(), depth + RING_GAP, argb, lift);
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
        boolean caged = look.junctionCage() && straight == null && Integer.bitCount(arms) >= 2;
        ConduitSilhouette.Outline outline = ConduitSilhouette.of(thickness, arms, rings, look.rim(), caged);
        List<ConduitSilhouette.Line> lines = outline.lines();
        double half = ConduitShapes.width(thickness) / 2;
        double[] widths = new double[lines.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = Math.min(half, lines.get(i).kind() == ConduitSilhouette.Kind.RIM ? look.rim() : look.border());
        }
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] < EPSILON) {
                continue;
            }
            ConduitSilhouette.Line line = lines.get(i);
            addStrip(out, core, arms, caged, lines, widths, i, line.first(), line.second(), borderArgb);
            addStrip(out, core, arms, caged, lines, widths, i, line.second(), line.first(), borderArgb);
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

    /**
     * The axis of the run passing the core's faces along {@code side}: an axis other than {@code side} with arms on
     * both ends, so a through run keeps its rows across the core, else one with an arm on one end; null if no arm
     * leaves the core off {@code side}.
     */
    private static Direction.@Nullable Axis runPassing(int arms, Direction.Axis side) {
        Direction.@Nullable Axis oneSided = null;
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis == side) {
                continue;
            }
            Direction negative = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
            boolean before = ConduitConnections.isConnected(arms, negative);
            boolean after = ConduitConnections.isConnected(arms, negative.getOpposite());
            if (before && after) {
                return axis;
            }
            if ((before || after) && oneSided == null) {
                oneSided = axis;
            }
        }
        return oneSided;
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
     * The strip of line {@code index} on face {@code on} beside the edge of {@code on} and {@code beside}: along the
     * line, {@code widths[index]} px in from the edge and reaching {@link #STRIP_OFFSET} past it, where the strip on
     * {@code beside} meets it. Where it meets a strip on the same face at a corner, its end moves by
     * {@link #cornerShift} so the two cover the corner once. In a {@code caged} outline no strip lies on a face that
     * faces the same way as one of the conduit's arms (a core face an arm leaves from, or an arm's side facing into the
     * crease), and an arm's line starts {@link #STRIP_OFFSET} past the core, where the frame's strips reach to.
     */
    @SuppressWarnings("TooManyParameters") // one strip of a line, picked from the outline; kept primitive
    private static void addStrip(List<ConduitQuad> out, AABB core, int arms, boolean caged,
            List<ConduitSilhouette.Line> lines, double[] widths, int index, Direction on, Direction beside, int argb) {
        ConduitSilhouette.Line line = lines.get(index);
        Direction.Axis axis = line.axis();
        if (caged && ConduitConnections.isConnected(arms, on)) {
            return;
        }
        Direction.Axis across = beside.getAxis();
        double width = widths[index];
        double from = line.from() - cornerShift(core, arms, caged, lines, widths, index, on, beside,
                Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE));
        double to = line.to() + cornerShift(core, arms, caged, lines, widths, index, on, beside,
                Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE));
        if (caged) {
            if (Math.abs(line.from() - core.max(axis)) < EPSILON) {
                from += STRIP_OFFSET;
            }
            if (Math.abs(line.to() - core.min(axis)) < EPSILON) {
                to -= STRIP_OFFSET;
            }
        }
        double edge = bound(core, beside);
        double inward = beside.getAxisDirection() == Direction.AxisDirection.POSITIVE ? -width : width;
        double outward = beside.getAxisDirection() == Direction.AxisDirection.POSITIVE ? STRIP_OFFSET : -STRIP_OFFSET;
        double min = Math.min(edge + inward, edge + outward);
        double max = Math.max(edge + inward, edge + outward);
        AABB pane = pane(core, on, axis, from, to, STRIP_OFFSET);
        out.add(new ConduitQuad(with(pane, across, min, max), on, ConduitTexture.FLAT, argb, false));
    }

    /**
     * How far the strip of line {@code index} on face {@code on} (beside {@code beside}) reaches past the line's
     * {@code end}, negative where it stops short. Where a strip of another line on the same face meets that end at a
     * right angle, the strip along the earlier axis covers the corner square and the other one starts after it: at a
     * convex corner (an outer corner of the face) the earlier one reaches {@link #STRIP_OFFSET} further and the later
     * one stops the earlier one's width short; at a concave one (the inner corner of a bend or branch, on the core's
     * face) the earlier one reaches on by the later one's width and the later one stops {@link #STRIP_OFFSET} short, as
     * it also does where both lines stop at creases beside an arm on {@code on}. 0 where no strip meets the end. A
     * {@code caged} outline has no concave corners: its strips only meet at the corners of the core's frames.
     */
    @SuppressWarnings("TooManyParameters") // one end of one strip, looked up in the outline; kept primitive
    private static double cornerShift(AABB core, int arms, boolean caged, List<ConduitSilhouette.Line> lines,
            double[] widths, int index, Direction on, Direction beside, Direction end) {
        ConduitSilhouette.Line line = lines.get(index);
        double at = end.getAxisDirection() == Direction.AxisDirection.POSITIVE ? line.to() : line.from();
        double edge = bound(core, beside);
        for (int i = 0; i < widths.length; i++) {
            ConduitSilhouette.Line other = lines.get(i);
            if (i == index || widths[i] < EPSILON || other.axis() != beside.getAxis()) {
                continue;
            }
            Direction otherBeside;
            if (other.first() == on) {
                otherBeside = other.second();
            } else if (other.second() == on) {
                otherBeside = other.first();
            } else {
                continue;
            }
            if (Math.abs(bound(core, otherBeside) - at) > EPSILON) {
                continue;
            }
            // Convex: the other strip lies on the face's edge beyond this end and runs inwards from this strip's edge.
            // Concave: it lies on the far side of this end and runs outwards, along an arm on beside.
            boolean convex = otherBeside == end;
            if (caged && !convex) {
                continue;
            }
            Direction away = convex ? beside.getOpposite() : beside;
            double otherStart = away.getAxisDirection() == Direction.AxisDirection.POSITIVE ? other.from() : other.to();
            if (Math.abs(otherStart - edge) > EPSILON) {
                continue;
            }
            boolean first = line.axis().compareTo(other.axis()) < 0;
            if (convex) {
                return first ? STRIP_OFFSET : -widths[i];
            }
            if (ConduitConnections.isConnected(arms, on)) {
                // No core face on this side: both strips stop at creases, and only their offsets meet.
                return first ? 0 : -STRIP_OFFSET;
            }
            return first ? widths[i] : -STRIP_OFFSET;
        }
        return 0;
    }

    /** Where the glass's face {@code face} lies along its axis. */
    private static double bound(AABB core, Direction face) {
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? core.max(face.getAxis())
                : core.min(face.getAxis());
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
