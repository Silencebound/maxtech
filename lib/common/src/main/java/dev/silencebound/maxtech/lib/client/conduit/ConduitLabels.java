package dev.silencebound.maxtech.lib.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import dev.silencebound.maxtech.lib.info.ConduitInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.AABB;

/**
 * The client option {@code accessibility.conduitColorLabels} (PRD 8.2): marks that say in shape what a conduit's
 * colours say, so no information is carried by colour alone. A dyed conduit shows its dye's letter ({@link #dyeLetter})
 * on the open faces of its core; a connector shows an arrow into the block for insert, out of the block for extract and
 * a diamond for both on the sides of its arm, next to the ring or band in the mode's colour. The Jade overlay adds the
 * same marks in words ({@link #infoLines}). Pure geometry and text with no client classes, so it is unit-tested; the
 * models bake the quads once per cache key, never per frame.
 */
public final class ConduitLabels {
    /** Letter glyphs are this many cells wide and tall. */
    static final int LETTER_COLUMNS = 3;
    static final int LETTER_ROWS = 5;
    /** Mode glyphs are this many cells wide (across the arm) and long (along it). */
    static final int MODE_COLUMNS = 5;
    static final int MODE_ROWS = 3;
    /** How far a mark stands proud of the face it is drawn on, in pixels: enough not to fight with it. */
    static final double LIFT = 0.02;
    /** Space kept free around a glyph, in pixels. */
    private static final double MARGIN = 0.5;
    /** Below this cell size a glyph is not readable, so none is drawn. */
    private static final double MIN_CELL = 0.3;
    private static final double BLOCK = 16.0;
    private static final double CENTRE = BLOCK / 2;
    /** Ink for a mark on a light colour, and for one on a dark colour. */
    public static final int DARK_INK = 0xFF141414;
    public static final int LIGHT_INK = 0xFFF2F2F2;

    public static final String COLOUR_MARK_KEY = "info.maxtech.conduit.colour_mark";
    public static final String MODE_MARK_KEY = "info.maxtech.conduit.mode_mark";
    private static final String MODE_GLYPH_KEY_PREFIX = "info.maxtech.mode_glyph.";

    private static final Direction[] DIRECTIONS = Direction.values();

    private ConduitLabels() {
    }

    /**
     * The letter shown on a conduit dyed {@code colour}, unique per dye: the initial where it is free, otherwise a
     * letter of another name for the colour (A for azure, S for silver, D for dark grey, V for violet, N from brown, K
     * for black as in CMYK).
     */
    public static char dyeLetter(DyeColor colour) {
        return switch (colour) {
            case WHITE -> 'W';
            case ORANGE -> 'O';
            case MAGENTA -> 'M';
            case LIGHT_BLUE -> 'A';
            case YELLOW -> 'Y';
            case LIME -> 'L';
            case PINK -> 'P';
            case GRAY -> 'D';
            case LIGHT_GRAY -> 'S';
            case CYAN -> 'C';
            case PURPLE -> 'V';
            case BLUE -> 'B';
            case BROWN -> 'N';
            case GREEN -> 'G';
            case RED -> 'R';
            case BLACK -> 'K';
        };
    }

    /**
     * The {@link #LETTER_COLUMNS} x {@link #LETTER_ROWS} glyph of a {@link #dyeLetter}: bit {@code row * 3 + column}
     * set where a cell is inked, row 0 at the top and column 0 at the left.
     */
    static int letterBits(char letter) {
        return switch (letter) {
            case 'W' -> bits("X.X", "X.X", "XXX", "XXX", "X.X");
            case 'O' -> bits("XXX", "X.X", "X.X", "X.X", "XXX");
            case 'M' -> bits("X.X", "XXX", "XXX", "X.X", "X.X");
            case 'A' -> bits(".X.", "X.X", "XXX", "X.X", "X.X");
            case 'Y' -> bits("X.X", "X.X", ".X.", ".X.", ".X.");
            case 'L' -> bits("X..", "X..", "X..", "X..", "XXX");
            case 'P' -> bits("XX.", "X.X", "XX.", "X..", "X..");
            case 'D' -> bits("XX.", "X.X", "X.X", "X.X", "XX.");
            case 'S' -> bits(".XX", "X..", ".X.", "..X", "XX.");
            case 'C' -> bits(".XX", "X..", "X..", "X..", ".XX");
            case 'V' -> bits("X.X", "X.X", "X.X", "X.X", ".X.");
            case 'B' -> bits("XX.", "X.X", "XX.", "X.X", "XX.");
            case 'N' -> bits("XX.", "X.X", "X.X", "X.X", "X.X");
            case 'G' -> bits(".XX", "X..", "X.X", "X.X", ".XX");
            case 'R' -> bits("XX.", "X.X", "XX.", "X.X", "X.X");
            case 'K' -> bits("X.X", "X.X", "XX.", "X.X", "X.X");
            default -> 0;
        };
    }

