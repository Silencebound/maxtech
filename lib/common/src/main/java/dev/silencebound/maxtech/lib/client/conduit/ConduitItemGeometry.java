package dev.silencebound.maxtech.lib.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * The item piece of a shell conduit: a straight east-west run across the whole block whose two ends look like an open
 * end in the world (bars around the end face, the end glass set in between them, tier stripes beside them), with no
 * contents, labels or rings. Item models are static, so this is what an addon's datagen writes for a shell conduit's
 * item.
 *
 * <p>
 * Built from the bodies the block model draws, not drawn anew: the west half is the body of a tube whose only arm runs
 * east (its open end on the west), cut where it has run on for half a block and moved west so the open end lies on the
 * block face; the east half is its mirror. Past the open end's bars both halves have the cross-section of a straight
 * run, so they meet at the block's centre, where faces that continue each other are joined. Sprite coordinates are
 * projected again from where each face ends up, so the glass and bar sprites run on across the join. Pure geometry.
 */
public final class ConduitItemGeometry {
    private static final double EPSILON = 1.0E-4;
    private static final double CENTRE = 8.0;

    private ConduitItemGeometry() {
    }

    /** The faces of {@code type}'s item piece in {@code look}, its border colour undyed. */
    public static List<ConduitQuad> shellPiece(ConduitLook.Shell look, ConduitType type) {
        double lo = ConduitShapes.core(type.thickness()).minX;
        List<ConduitQuad> west = ConduitGeometry.body(look,
                new ConduitBodyKey(type, null, ConduitConnections.bit(Direction.EAST), 0));
        List<ConduitQuad> east = ConduitGeometry.body(look,
                new ConduitBodyKey(type, null, ConduitConnections.bit(Direction.WEST), 0));
        List<ConduitQuad> out = new ArrayList<>();
        // The west half: the open end at lo, cut half a block further east, moved by -lo onto 0 to 8.
        for (ConduitQuad quad : west) {
            add(out, quad, lo, CENTRE + lo, -lo);
        }
        List<ConduitQuad> eastHalf = new ArrayList<>();
        for (ConduitQuad quad : east) {
            add(eastHalf, quad, CENTRE - lo, 16 - lo, lo);
        }
        for (ConduitQuad quad : eastHalf) {
            if (!joined(out, quad)) {
                out.add(quad);
            }
        }
        return out;
    }

    /**
     * Adds {@code quad} cut to {@code from} to {@code to} along X and moved by {@code shift}, unless nothing is left.
     */
    private static void add(List<ConduitQuad> out, ConduitQuad quad, double from, double to, double shift) {
        AABB box = quad.box();
        if (quad.facing().getAxis() == Direction.Axis.X) {
            double plane = ConduitGeometry.bound(box, quad.facing());
            if (plane < from - EPSILON || plane > to + EPSILON) {
                return;
            }
        } else if (box.maxX < from + EPSILON || box.minX > to - EPSILON) {
            return;
        }
        double min = Math.max(box.minX, from);
        double max = Math.min(box.maxX, to);
        out.add(moved(quad, ConduitGeometry.with(box, Direction.Axis.X, min + shift, max + shift)));
    }

    /** Whether {@code quad} continues a face of {@code out} across the centre; if so that face now covers both. */
    private static boolean joined(List<ConduitQuad> out, ConduitQuad quad) {
        if (quad.facing().getAxis() == Direction.Axis.X || Math.abs(quad.box().minX - CENTRE) > EPSILON) {
            return false;
        }
        for (int i = 0; i < out.size(); i++) {
            ConduitQuad other = out.get(i);
            if (Math.abs(other.box().maxX - CENTRE) < EPSILON && continues(other, quad)) {
                out.set(i, moved(other, other.box().minmax(quad.box())));
                return true;
            }
        }
        return false;
    }

    private static boolean continues(ConduitQuad a, ConduitQuad b) {
        AABB p = a.box();
        AABB q = b.box();
        return a.facing() == b.facing() && a.texture() == b.texture() && a.argb() == b.argb() && a.layer() == b.layer()
                && a.lightEmission() == b.lightEmission() && Objects.equals(a.sprite(), b.sprite())
                && turns(a) == turns(b) && Math.abs(p.minY - q.minY) < EPSILON && Math.abs(p.maxY - q.maxY) < EPSILON
                && Math.abs(p.minZ - q.minZ) < EPSILON && Math.abs(p.maxZ - q.maxZ) < EPSILON;
    }

    /** {@code quad} on {@code box}, never culled, its projected sprite coordinates projected from {@code box}. */
    private static ConduitQuad moved(ConduitQuad quad, AABB box) {
        ConduitUv uv = quad.uv();
        return new ConduitQuad(box, quad.facing(), quad.texture(), quad.argb(), false, quad.layer(), quad.sprite(),
                uv == null ? null : ConduitUvs.projected(box, quad.facing(), uv.quarterTurns()), quad.lightEmission());
    }

    /** The quad's sprite turns, -1 for a quad that shows its whole sprite. */
    private static int turns(ConduitQuad quad) {
        ConduitUv uv = quad.uv();
        return uv == null ? -1 : uv.quarterTurns();
    }
}
