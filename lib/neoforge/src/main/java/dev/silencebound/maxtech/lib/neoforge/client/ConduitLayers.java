package dev.silencebound.maxtech.lib.neoforge.client;

import com.mojang.blaze3d.platform.Transparency;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer;

/** Maps a {@link ConduitLayer} to the {@link Transparency} that picks a baked quad's chunk layer. */
final class ConduitLayers {
    private ConduitLayers() {
    }

    /** The transparency of a quad in {@code layer} whose sprite has {@code sprite}. */
    static Transparency transparency(ConduitLayer layer, Transparency sprite) {
        return switch (layer) {
            case AUTO -> sprite;
            case TRANSLUCENT -> Transparency.TRANSLUCENT;
            case CUTOUT -> Transparency.TRANSPARENT;
        };
    }

    /**
     * Fails if a quad lands in the translucent chunk layer in a model its builder did not mark translucent, which block
     * outlines and moving blocks would get wrong.
     *
     * @param block
     *            the conduit block, for the message
     * @param quadSprite
     *            the quad's sprite or texture, for the message
     */
    static void requireDeclared(boolean modelTranslucent, Transparency transparency, Object block, Object quadSprite) {
        boolean translucent = Transparency.TRANSLUCENT.equals(transparency)
                || Transparency.TRANSPARENT_AND_TRANSLUCENT.equals(transparency);
        if (translucent && !modelTranslucent) {
            throw new IllegalStateException("A quad of conduit " + block + " using " + quadSprite
                    + " is drawn in the translucent layer, but its builder called customModel(false); pass true or give"
                    + " the quad an opaque or cutout layer");
        }
    }
}