    /**
     * The {@link #MODE_COLUMNS} x {@link #MODE_ROWS} glyph of a connector in {@code mode}, row 0 nearest the block: an
     * arrow pointing into the block for insert, out of it for extract, a diamond for both; nothing for a disabled face,
     * whose grey stub already differs in shape.
     */
    static int modeBits(ConduitFaceMode mode) {
        return switch (mode) {
            case INSERT -> bits("..X..", ".X.X.", "X...X");
            case EXTRACT -> bits("X...X", ".X.X.", "..X..");
            case BOTH -> bits("..X..", ".X.X.", "..X..");
            case DISABLED -> 0;
        };
    }

    /** Lang key naming {@code mode}'s glyph, e.g. {@code info.maxtech.mode_glyph.insert}. */
    public static String modeGlyphKey(ConduitFaceMode mode) {
        return MODE_GLYPH_KEY_PREFIX + mode.name().toLowerCase(Locale.ROOT);
    }

    /** The ink a mark drawn over {@code argb} uses: dark on a light colour, light on a dark one. */
    public static int ink(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        // Rec. 601 luma, in thousandths.
        int luma = (299 * r + 587 * g + 114 * b) / 1000;
        return luma >= 110 ? DARK_INK : LIGHT_INK;
    }

    /**
     * Adds the letter of {@code colour} in {@code ink} on every face of the core of a conduit {@code thickness} thick
     * that no arm in {@code arms} leaves, upright on the sides and north-up on top and bottom.
     */
    public static void addDyeLetter(List<ConduitQuad> out, float thickness, int arms, DyeColor colour, int ink) {
        double width = ConduitShapes.width(thickness);
        double cell = Math.min(1.0, (width - MARGIN) / LETTER_ROWS);
        if (cell < MIN_CELL) {
            return;
        }
        int glyph = letterBits(dyeLetter(colour));
        for (Direction face : DIRECTIONS) {
            if (ConduitConnections.isConnected(arms, face)) {
                continue;
            }
            double half = width / 2;
            addGlyph(out, CENTRE + face.getStepX() * half, CENTRE + face.getStepY() * half,
                    CENTRE + face.getStepZ() * half, face, up(face), glyph, LETTER_COLUMNS, LETTER_ROWS, cell, ink);
        }
    }

    /**
     * Adds {@code mode}'s glyph in {@code argb} on the four long sides of the {@code side} arm of a conduit
     * {@code thickness} thick, starting {@code fromFace} px in from the block face, pointing along the arm. Nothing for
     * a disabled face or where the glyph would not fit.
     */
    public static void addModeMark(List<ConduitQuad> out, float thickness, Direction side, ConduitFaceMode mode,
            double fromFace, int argb) {
        int glyph = modeBits(mode);
        double cell = modeCell(thickness, fromFace);
        if (glyph == 0 || cell < MIN_CELL) {
            return;
        }
        double half = ConduitShapes.width(thickness) / 2;
        // The glyph's centre, measured along the arm from the block's centre.
        double along = CENTRE - (fromFace + MODE_ROWS * cell / 2);
        for (Direction face : DIRECTIONS) {
            if (face.getAxis() == side.getAxis()) {
                continue;
            }
            double x = CENTRE + side.getStepX() * along + face.getStepX() * half;
            double y = CENTRE + side.getStepY() * along + face.getStepY() * half;
            double z = CENTRE + side.getStepZ() * along + face.getStepZ() * half;
            addGlyph(out, x, y, z, face, side, glyph, MODE_COLUMNS, MODE_ROWS, cell, argb);
        }
    }

    /**
     * How far a mode glyph started {@code fromFace} px in from the block face reaches along the arm of a conduit
     * {@code thickness} thick, in px from the block face; {@code fromFace} when no glyph fits.
     */
    public static double modeMarkEnd(float thickness, double fromFace) {
        double cell = modeCell(thickness, fromFace);
        return cell < MIN_CELL ? fromFace : fromFace + MODE_ROWS * cell;
    }

    private static double modeCell(float thickness, double fromFace) {
        double width = ConduitShapes.width(thickness);
        double armLength = (BLOCK - width) / 2;
        return Math.min(1.0, Math.min((width - MARGIN) / MODE_COLUMNS, (armLength - fromFace - MARGIN) / MODE_ROWS));
    }

