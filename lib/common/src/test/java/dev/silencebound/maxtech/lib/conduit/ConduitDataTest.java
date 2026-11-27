package dev.silencebound.maxtech.lib.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ItemLike;
import org.junit.jupiter.api.Test;

class ConduitDataTest {
    @Test
    void newConduitStartsInTheDefaultMode() {
        ConduitType insertFirst = type(EnumSet.allOf(ConduitFaceMode.class), ConduitFaceMode.INSERT);
        ConduitData conduit = new ConduitData(insertFirst, null, 0);
        for (Direction side : Direction.values()) {
            assertEquals(ConduitFaceMode.INSERT, conduit.faceMode(side), side.name());
        }
    }

    private static ConduitType type(Set<ConduitFaceMode> supported, ConduitFaceMode defaultMode) {
        return new ConduitType(Identifier.fromNamespaceAndPath("test", "modes"), "basic", 0x808080,
                ConduitDataTest::noItem) {
            @Override
            public Set<ConduitFaceMode> supportedFaceModes() {
                return supported;
            }

            @Override
            public ConduitFaceMode defaultFaceMode() {
                return defaultMode;
            }
        };
    }

    private static ItemLike noItem() {
        throw new UnsupportedOperationException();
    }
}
