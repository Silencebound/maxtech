package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A shell's {@link ConduitLook.Shell#barTexture}: the same borders and bars, drawn with the texture's sprite. */
class ConduitBarTextureTest {
    private static final Identifier GLASS = Identifier.fromNamespaceAndPath("test", "block/glass");
    private static final Identifier BAR = Identifier.fromNamespaceAndPath("test", "block/bar");
    private static final ConduitType TYPE = new ConduitType(Identifier.fromNamespaceAndPath("test", "net"), "test",
            0x4078D8, 0.375F, () -> (ItemLike) () -> {
                throw new UnsupportedOperationException();
            });

    /**
     * Every border quad (bar, collar, line, rim) keeps its box, face, tint and layer but shows the bar sprite projected
     * onto the block; the glass is left alone.
     */
    @ParameterizedTest(name = "frame bars {0}, arms {1}")
    @CsvSource({"true, we", "true, e", "true, ''", "true, es", "true, wes", "true, nswe", "true, ues", "false, we",
            "false, es", "false, nswe"})
    void bordersShowTheTextureProjected(boolean frameBars, String arms) {
        ConduitLook.Shell plain = ConduitLook.shell(GLASS).withBorder(1);
        plain = frameBars ? plain.withFrameBars() : plain;
        int mask = mask(arms);
        List<ConduitQuad> flat = body(plain, mask);
        List<ConduitQuad> textured = body(plain.withBarTexture(BAR), mask);
        assertEquals(flat.size(), textured.size(), "quad count");
        int borders = 0;
        for (int i = 0; i < flat.size(); i++) {
            ConduitQuad before = flat.get(i);
            ConduitQuad after = textured.get(i);
            if (Objects.equals(GLASS, before.sprite())) {
                assertEquals(before, after, "glass changed");
                continue;
            }
            borders++;
            assertEquals(before.box(), after.box());
            assertEquals(before.facing(), after.facing());
            assertEquals(before.argb(), after.argb(), "tint");
            assertEquals(before.layer(), after.layer());
            assertEquals(BAR, after.sprite(), "sprite of " + after);
            ConduitUv uv = after.uv();
            assertNotNull(uv, "uv of " + after);
            int turns = expectedTurns(after.box(), after.facing());
            assertEquals(turns, uv.quarterTurns(), "turns of " + after);
            assertEquals(ConduitUvs.projected(after.box(), after.facing(), turns), uv, "projected");
        }
        assertFalse(borders == 0, "no borders");
    }

    /**
     * Every bar's sprite rows run along its longer side (its u span is the longer extent), on straight runs, bends,
     * branches and a crossing, with 1 px and 2 px bars. Squares are pinned apart below.
     */
    @ParameterizedTest(name = "{1} px bars, arms {0}")
    @CsvSource({"we, 1", "du, 1", "ns, 1", "es, 1", "wn, 2", "wes, 1", "wes, 2", "ues, 1", "nswe, 1", "nswe, 2",
            "'', 1", "we, 2", "du, 2"})
    void barRowsRunAlongTheLongSide(String arms, float border) {
        List<ConduitQuad> quads = body(ConduitLook.shell(GLASS).withBorder(border).withFrameBars().withBarTexture(BAR),
                mask(arms));
        int checked = 0;
        for (ConduitQuad bar : quads) {
            ConduitUv uv = bar.uv();
            if (!Objects.equals(BAR, bar.sprite()) || uv == null) {
                continue;
            }
            double[] sides = sides(bar);
            if (Math.abs(sides[0] - sides[1]) < 1.0E-3) {
                continue;
            }
            assertEquals(Math.max(sides[0], sides[1]), uv.u1() - uv.u0(), 1.0E-3, "u span of " + bar);
            assertEquals(Math.min(sides[0], sides[1]), uv.v1() - uv.v0(), 1.0E-3, "v span of " + bar);
            checked++;
        }
        assertFalse(checked == 0, "nothing compared");
    }

    /**
     * Where the long-side pick ties (a square bar end or collar face) the rows run along the first of X, Y, Z that is
     * not the face's own axis.
     */
    @ParameterizedTest(name = "{0} bars on {1}")
    @CsvSource({"1, DOWN", "1, UP", "1, NORTH", "1, SOUTH", "1, WEST", "1, EAST", "2, UP", "2, EAST"})
    void squareFacesRunAlongTheFirstAxis(float border, Direction face) {
        double lo = 6;
        AABB square = new AABB(lo, lo, lo, lo + border, lo + border, lo + border);
        ConduitQuad flat = new ConduitQuad(square, face, ConduitTexture.FLAT, 0xFFFFFFFF, false);
        ConduitUv uv = ConduitGeometry.textured(flat, BAR).uv();
        assertNotNull(uv);
        assertEquals(turnsWithRowsAlong(face, other(face)[0]), uv.quarterTurns());
    }

    /** The quarter turns that put the sprite's rows along the longer side of {@code box}'s {@code face}. */
    private static int expectedTurns(AABB box, Direction face) {
        double[] sides = sides(box, face);
        Direction.Axis[] axes = other(face);
        Direction.Axis along = sides[1] > sides[0] + 1.0E-4 ? axes[1] : axes[0];
        return turnsWithRowsAlong(face, along);
    }

    /**
     * The turns the glass has on {@code face} of a straight tube along {@code along}: the sprite's u runs along the
     * tube, toward its positive end. The bars follow the glass, so the stripes of both line up.
     */
    private static int turnsWithRowsAlong(Direction face, Direction.Axis along) {
        String arms = switch (along) {
            case X -> "we";
            case Y -> "du";
            case Z -> "ns";
        };
        for (ConduitQuad quad : body(ConduitLook.shell(GLASS).withBorder(1).withFrameBars(), mask(arms))) {
            if (Objects.equals(GLASS, quad.sprite()) && quad.facing() == face && quad.uv() != null) {
                return quad.uv().quarterTurns();
            }
        }
        throw new AssertionError("no glass on " + face + " of a tube along " + along);
    }

    private static Direction.Axis[] other(Direction face) {
        return Arrays.stream(Direction.Axis.values()).filter(axis -> axis != face.getAxis())
                .toArray(Direction.Axis[]::new);
    }

    /** The extents of the quad's box along the two axes its face spans, in X, Y, Z order. */
    private static double[] sides(ConduitQuad quad) {
        return sides(quad.box(), quad.facing());
    }

    private static double[] sides(AABB box, Direction face) {
        Direction.Axis[] axes = other(face);
        return new double[]{box.max(axes[0]) - box.min(axes[0]), box.max(axes[1]) - box.min(axes[1])};
    }

    private static List<ConduitQuad> body(ConduitLook.Shell look, int arms) {
        return ConduitGeometry.body(look, new ConduitBodyKey(TYPE, null, arms, 0));
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
