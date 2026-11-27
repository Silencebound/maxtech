package dev.silencebound.maxtech.lib.api.conduit.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class ConduitUvTest {
    /** The whole range is allowed, mirrored rectangles too, and every quarter turn. */
    @Test
    void acceptsTheSpriteRange() {
        assertDoesNotThrow(() -> new ConduitUv(0, 0, 16, 16, 0));
        assertDoesNotThrow(() -> new ConduitUv(16, 16, 0, 0, 3));
        assertDoesNotThrow(() -> new ConduitUv(4.5F, 2, 4.5F, 14, 2));
        assertEquals(0, new ConduitUv(1, 2, 3, 4).quarterTurns());
    }

    /** A coordinate outside the sprite or not a number, or a turn outside 0 to 3, fails. */
    @Test
    void rejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new ConduitUv(-0.01F, 0, 16, 16, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConduitUv(0, 16.01F, 16, 16, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConduitUv(0, 0, 17, 16, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConduitUv(0, 0, 16, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConduitUv(Float.NaN, 0, 16, 16, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConduitUv(0, 0, 16, 16, -1));
        assertThrows(IllegalArgumentException.class, () -> new ConduitUv(0, 0, 16, 16, 4));
    }

    /** The quad constructors and {@code ofSprite} from before keep whole-sprite UVs. */
    @Test
    void olderQuadConstructorsHaveNoUv() {
        AABB box = new AABB(6, 6, 6, 10, 10, 10);
        assertNull(new ConduitQuad(box, Direction.UP, ConduitTexture.FLAT, -1, false).uv());
        assertNull(new ConduitQuad(box, Direction.UP, ConduitTexture.FLAT, -1, false, ConduitLayer.CUTOUT).uv());
        assertNull(ConduitQuad
                .ofSprite(box, Direction.UP, Identifier.parse("test:block/glass"), -1, false, ConduitLayer.AUTO).uv());
    }
}
