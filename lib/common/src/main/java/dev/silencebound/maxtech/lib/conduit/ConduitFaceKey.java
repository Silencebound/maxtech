package dev.silencebound.maxtech.lib.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import net.minecraft.core.Direction;

/**
 * One face decoration of a conduit: a connector ring coloured by {@code mode}, or a grey stub when {@code mode} is
 * {@link ConduitFaceMode#DISABLED}. Its size follows the type's thickness. The conduit model caches one baked part per
 * key, so the cache grows with types, sides and modes, not with every combination of modes (ADR-0005).
 */
public record ConduitFaceKey(ConduitType type, Direction side, ConduitFaceMode mode) {
    public boolean isStub() {
        return mode == ConduitFaceMode.DISABLED;
    }
}
