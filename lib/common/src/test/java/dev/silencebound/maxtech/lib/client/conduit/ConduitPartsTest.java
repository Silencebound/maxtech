package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitParts;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import org.junit.jupiter.api.Test;

class ConduitPartsTest {
    private static final ConduitType TYPE = new ConduitType(Identifier.fromNamespaceAndPath("test", "net"), "test",
            0x4078D8, 0.5F, () -> (ItemLike) () -> {
                throw new UnsupportedOperationException();
            });

    /** The api hands out exactly the quads Lib's own conduit model bakes. */
    @Test
    void partsMatchLibsGeometry() {
        ConduitParts.setFactory(new LibConduitParts());
        ConduitLook look = ConduitLook.wire();
        for (boolean labels : new boolean[]{false, true}) {
            for (int arms = 0; arms < 64; arms++) {
                ConduitBodyKey key = new ConduitBodyKey(TYPE, DyeColor.RED, arms, arms & 0b000101);
                assertEquals(ConduitGeometry.body(look, key, labels), ConduitParts.body(look, key, labels));
            }
            for (Direction side : Direction.values()) {
                for (ConduitFaceMode mode : ConduitFaceMode.values()) {
                    ConduitFaceKey key = new ConduitFaceKey(TYPE, side, mode);
                    assertEquals(ConduitGeometry.face(look, key, labels), ConduitParts.face(look, key, labels));
                }
            }
        }
    }

}
