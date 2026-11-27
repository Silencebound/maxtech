package dev.silencebound.maxtech.lib.neoforge.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A translucent quad in a model not declared translucent fails, naming the block; declared or opaque is fine. */
    @Test
    void undeclaredTranslucencyFailsNamingTheBlock() {
        for (Transparency translucent : List.of(Transparency.TRANSLUCENT, Transparency.TRANSPARENT_AND_TRANSLUCENT)) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> ConduitLayers.requireDeclared(false, translucent, "test:tube", "test:block/glass"));
            assertTrue(String.valueOf(error.getMessage()).contains("test:tube"), error.getMessage());
            assertDoesNotThrow(() -> ConduitLayers.requireDeclared(true, translucent, "test:tube", "x"));
        }
        for (Transparency opaque : List.of(Transparency.NONE, Transparency.TRANSPARENT)) {
            assertDoesNotThrow(() -> ConduitLayers.requireDeclared(false, opaque, "test:tube", "x"));
        }
    }
}
