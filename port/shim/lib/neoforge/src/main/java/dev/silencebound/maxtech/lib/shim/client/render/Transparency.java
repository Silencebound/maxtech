package dev.silencebound.maxtech.lib.shim.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * 26.1's {@code com.mojang.blaze3d.platform.Transparency}: whether a sprite has fully transparent and partly
 * translucent pixels, which picks a quad's chunk layer. 1.21.1 sprites do not carry it, so {@link #of} scans the
 * sprite's original image once per sprite contents, as 26.1's {@code NativeImage.computeTransparency} does at load.
 */
public record Transparency(boolean hasTransparent, boolean hasTranslucent) {
    public static final Transparency NONE = new Transparency(false, false);
    public static final Transparency TRANSPARENT = new Transparency(true, false);
    public static final Transparency TRANSLUCENT = new Transparency(false, true);
    public static final Transparency TRANSPARENT_AND_TRANSLUCENT = new Transparency(true, true);

    // Keyed by contents, which a resource reload replaces, so old sprites drop out with their atlas.
    private static final Map<SpriteContents, Transparency> SPRITES = Collections.synchronizedMap(new WeakHashMap<>());

    public static Transparency of(boolean hasTransparent, boolean hasTranslucent) {
        if (hasTransparent && hasTranslucent) {
            return TRANSPARENT_AND_TRANSLUCENT;
        } else if (hasTransparent) {
            return TRANSPARENT;
        } else {
            return hasTranslucent ? TRANSLUCENT : NONE;
        }
    }

    /** The transparency of {@code sprite}'s pixels, computed once per sprite contents. */
    public static Transparency of(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        Transparency known = SPRITES.get(contents);
        if (known == null) {
            known = of(contents.getOriginalImage());
            SPRITES.put(contents, known);
        }
        return known;
    }

    /** The transparency of {@code image}'s pixels, as 26.1's {@code NativeImage.computeTransparency}. */
    public static Transparency of(NativeImage image) {
        if (image.format() != NativeImage.Format.RGBA) {
            return NONE;
        }
        boolean transparent = false;
        boolean translucent = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getPixelRGBA(x, y) >>> 24;
                if (alpha == 0) {
                    transparent = true;
                } else if (alpha != 255) {
                    translucent = true;
                }
            }
        }
        return of(transparent, translucent);
    }

    public Transparency or(Transparency other) {
        return of(this.hasTransparent || other.hasTransparent, this.hasTranslucent || other.hasTranslucent);
    }

    public boolean isOpaque() {
        return !this.hasTransparent && !this.hasTranslucent;
    }

    /** The chunk layer 26.1's {@code ChunkSectionLayer.byTransparency} picks for this transparency. */
    public RenderType chunkLayer() {
        if (hasTranslucent) {
            return RenderType.translucent();
        }
        return hasTransparent ? RenderType.cutout() : RenderType.solid();
    }
}
