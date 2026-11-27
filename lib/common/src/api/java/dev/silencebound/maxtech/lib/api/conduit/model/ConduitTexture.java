package dev.silencebound.maxtech.lib.api.conduit.model;

import org.jetbrains.annotations.ApiStatus;

/** Which sprite a {@link ConduitQuad} is drawn with; the conduit model maps each to a sprite of its look. */
@ApiStatus.Experimental
public enum ConduitTexture {
    /** The plain white sprite, tinted with the quad's colour. */
    FLAT,
    /** The open end of a solid conduit ({@link dev.silencebound.maxtech.lib.api.conduit.ConduitLook.Solid#end()}). */
    END,
    /**
     * Drawn over {@link #END} ({@link dev.silencebound.maxtech.lib.api.conduit.ConduitLook.Solid#overlay()}), e.g. a
     * cable's wire core.
     */
    OVERLAY
}
