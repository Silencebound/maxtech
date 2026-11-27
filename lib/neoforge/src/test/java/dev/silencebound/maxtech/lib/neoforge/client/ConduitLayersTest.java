package dev.silencebound.maxtech.lib.neoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.platform.Transparency;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConduitLayersTest {
    private static final List<Transparency> SPRITES = List.of(Transparency.NONE, Transparency.TRANSPARENT,
            Transparency.TRANSLUCENT, Transparency.TRANSPARENT_AND_TRANSLUCENT);

    /** {@code AUTO} keeps the sprite's own answer, so every existing conduit lands in the layer it always did. */
    @Test
    void autoKeepsTheSpritesTransparency() {
        for (Transparency sprite : SPRITES) {
            assertEquals(sprite, ConduitLayers.transparency(ConduitLayer.AUTO, sprite));
        }
    }

    /** A forced layer ignores the sprite. */
    @Test
    void forcedLayersIgnoreTheSprite() {
        for (Transparency sprite : SPRITES) {
            assertEquals(Transparency.TRANSLUCENT, ConduitLayers.transparency(ConduitLayer.TRANSLUCENT, sprite));
            assertEquals(Transparency.TRANSPARENT, ConduitLayers.transparency(ConduitLayer.CUTOUT, sprite));
        }
    }
}
