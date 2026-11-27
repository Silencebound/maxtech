package dev.silencebound.maxtech.lib.api.conduit;

import net.minecraft.core.Direction;
import org.jetbrains.annotations.ApiStatus;

/**
 * One face decoration of a conduit: a connector ring coloured by {@code mode}, or a grey stub when {@code mode} is
 * {@link ConduitFaceMode#DISABLED}. Its size follows the type's thickness. The conduit model caches one baked part per
 * key, so the cache grows with types, sides and modes, not with every combination of modes (ADR-0005).
 * <p>
 * Experimental (Lib 0.14.0, moved from {@code lib.conduit} for Farms, FM1) until Farms, its first addon user, settles
 * it.
 *
 * @param type
 *            the conduit type, which sets the size
 * @param side
 *            the face it sits on
 * @param mode
 *            the face's mode, which sets the colour
 */
@ApiStatus.Experimental
public record ConduitFaceKey(ConduitType type, Direction side, ConduitFaceMode mode) {
    /** Whether this is a disabled face, drawn as a grey stub instead of a ring. */
    public boolean isStub() {
        return mode == ConduitFaceMode.DISABLED;
    }
}
