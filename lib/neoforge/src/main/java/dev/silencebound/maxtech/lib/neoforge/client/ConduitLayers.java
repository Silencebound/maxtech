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
}
