package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A shell's {@link ConduitLook.Shell#tierStripe}: flat stripes on the set-in glass beside every frame bar, and nothing
 * else changed.
 */
class ConduitTierStripeTest {
    private static final Identifier GLASS = Identifier.fromNamespaceAndPath("test", "block/glass");
    private static final int STRIPE = 0xD6DBE0;
    private static final double EPS = 1.0E-4;
    /** Spacing of the sample points along an edge, in pixels. */
    private static final double STEP = 0.125;
    private static final ConduitLook.Shell FRAMED = ConduitLook.shell(GLASS).withBorderColour(0x2C3238).withBorder(1)
            .withFrameBars();

    /**
     * With the stripe the body is the stripeless body plus stripes at its end; each stripe lies flat on a glass face's
     * plane, {@link ConduitGeometry#STRIP_OFFSET} px out, inside that face, opaque in the stripe colour; every point of
     * the glass within 1 px of an edge with a bar or collar beside it is covered exactly once, and no other point is.
     */
    @ParameterizedTest(name = "thickness {0}, arms {1}, rings {2}")
    @CsvSource({"0.5, we, ''", "0.5, es, ''", "0.5, wes, ''", "0.5, nswe, ''", "0.5, ues, ''", "0.5, e, ''",
            "0.5, '', ''", "0.5, we, w", "0.5, wes, es", "0.375, nswe, ''", "0.375, du, ''"})
    void stripesLieBesideEveryBar(float thickness, String arms, String rings) {
        ConduitType type = type(thickness);
        ConduitBodyKey key = new ConduitBodyKey(type, null, mask(arms), mask(rings));
        List<ConduitQuad> plain = ConduitGeometry.body(FRAMED, key);
        List<ConduitQuad> striped = ConduitGeometry.body(FRAMED.withTierStripe(STRIPE), key);
        assertEquals(plain, striped.subList(0, plain.size()), "the body before the stripes changed");
        List<ConduitQuad> stripes = striped.subList(plain.size(), striped.size());
        assertFalse(stripes.isEmpty(), "no stripes");
        List<ConduitQuad> glass = new ArrayList<>();
        List<AABB> frame = new ArrayList<>();
        for (ConduitQuad quad : plain) {
            if (Objects.equals(GLASS, quad.sprite())) {
                glass.add(quad);
            } else {
                frame.add(quad.box());
            }
        }
        for (ConduitQuad stripe : stripes) {
            assertEquals(0xFF000000 | STRIPE, stripe.argb(), "colour of " + stripe);
            assertEquals(null, stripe.sprite(), "flat");
            assertFalse(stripe.culled());
            Direction.Axis normal = stripe.facing().getAxis();
            assertEquals(0, stripe.box().max(normal) - stripe.box().min(normal), EPS, "flat: " + stripe);
            assertTrue(glass.stream().anyMatch(pane -> onPane(stripe, pane)), "on no glass face: " + stripe);
        }
        int sampled = 0;
        for (ConduitQuad pane : glass) {
            Direction.Axis[] axes = inPlane(pane.facing());
            AABB box = pane.box();
            for (double a = box.min(axes[0]) + STEP / 2; a < box.max(axes[0]); a += STEP) {
                for (double b = box.min(axes[1]) + STEP / 2; b < box.max(axes[1]); b += STEP) {
                    boolean wanted = besideBar(box, pane.facing(), axes, a, b, frame);
                    int covering = 0;
                    for (ConduitQuad stripe : stripes) {
                        if (onPane(stripe, pane) && contains(stripe.box(), axes, a, b)) {
                            covering++;
                        }
                    }
                    assertEquals(wanted ? 1 : 0, covering, "stripes over " + a + ", " + b + " on " + pane);
                    sampled += wanted ? 1 : 0;
                }
            }
        }
        assertTrue(sampled > 0, "no point beside a bar");
    }

    /** A straight run: one stripe along each long edge of each of its four glass faces. */
    @Test
    void straightHasTwoStripesPerFace() {
        ConduitBodyKey key = new ConduitBodyKey(type(0.5F), null, mask("we"), 0);
        int plain = ConduitGeometry.body(FRAMED, key).size();
        List<ConduitQuad> stripes = ConduitGeometry.body(FRAMED.withTierStripe(STRIPE), key).subList(plain,
                ConduitGeometry.body(FRAMED.withTierStripe(STRIPE), key).size());
        assertEquals(8, stripes.size());
        for (ConduitQuad stripe : stripes) {
            assertEquals(16, stripe.box().max(Direction.Axis.X) - stripe.box().min(Direction.Axis.X), EPS, "length");
        }
    }

