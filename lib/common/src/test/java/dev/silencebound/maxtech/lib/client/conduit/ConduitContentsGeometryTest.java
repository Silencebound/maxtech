package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import com.google.common.base.Splitter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConduitContentsGeometryTest {
    private static final double EPS = 1.0E-6;
    private static final Identifier STILL = Identifier.fromNamespaceAndPath("test", "block/fluid_still");
    private static final Identifier FLOWING = Identifier.fromNamespaceAndPath("test", "block/fluid_flow");
    private static final ConduitContentsGeometry.Paint PAINT = new ConduitContentsGeometry.Paint(STILL, FLOWING,
            0xFF3F76E4, 15);
    private static final ConduitLook.Shell SHELL = ConduitLook
            .shell(Identifier.fromNamespaceAndPath("test", "block/glass"));
    // 6 px wide: the glass is 5 to 11, the contents at the default inset 5.5 to 10.5.
    private static final ConduitType TYPE = new ConduitType(Identifier.fromNamespaceAndPath("test", "net"), "test",
            0x4078D8, 0.375F, () -> (ItemLike) () -> {
                throw new UnsupportedOperationException();
            });
    private static final double LOW = 5.5;
    private static final double HIGH = 10.5;
    private static final int FLUID = 5;

    /**
     * The flow on every contents face, per piece, written out by hand: {@code piece.face=flow}, the piece {@code c} for
     * the core or an arm's side letter, the flow the direction the flowing sprite's +v (vanilla's flowing sprites move
     * towards +v) runs along in the world. Every other face shows the still sprite. The table is the convention: fluid
     * runs from the upstream side downstream on every face of every piece.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "straight from west | we | w | w.up=e w.down=e w.north=e w.south=e c.up=e c.down=e c.north=e c.south=e"
                    + " e.up=e e.down=e e.north=e e.south=e",
            "straight from east | we | e | w.up=w w.down=w w.north=w w.south=w c.up=w c.down=w c.north=w c.south=w"
                    + " e.up=w e.down=w e.north=w e.south=w",
            "bend from north | ne | n | n.up=s n.down=s n.west=s n.east=s c.up=s c.down=s c.west=s c.south=e"
                    + " e.up=e e.down=e e.north=e e.south=e",
            "bend from east | ne | e | e.up=w e.down=w e.north=w e.south=w c.up=w c.down=w c.south=w c.west=n"
                    + " n.up=n n.down=n n.west=n n.east=n",
            "T from west | wen | w | w.up=e w.down=e w.north=e w.south=e c.up=e c.down=e c.south=e"
                    + " e.up=e e.down=e e.north=e e.south=e n.up=n n.down=n n.west=n n.east=n",
            "vertical from above | du | u | u.north=d u.south=d u.west=d u.east=d c.north=d c.south=d c.west=d"
                    + " c.east=d d.north=d d.south=d d.west=d d.east=d",
            "vertical from below | du | d | u.north=u u.south=u u.west=u u.east=u c.north=u c.south=u c.west=u"
                    + " c.east=u d.north=u d.south=u d.west=u d.east=u",
            "vertical bend from above | ue | u | u.north=d u.south=d u.west=d u.east=d c.north=d c.south=d"
                    + " c.west=d c.east=d c.down=e e.up=e e.down=e e.north=e e.south=e",
            "vertical bend from east | ue | e | e.up=w e.down=w e.north=w e.south=w c.north=w c.south=w c.down=w"
                    + " c.west=u c.east=u u.north=u u.south=u u.west=u u.east=u"})
    void flowRunsDownstreamOnEveryFace(String name, String arms, String upstream, String table) {
        Map<String, String> expected = new TreeMap<>();
        for (String entry : Splitter.on(' ').split(table.trim())) {
            List<String> parts = Splitter.on('=').splitToList(entry);
            expected.put(parts.get(0), parts.get(1));
        }
        Map<String, String> actual = new TreeMap<>();
        for (ConduitQuad quad : quads(SHELL, mask(arms), 0,
                ConduitContents.pack(FLUID, ConduitContents.FULL, side(upstream), false))) {
            if (flowing(quad)) {
                String key = piece(quad) + "." + quad.facing().getSerializedName();
                assertEquals(null, actual.put(key, letter(flow(quad))), name + ": two quads on " + key);
            } else {
                assertEquals(STILL, quad.sprite(), name);
            }
        }
        assertEquals(expected, actual, name);
    }

    /** Without an upstream side every face, the lane's included, shows the still sprite. */
    @Test
    void noUpstreamIsStill() {
        List<ConduitQuad> quads = quads(SHELL, mask("ne"), 0, ConduitContents.pack(FLUID, 3, null, true));
        assertFalse(quads.isEmpty());
        for (ConduitQuad quad : quads) {
            assertEquals(STILL, quad.sprite());
        }
    }

    /** The second lane flows against the main flow: west in a run flowing east. */
    @Test
    void laneFlowsTheOtherWay() {
        int lanes = 0;
        for (ConduitQuad quad : quads(SHELL, mask("we"), 0, ConduitContents.pack(FLUID, 2, Direction.WEST, true))) {
            if (isLane(quad) && flowing(quad)) {
                assertEquals(Direction.WEST, flow(quad), quad.toString());
                lanes++;
            }
        }
        assertTrue(lanes >= 8, "lane faces along the run: " + lanes);
    }

    /** A horizontal run fills from the bottom of the contents to the fill level, over the whole run. */
    @ParameterizedTest
    @CsvSource({"1", "2", "3", "4"})
    void horizontalFillsToTheLevel(int fill) {
        AABB bounds = bounds(quads(SHELL, mask("we"), 0, ConduitContents.pack(FLUID, fill, null, false)));
        assertBox(new AABB(0, LOW, LOW, 16, LOW + (HIGH - LOW) * fill / 4, HIGH), bounds);
    }

    /** A vertical run is a full column at any fill; a horizontal arm beside it keeps its level. */
    @Test
    void verticalIsAFullColumn() {
        assertBox(new AABB(LOW, 0, LOW, HIGH, 16, HIGH),
                bounds(quads(SHELL, mask("du"), 0, ConduitContents.pack(FLUID, 1, null, false))));
        List<ConduitQuad> bend = quads(SHELL, mask("ue"), 0, ConduitContents.pack(FLUID, 2, null, false));
        for (ConduitQuad quad : bend) {
            double top = "e".equals(piece(quad)) ? LOW + (HIGH - LOW) / 2 : 16;
            assertTrue(quad.box().maxY <= top + EPS, quad.toString());
            if ("c".equals(piece(quad)) && quad.facing() != Direction.DOWN) {
                assertEquals(HIGH, quad.box().maxY, EPS, "the core of a vertical bend is a full column: " + quad);
            }
        }
        assertEquals(16, bounds(bend).maxY, EPS);
    }

    /** The contents sit {@code inset} px inside the glass; an inset that leaves no room draws nothing. */
    @Test
    void contentsAreInset() {
        int packed = ConduitContents.pack(FLUID, 4, null, false);
        assertBox(new AABB(LOW, LOW, LOW, HIGH, HIGH, HIGH), bounds(quads(SHELL, 0, 0, packed)));
        assertBox(new AABB(6.5, 6.5, 6.5, 9.5, 9.5, 9.5), bounds(quads(SHELL.withInset(1.5F), 0, 0, packed)));
        assertTrue(quads(SHELL.withInset(3), 0, 0, packed).isEmpty());
    }

    /** Fill 0 or no fluid draws nothing. */
    @Test
    void emptyDrawsNothing() {
        assertTrue(quads(SHELL, mask("we"), 0, ConduitContents.pack(FLUID, 0, Direction.WEST, true)).isEmpty());
        assertTrue(quads(SHELL, mask("we"), 0, ConduitContents.pack(-1, 0, null, false)).isEmpty());
        assertTrue(quads(SHELL, mask("we"), 0, 0).isEmpty());
        assertFalse(ConduitContentsGeometry.drawn(0));
    }

    /**
     * The lane is {@code lane_width} px square in the run's (+, +) inner corner, reaching just past the contents; it is
     * not drawn at width 0 or without a run.
     */
    @Test
    void laneSitsInTheInnerCorner() {
        int packed = ConduitContents.pack(FLUID, 1, Direction.WEST, true);
        double lift = ConduitContentsGeometry.LANE_LIFT;
        assertBox(new AABB(0, HIGH - 1, HIGH - 1, 16, HIGH + lift, HIGH + lift),
                bounds(lanes(quads(SHELL, mask("we"), 0, packed))));
        assertBox(new AABB(HIGH - 1, 0, HIGH - 1, HIGH + lift, 16, HIGH + lift),
                bounds(lanes(quads(SHELL, mask("du"), 0, packed))));
        assertTrue(lanes(quads(SHELL.withLaneWidth(0), mask("we"), 0, packed)).isEmpty());
        assertTrue(lanes(quads(SHELL, 0, 0, packed)).isEmpty());
        assertTrue(lanes(quads(SHELL, mask("we"), 0, ConduitContents.pack(FLUID, 1, Direction.WEST, false))).isEmpty());
    }

    /** Every contents quad is opaque cutout, tinted and lit by the paint. */
    @Test
    void quadsCarryThePaint() {
        for (ConduitQuad quad : quads(SHELL, mask("ne"), 0, ConduitContents.pack(FLUID, 2, Direction.NORTH, true))) {
            assertEquals(ConduitLayer.CUTOUT, quad.layer());
            assertEquals(0xFF3F76E4, quad.argb());
            assertEquals(15, quad.lightEmission());
            assertEquals(ConduitTexture.FLAT, quad.texture());
        }
        assertThrows(IllegalArgumentException.class, () -> new ConduitQuad(new AABB(0, 0, 0, 1, 1, 1), Direction.UP,
                ConduitTexture.FLAT, -1, false, ConduitLayer.AUTO, null, null, 16));
    }

    /**
     * Lib's looks cache bodies without the render value and contents, so values the body does not draw never multiply
     * bakes; the contents part drops the dye and render value instead.
     */
    @Test
    void cacheKeysDropWhatThePartDoesNotDraw() {
        int water = ConduitContents.pack(FLUID, 2, Direction.WEST, false);
        int lava = ConduitContents.pack(FLUID + 1, 4, null, true);
        ConduitBodyKey plain = new ConduitBodyKey(TYPE, DyeColor.RED, 3, 1);
        assertSame(plain, ConduitGeometry.cacheKey(plain));
        assertEquals(plain, ConduitGeometry.cacheKey(new ConduitBodyKey(TYPE, DyeColor.RED, 3, 1, 7, water)));
        assertEquals(plain, ConduitGeometry.cacheKey(new ConduitBodyKey(TYPE, DyeColor.RED, 3, 1, 0, lava)));
        assertEquals(new ConduitBodyKey(TYPE, null, 3, 1, 0, water),
                ConduitContentsGeometry.key(new ConduitBodyKey(TYPE, DyeColor.RED, 3, 1, 7, water)));
        PartCache<ConduitBodyKey, String> bodies = new PartCache<>(ConduitBodyKey::toString);
        for (int contents : new int[]{0, water, lava}) {
            for (int value : new int[]{0, 9}) {
                bodies.get(ConduitGeometry.cacheKey(new ConduitBodyKey(TYPE, null, 3, 1, value, contents)));
            }
        }
        assertEquals(1, bodies.bakes());
    }

    private static List<ConduitQuad> quads(ConduitLook.Shell look, int arms, int rings, int packed) {
        return ConduitContentsGeometry.quads(look, TYPE.thickness(), arms, rings, packed, PAINT);
    }

    /**
     * The world direction the sprite's +v runs along on the quad's face, from {@link ConduitUv}'s documented
     * convention: unturned, +v runs down the face (north on a down face, south on an up face); each clockwise quarter
     * turn moves it to the unturned -u, -v, then +u.
     */
    private static Direction flow(ConduitQuad quad) {
        ConduitUv uv = quad.uv();
        assertTrue(uv != null && uv.u0() < uv.u1() && uv.v0() < uv.v1(), "mirrored or missing UVs: " + quad);
        Direction face = quad.facing();
        Direction u = switch (face) {
            case DOWN, UP, SOUTH -> Direction.EAST;
            case NORTH -> Direction.WEST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
        };
        Direction v = switch (face) {
            case DOWN -> Direction.NORTH;
            case UP -> Direction.SOUTH;
            default -> Direction.DOWN;
        };
        return switch (uv.quarterTurns()) {
            case 0 -> v;
            case 1 -> u.getOpposite();
            case 2 -> v.getOpposite();
            default -> u;
        };
    }

    private static boolean flowing(ConduitQuad quad) {
        Identifier sprite = quad.sprite();
        return sprite != null && sprite.equals(FLOWING);
    }

    /** {@code c} for a quad of the core, else the letter of the arm's side. */
    private static String piece(ConduitQuad quad) {
        AABB box = quad.box();
        for (Direction side : Direction.values()) {
            Direction.Axis axis = side.getAxis();
            double centre = (box.min(axis) + box.max(axis)) / 2;
            boolean beyond = side.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? centre > HIGH + EPS
                    : centre < LOW - EPS;
            if (beyond) {
                return letter(side);
            }
        }
        return "c";
    }

    /** A lane quad: at most about 1 px across in one of its face's directions, unlike any main contents face. */
    private static boolean isLane(ConduitQuad quad) {
        AABB box = quad.box();
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis != quad.facing().getAxis() && box.max(axis) - box.min(axis) < 1.1) {
                return true;
            }
        }
        return false;
    }

    private static List<ConduitQuad> lanes(List<ConduitQuad> quads) {
        return quads.stream().filter(ConduitContentsGeometryTest::isLane).toList();
    }

    private static AABB bounds(List<ConduitQuad> quads) {
        assertFalse(quads.isEmpty());
        AABB bounds = quads.getFirst().box();
        for (ConduitQuad quad : quads) {
            bounds = bounds.minmax(quad.box());
        }
        return bounds;
    }

    private static void assertBox(AABB expected, AABB actual) {
        for (Direction.Axis axis : Direction.Axis.values()) {
            assertEquals(expected.min(axis), actual.min(axis), EPS, axis + " min of " + actual);
            assertEquals(expected.max(axis), actual.max(axis), EPS, axis + " max of " + actual);
        }
    }

    private static String letter(Direction side) {
        return side.getSerializedName().substring(0, 1);
    }

    private static @Nullable Direction side(String letter) {
        for (Direction side : Direction.values()) {
            if (letter(side).equals(letter)) {
                return side;
            }
        }
        return null;
    }

    private static int mask(String sides) {
        int mask = 0;
        for (int i = 0; i < sides.length(); i++) {
            Direction side = side(String.valueOf(sides.charAt(i)));
            if (side != null) {
                mask |= ConduitConnections.bit(side);
            }
        }
        return mask;
    }
}
