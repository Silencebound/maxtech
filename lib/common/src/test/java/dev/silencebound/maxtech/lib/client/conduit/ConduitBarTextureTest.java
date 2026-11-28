package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ItemLike;
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
            assertEquals(ConduitUvs.projected(after.box(), after.facing(), uv.quarterTurns()), uv, "projected");
        }
        assertFalse(borders == 0, "no borders");
    }

    /**
     * On a straight run the bar sprite is turned like the glass: its rows run along the tube, so stripes and rivets
     * line up along the bars.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"we", "du", "ns"})
    void barRowsRunAlongTheTube(String arms) {
        List<ConduitQuad> quads = body(ConduitLook.shell(GLASS).withBorder(1).withFrameBars().withBarTexture(BAR),
                mask(arms));
        int checked = 0;
        for (ConduitQuad bar : quads) {
            ConduitUv barUv = bar.uv();
            if (!Objects.equals(BAR, bar.sprite()) || barUv == null || longSide(bar) != axisOf(arms)) {
                continue;
            }
            for (ConduitQuad glass : quads) {
                ConduitUv glassUv = glass.uv();
                if (Objects.equals(GLASS, glass.sprite()) && glass.facing() == bar.facing() && glassUv != null) {
                    assertEquals(glassUv.quarterTurns(), barUv.quarterTurns(), "turns of " + bar);
                    checked++;
                }
            }
        }
        assertFalse(checked == 0, "nothing compared");
    }

    private static Direction.Axis longSide(ConduitQuad quad) {
        Direction.Axis longest = Direction.Axis.X;
        double length = -1;
        for (Direction.Axis axis : Direction.Axis.values()) {
            double extent = quad.box().max(axis) - quad.box().min(axis);
            if (axis != quad.facing().getAxis() && extent > length) {
                longest = axis;
                length = extent;
            }
        }
        return longest;
    }

    private static Direction.Axis axisOf(String arms) {
        return switch (arms.charAt(0)) {
            case 'd', 'u' -> Direction.Axis.Y;
            case 'n', 's' -> Direction.Axis.Z;
            default -> Direction.Axis.X;
        };
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
