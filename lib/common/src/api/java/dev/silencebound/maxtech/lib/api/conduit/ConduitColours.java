package dev.silencebound.maxtech.lib.api.conduit;

import net.minecraft.util.ARGB;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Conduit colours: an uncoloured conduit shows its look's colour ({@link ConduitLook}), a dyed one that colour pulled
 * most of the way towards the dye; face decorations show their mode's colour.
 * <p>
 * Experimental (Lib 0.14.0, moved from {@code lib.conduit} for Farms, FM1) until Farms, its first addon user, settles
 * it.
 */
@ApiStatus.Experimental
public final class ConduitColours {
    // Enough that a dyed conduit reads as its dye colour, little enough that two dyed types still look different.
    static final float DYE_WEIGHT = 0.65F;

    private ConduitColours() {
    }

    /** Opaque {@code 0xAARRGGBB} tint for a conduit of {@code type} dyed {@code colour} ({@code null} = uncoloured). */
    public static int argb(ConduitType type, @Nullable DyeColor colour) {
        return argb(type.baseColour(), colour);
    }

    /** Opaque {@code 0xAARRGGBB} tint for a conduit whose own colour is {@code rgb}, dyed {@code colour}. */
    public static int argb(int rgb, @Nullable DyeColor colour) {
        int base = ARGB.opaque(rgb);
        return colour == null ? base : ARGB.srgbLerp(DYE_WEIGHT, base, colour.getTextureDiffuseColor());
    }

    /**
     * Opaque colour of a face decoration (ADR-0005): blue insert, orange extract, green both; a disabled face's stub is
     * grey.
     */
    public static int faceArgb(ConduitFaceMode mode) {
        return switch (mode) {
            case INSERT -> 0xFF3C6FE0;
            case EXTRACT -> 0xFFF08A24;
            case BOTH -> 0xFF4CB43C;
            case DISABLED -> 0xFF7A7A7A;
        };
    }
}
