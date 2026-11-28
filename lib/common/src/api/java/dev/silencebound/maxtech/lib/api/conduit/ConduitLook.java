package dev.silencebound.maxtech.lib.api.conduit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * How a conduit block is drawn (ADR-0012). Each conduit block's block state file names Lib's conduit model with one of
 * these, written by Storage's datagen or, for an addon's conduit, by Lib at runtime from {@link Conduits.Builder#look};
 * the model takes the thickness, connections and rings from the block entity. {@link #CODEC} is the documented format
 * ({@code docs/api/conduit-addon.md}).
 *
 * <p>
 * {@link Solid} is a square conduit in one flat colour, with no seams, rims or dark lines, so a run reads as one
 * continuous cable and only face shading separates top from sides. An arm that ends in the open shows
 * {@link Solid#end}, optionally with a tinted {@link Solid#overlay} on top (a cable's wire core). {@link Shell} is a
 * translucent glass tube whose border lines and rims Lib works out from the connections. The block state file names the
 * look ({@code "look": "solid"} or {@code "shell"}), so another look can be added without changing existing files.
 *
 * <p>
 * Colours are {@code 0xRRGGBB}; {@link #TYPE_COLOUR} stands for the conduit type's {@link ConduitType#baseColour()}. A
 * dyed conduit pulls its body colour towards the dye ({@link ConduitColours}).
 * <p>
 * Experimental (Lib 0.14.0, moved from {@code lib.conduit} for Farms, FM1) until Farms, its first addon user, settles
 * it.
 */
@ApiStatus.Experimental
public sealed interface ConduitLook {
    /** Use the conduit type's base colour. */
    int TYPE_COLOUR = -1;
    /** The plain white sprite that flat faces are tinted on. */
    Identifier FLAT = Identifier.fromNamespaceAndPath("maxtech_lib", "block/conduit/flat");
    /** A flat conduit in its type's colour; what a conduit block without a look of its own gets. */
    ConduitLook DEFAULT = wire();

    /** The look as the block state file holds it: a {@code look} field naming the {@link Kind}, then its fields. */
    MapCodec<ConduitLook> CODEC = Kind.CODEC.dispatchMap("look", ConduitLook::kind, Kind::codec);

    /** Which of the looks this is; the {@code look} field of the block state file. */
    Kind kind();

    /** A bare wire: solid in its type's colour, flat on every face. */
    static Solid wire() {
        return new Solid(FLAT, null, TYPE_COLOUR, TYPE_COLOUR);
    }

    /**
     * A conduit whose open ends show {@code end} with {@code overlay} drawn over it in {@code overlayColour}, its flat
     * faces and end in {@code colour}: an insulated cable with its wire core showing.
     */
    static Solid solid(Identifier end, @Nullable Identifier overlay, int colour, int overlayColour) {
        return new Solid(end, overlay, colour, overlayColour);
    }

    /**
     * A glass tube drawn with {@code shell}, its borders in the type's colour at the default widths
     * ({@link Shell#DEFAULT_BORDER}, {@link Shell#DEFAULT_RIM}). Lib 0.14.0.
     */
    static Shell shell(Identifier shell) {
        return new Shell(shell, TYPE_COLOUR, Shell.DEFAULT_BORDER, Shell.DEFAULT_RIM, Shell.DEFAULT_INSET,
                Shell.DEFAULT_LANE_WIDTH);
    }

    /** {@code colour}, or {@code type}'s base colour for {@link #TYPE_COLOUR}; {@code 0xRRGGBB}. */
    static int resolve(int colour, ConduitType type) {
        return colour == TYPE_COLOUR ? type.baseColour() & 0xFFFFFF : colour;
    }

    /**
     * A flat conduit; see {@link ConduitLook}.
     *
     * @param end
     *            sprite of an open end, drawn whole on the end face whatever the thickness
     * @param overlay
     *            sprite drawn over {@code end} in {@code overlayColour}, or {@code null}
     * @param colour
     *            body and end colour, or {@link #TYPE_COLOUR}
     * @param overlayColour
     *            overlay colour, or {@link #TYPE_COLOUR}; dyes leave it alone
     */
    record Solid(Identifier end, @Nullable Identifier overlay, int colour, int overlayColour) implements ConduitLook {
        static final MapCodec<Solid> CODEC = RecordCodecBuilder
                .mapCodec(i -> i
                        .group(Identifier.CODEC.fieldOf("end").forGetter(Solid::end),
                                Identifier.CODEC.optionalFieldOf("overlay")
                                        .forGetter(s -> Optional.ofNullable(s.overlay())),
                                colourField("colour").forGetter(Solid::colour),
                                colourField("overlay_colour").forGetter(Solid::overlayColour))
                        .apply(i, (end, overlay, colour, overlayColour) -> new Solid(end, overlay.orElse(null), colour,
                                overlayColour)));

        @Override
        public Kind kind() {
            return Kind.SOLID;
        }
    }

    /**
     * A translucent glass tube (Lib 0.14.0): every glass face shows {@code shell} projected onto the block, so the
     * sprite tiles on without a seam from one tube to the next, and opaque border lines in {@code borderColour} run
     * along the tube's outer long edges. Lib works the lines out from the connections: they run on across joints
     * between tubes, never across one, and leave out the inner creases of bends and branches. An open end gets a rim
     * {@code rim} px wide around its end face, and an arm that meets a block a rim band just inside Lib's connector
     * ring. A dye tints the borders only.
     * <p>
     * A conduit's {@link ConduitContents} show inside the glass, opaque, in the fluid's own textures and tint and at
     * its own light level, {@code inset} px in from the glass: from the bottom up to the fill level on horizontal
     * pieces, as a full column on vertical ones. With an upstream side the fluid's flowing texture runs along the flow,
     * else its still one; a second lane is a strip {@code laneWidth} px wide in the run's inner (+, +) corner, on the
     * fluid's surface on horizontal pieces, flowing the other way. The fluid's tint is its default one, not the
     * biome's.
     * <p>
     * With {@code frameBars} (Lib 0.15.0) the borders are solid bars instead of lines on the faces: a bar
     * {@code border} px square on every edge of the tube's outer box, flush with it, and the glass set in by the bar's
     * width between them, so the bars stand out as a frame. A bend, branch or single tube has a bar on all twelve edges
     * of its core, each arm one on its four long edges, and the bars run to the block face, where the next tube's go
     * on. Nothing is left out, so the bars on the far side, seen through the glass, read as the back of the frame. An
     * open end's edges are bars too (no rim), a rim band becomes a collar one bar deep, and the contents sit
     * {@code inset} px inside the inset glass. Label marks sit on the inset glass between the bars, mode glyphs past
     * the collar.
     * <p>
     * The block state fields are {@code shell}, then {@code border_colour}, {@code border}, {@code rim}, {@code inset},
     * {@code lane_width} and {@code frame_bars}, each left out at its default; later fields will be optional too, so
     * files stay valid.
     *
     * @param shell
     *            sprite of the glass (block atlas), usually mostly transparent
     * @param borderColour
     *            colour of the border lines and rims, or {@link #TYPE_COLOUR}
     * @param border
     *            width of a border line on each face it borders, in pixels, 0 to {@value #MAX_WIDTH}; 0 draws none
     * @param rim
     *            width of a rim, in pixels, 0 to {@value #MAX_WIDTH}; 0 draws none
     * @param inset
     *            how far the contents sit inside the glass, in pixels, 0 to {@value #MAX_WIDTH}
     * @param laneWidth
     *            width of the second lane's strip, in pixels, 0 to {@value #MAX_WIDTH}; 0 draws none
     * @param frameBars
     *            whether the borders are solid bars on every edge, with the glass set in between them
     */
    record Shell(Identifier shell, int borderColour, float border, float rim, float inset, float laneWidth,
            boolean frameBars) implements ConduitLook {
        /** Default {@link #border}, in pixels. */
        public static final float DEFAULT_BORDER = 0.5F;
        /** Default {@link #rim}, in pixels. */
        public static final float DEFAULT_RIM = 1.5F;
        /** Default {@link #inset}, in pixels. */
        public static final float DEFAULT_INSET = 0.5F;
        /** Default {@link #laneWidth}, in pixels. */
        public static final float DEFAULT_LANE_WIDTH = 1.0F;
        /** The widest {@link #border} or {@link #rim}, in pixels. */
        public static final float MAX_WIDTH = 4.0F;

        static final MapCodec<Shell> CODEC = RecordCodecBuilder.mapCodec(i -> i
                .group(Identifier.CODEC.fieldOf("shell").forGetter(Shell::shell),
                        colourField("border_colour").forGetter(Shell::borderColour),
                        widthField("border", DEFAULT_BORDER).forGetter(Shell::border),
                        widthField("rim", DEFAULT_RIM).forGetter(Shell::rim),
                        widthField("inset", DEFAULT_INSET).forGetter(Shell::inset),
                        widthField("lane_width", DEFAULT_LANE_WIDTH).forGetter(Shell::laneWidth),
                        Codec.BOOL.optionalFieldOf("frame_bars", false).forGetter(Shell::frameBars))
                .apply(i, Shell::new));

        /**
         * Checks the widths.
         *
         * @throws IllegalArgumentException
         *             when a width is outside 0 to {@value #MAX_WIDTH} or not a number
         */
        public Shell {
            requireWidth("border", border);
            requireWidth("rim", rim);
            requireWidth("inset", inset);
            requireWidth("lane_width", laneWidth);
        }

        /** A shell with the default {@link #inset} and {@link #laneWidth}, as the first shells had. */
        public Shell(Identifier shell, int borderColour, float border, float rim) {
            this(shell, borderColour, border, rim, DEFAULT_INSET, DEFAULT_LANE_WIDTH);
        }

        /** A shell with border lines, as shells before Lib 0.15.0 were. */
        public Shell(Identifier shell, int borderColour, float border, float rim, float inset, float laneWidth) {
            this(shell, borderColour, border, rim, inset, laneWidth, false);
        }

        /** This look with its borders and rims in {@code colour} ({@code 0xRRGGBB} or {@link #TYPE_COLOUR}). */
        public Shell withBorderColour(int colour) {
            return new Shell(shell, colour, border, rim, inset, laneWidth, frameBars);
        }

        /** This look with border lines {@code border} px wide. */
        public Shell withBorder(float border) {
            return new Shell(shell, borderColour, border, rim, inset, laneWidth, frameBars);
        }

        /** This look with rims {@code rim} px wide. */
        public Shell withRim(float rim) {
            return new Shell(shell, borderColour, border, rim, inset, laneWidth, frameBars);
        }

        /** This look with its contents {@code inset} px inside the glass. */
        public Shell withInset(float inset) {
            return new Shell(shell, borderColour, border, rim, inset, laneWidth, frameBars);
        }

        /** This look with a second lane {@code laneWidth} px wide. */
        public Shell withLaneWidth(float laneWidth) {
            return new Shell(shell, borderColour, border, rim, inset, laneWidth, frameBars);
        }

        /** This look with its borders as solid frame bars and the glass set in between them ({@link #frameBars}). */
        public Shell withFrameBars() {
            return new Shell(shell, borderColour, border, rim, inset, laneWidth, true);
        }

        @Override
        public Kind kind() {
            return Kind.SHELL;
        }

        private static void requireWidth(String name, float width) {
            if (!(width >= 0.0F && width <= MAX_WIDTH)) {
                throw new IllegalArgumentException(name + " must be 0 to " + MAX_WIDTH + " px, was " + width);
            }
        }

        private static MapCodec<Float> widthField(String name, float fallback) {
            return Codec.floatRange(0.0F, MAX_WIDTH).optionalFieldOf(name, fallback);
        }
    }

    /** The looks by name, for the block state file. */
    enum Kind implements StringRepresentable {
        /** {@link Solid}, named {@code solid}. */
        SOLID("solid"),
        /** {@link Shell}, named {@code shell}. Lib 0.14.0. */
        SHELL("shell");

        static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        private final String name;

        Kind(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        MapCodec<? extends ConduitLook> codec() {
            return switch (this) {
                case SOLID -> Solid.CODEC;
                case SHELL -> Shell.CODEC;
            };
        }
    }

    /** An optional {@code #RRGGBB} field; absent means {@link #TYPE_COLOUR}. */
    private static MapCodec<Integer> colourField(String name) {
        return ExtraCodecs.STRING_RGB_COLOR.optionalFieldOf(name).xmap(
                colour -> colour.map(c -> c & 0xFFFFFF).orElse(TYPE_COLOUR),
                colour -> colour == TYPE_COLOUR ? Optional.empty() : Optional.of(colour));
    }
}