    /** Without the option, or without frame bars, nothing is drawn in a stripe. */
    @ParameterizedTest(name = "arms {0}")
    @CsvSource({"we", "es", "wes", "nswe", "''"})
    void noStripesWithoutTheOption(String arms) {
        ConduitBodyKey key = new ConduitBodyKey(type(0.5F), null, mask(arms), 0);
        assertTrue(ConduitGeometry.body(FRAMED, key).stream().noneMatch(q -> q.argb() == (0xFF000000 | STRIPE)));
        ConduitLook.Shell lines = ConduitLook.shell(GLASS).withBorderColour(0x2C3238).withBorder(1);
        assertEquals(ConduitGeometry.body(lines, key), ConduitGeometry.body(lines.withTierStripe(STRIPE), key),
                "a shell without frame bars draws no stripe");
    }

    private static boolean onPane(ConduitQuad stripe, ConduitQuad pane) {
        Direction face = pane.facing();
        if (stripe.facing() != face) {
            return false;
        }
        double sign = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        double plane = ConduitGeometry.bound(pane.box(), face) + sign * ConduitGeometry.STRIP_OFFSET;
        if (Math.abs(stripe.box().min(face.getAxis()) - plane) > EPS) {
            return false;
        }
        for (Direction.Axis axis : inPlane(face)) {
            if (stripe.box().min(axis) < pane.box().min(axis) - EPS
                    || stripe.box().max(axis) > pane.box().max(axis) + EPS) {
                return false;
            }
        }
        return true;
    }

    /** Whether (a, b) on the glass lies within 1 px of an edge with a frame box just past it on the glass plane. */
    private static boolean besideBar(AABB box, Direction face, Direction.Axis[] axes, double a, double b,
            List<AABB> frame) {
        double sign = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        double depth = ConduitGeometry.bound(box, face) + sign * 0.01;
        for (int i = 0; i < 2; i++) {
            Direction.Axis edge = axes[i];
            Direction.Axis along = axes[1 - i];
            double at = i == 0 ? a : b;
            double on = i == 0 ? b : a;
            double width = Math.min(1, (box.max(edge) - box.min(edge)) / 2);
            double[] probes = {box.min(edge) - 0.01, box.max(edge) + 0.01};
            boolean[] near = {at < box.min(edge) + width, at > box.max(edge) - width};
            for (int end = 0; end < 2; end++) {
                if (!near[end]) {
                    continue;
                }
                for (AABB bar : frame) {
                    if (inside(bar, edge, probes[end]) && inside(bar, face.getAxis(), depth)
                            && inside(bar, along, on)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean contains(AABB box, Direction.Axis[] axes, double a, double b) {
        return inside(box, axes[0], a) && inside(box, axes[1], b);
    }

    private static boolean inside(AABB box, Direction.Axis axis, double value) {
        return box.min(axis) < value && value < box.max(axis);
    }

    private static Direction.Axis[] inPlane(Direction face) {
        return java.util.Arrays.stream(Direction.Axis.values()).filter(axis -> axis != face.getAxis())
                .toArray(Direction.Axis[]::new);
    }

    private static ConduitType type(float thickness) {
        return new ConduitType(Identifier.fromNamespaceAndPath("test", "net"), "test", 0x4078D8, thickness,
                () -> (ItemLike) () -> {
                    throw new UnsupportedOperationException();
                });
    }

    private static int mask(String sides) {
        int mask = 0;
        for (int i = 0; i < sides.length(); i++) {
            mask |= ConduitConnections.bit(switch (sides.charAt(i)) {
                case 'd' -> Direction.DOWN;
                case 'u' -> Direction.UP;
                case 'n' -> Direction.NORTH;
                case 's' -> Direction.SOUTH;
                case 'w' -> Direction.WEST;
                case 'e' -> Direction.EAST;
                default -> throw new IllegalArgumentException(sides);
            });
        }
        return mask;
    }
}
