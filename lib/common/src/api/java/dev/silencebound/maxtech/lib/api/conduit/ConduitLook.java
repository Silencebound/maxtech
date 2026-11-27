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
 * The one look is {@link Solid}: a square conduit in one flat colour, with no seams, rims or dark lines, so a run reads
 * as one continuous cable and only face shading separates top from sides. An arm that ends in the open shows
 * {@link Solid#end}, optionally with a tinted {@link Solid#overlay} on top (a cable's wire core). The block state file
 * still names the look ({@code "look": "solid"}), so another look can be added without changing existing files.
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

    /** The looks by name, for the block state file. */
    enum Kind implements StringRepresentable {
        /** {@link Solid}, named {@code solid}. */
        SOLID("solid");

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