    /**
     * Adds the letter of {@code colour} in {@code ink} on the four long sides of every arm in {@code arms} of a conduit
     * {@code thickness} thick, within {@code span} px of the core, oriented as {@link #addDyeLetter}. For a conduit
     * whose core faces carry something else, such as a covered cable's tier pips.
     */
    public static void addDyeLetterOnArms(List<ConduitQuad> out, float thickness, int arms, DyeColor colour, int ink,
            double span) {
        double width = ConduitShapes.width(thickness);
        double cell = Math.min(1.0, Math.min((width - MARGIN) / LETTER_ROWS, span / LETTER_ROWS));
        if (cell < MIN_CELL) {
            return;
        }
        int glyph = letterBits(dyeLetter(colour));
        double half = width / 2;
        for (Direction side : DIRECTIONS) {
            if (!ConduitConnections.isConnected(arms, side)) {
                continue;
            }
            for (Direction face : DIRECTIONS) {
                if (face.getAxis() == side.getAxis()) {
                    continue;
                }
                // Centred in the span, measured along the arm from the block's centre.
                double along = half + span / 2;
                double x = CENTRE + side.getStepX() * along + face.getStepX() * half;
                double y = CENTRE + side.getStepY() * along + face.getStepY() * half;
                double z = CENTRE + side.getStepZ() * along + face.getStepZ() * half;
                addGlyph(out, x, y, z, face, up(face), glyph, LETTER_COLUMNS, LETTER_ROWS, cell, ink);
            }
        }
    }

    /** Which way a letter's top points on a face facing {@code face}: up on the sides, north on top and bottom. */
    private static Direction up(Direction face) {
        return face.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
    }

    /**
     * The Jade lines for a conduit while the option is on: its dye letter and colour, and per connector the side, mode
     * and glyph in words. Empty while {@code labels} (the client's option) is off, and for an uncoloured conduit
     * without connectors.
     */
    public static List<Component> infoLines(ConduitRenderData data, boolean labels) {
        if (!labels) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        DyeColor colour = data.colour();
        if (colour != null) {
            lines.add(Component.translatable(COLOUR_MARK_KEY, String.valueOf(dyeLetter(colour)),
                    Component.translatable("color.minecraft." + colour.getName())));
        }
        for (ConduitFaceKey face : data.faces()) {
            if (!face.isStub()) {
                lines.add(
                        Component.translatable(MODE_MARK_KEY, Component.translatable(ConduitInfo.sideKey(face.side())),
                                Component.translatable(ConduitInfo.faceModeKey(face.mode())),
                                Component.translatable(modeGlyphKey(face.mode()))));
            }
        }
        return lines;
    }

    /**
     * Adds one pane per inked cell of {@code glyph} ({@code columns} x {@code rows} cells of {@code cell} px), centred
     * on ({@code x}, {@code y}, {@code z}) in the plane of a face pointing {@code facing}, row 0 towards {@code up} and
     * read the right way round from outside, {@link #LIFT} in front of the plane.
     */
    @SuppressWarnings("TooManyParameters") // a glyph's position, orientation, size and colour; kept primitive
    private static void addGlyph(List<ConduitQuad> out, double x, double y, double z, Direction facing, Direction up,
            int glyph, int columns, int rows, double cell, int argb) {
        // The viewer's right on a face seen from outside: up x normal.
        int rightX = up.getStepY() * facing.getStepZ() - up.getStepZ() * facing.getStepY();
        int rightY = up.getStepZ() * facing.getStepX() - up.getStepX() * facing.getStepZ();
        int rightZ = up.getStepX() * facing.getStepY() - up.getStepY() * facing.getStepX();
        double px = x + facing.getStepX() * LIFT;
        double py = y + facing.getStepY() * LIFT;
        double pz = z + facing.getStepZ() * LIFT;
        double half = cell / 2;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if ((glyph >> (row * columns + column) & 1) == 0) {
                    continue;
                }
                double right = (column - (columns - 1) / 2.0) * cell;
                double upward = ((rows - 1) / 2.0 - row) * cell;
                double cx = px + rightX * right + up.getStepX() * upward;
                double cy = py + rightY * right + up.getStepY() * upward;
                double cz = pz + rightZ * right + up.getStepZ() * upward;
                // Flat along the face's axis, a cell wide along the other two.
                double hx = facing.getAxis() == Direction.Axis.X ? 0 : half;
                double hy = facing.getAxis() == Direction.Axis.Y ? 0 : half;
                double hz = facing.getAxis() == Direction.Axis.Z ? 0 : half;
                out.add(new ConduitQuad(new AABB(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz), facing,
                        ConduitTexture.FLAT, argb, false));
            }
        }
    }

    /** Glyph bits from rows of {@code X} (inked) and {@code .}, top row first. */
    private static int bits(String... rows) {
        int bits = 0;
        int columns = rows[0].length();
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < columns; column++) {
                if (rows[row].charAt(column) == 'X') {
                    bits |= 1 << (row * columns + column);
                }
            }
        }
        return bits;
    }
}
