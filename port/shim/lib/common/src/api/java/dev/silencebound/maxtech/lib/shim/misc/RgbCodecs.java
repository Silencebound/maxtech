package dev.silencebound.maxtech.lib.shim.misc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.HexFormat;
import net.minecraft.util.ExtraCodecs;

/** 26.1's {@code ExtraCodecs.STRING_RGB_COLOR}, which 1.21.1 lacks. */
public final class RgbCodecs {
    /**
     * A colour as {@code "#RRGGBB"}, or as the number or three-float list vanilla's RGB codec reads. Decodes to an
     * opaque ARGB int; encodes the RGB part as {@code "#rrggbb"}.
     */
    public static final Codec<Integer> STRING_RGB_COLOR = Codec.withAlternative(
            hexColor().xmap(ARGB::opaque, ARGB::transparent), Codec.withAlternative(Codec.INT,
                    ExtraCodecs.VECTOR3F, vector -> ARGB.colorFromFloat(1.0F, vector.x(), vector.y(), vector.z())));

    private RgbCodecs() {
    }

    private static Codec<Integer> hexColor() {
        return Codec.STRING.comapFlatMap(string -> {
            if (!string.startsWith("#")) {
                return DataResult.error(() -> "Hex color must begin with #");
            }
            int digits = string.length() - 1;
            if (digits != 6) {
                return DataResult.error(() -> "Hex color is wrong size, expected 6 digits but got " + digits);
            }
            try {
                long value = HexFormat.fromHexDigitsToLong(string, 1, string.length());
                return value >= 0L && value <= 0xFFFFFFL ? DataResult.success((int) value)
                        : DataResult.error(() -> "Color value out of range: " + string);
            } catch (NumberFormatException e) {
                return DataResult.error(() -> "Invalid color value: " + string);
            }
        }, value -> "#" + HexFormat.of().toHexDigits(value.intValue(), 6));
    }
}
