package dev.silencebound.maxtech.lib.content;

import java.util.OptionalInt;
import net.minecraft.resources.Identifier;

/**
 * One texture layer of a layered item or block model ({@link ItemModel#layered}, {@link BlockModel#layeredCube}), drawn
 * over the layers before it, e.g. an untinted overlay on a tinted base.
 *
 * @param texture
 *            the texture id, e.g. {@code maxtech:item/material/metallic/dust}
 * @param tint
 *            the {@code 0xRRGGBB} colour multiplied into the layer, empty for none
 * @param optional
 *            whether datagen leaves the layer out when its texture file does not exist, for overlays that only some
 *            icon sets have
 */
public record ModelLayer(Identifier texture, OptionalInt tint, boolean optional) {
    /** A layer drawn as it is. */
    public static ModelLayer plain(Identifier texture) {
        return new ModelLayer(texture, OptionalInt.empty(), false);
    }

    /** A layer tinted with {@code rgb}. */
    public static ModelLayer tinted(Identifier texture, int rgb) {
        return new ModelLayer(texture, OptionalInt.of(rgb), false);
    }

    /** An untinted layer that datagen leaves out when the texture does not exist. */
    public static ModelLayer overlay(Identifier texture) {
        return new ModelLayer(texture, OptionalInt.empty(), true);
    }
}
