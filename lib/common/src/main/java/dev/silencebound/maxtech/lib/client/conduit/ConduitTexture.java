package dev.silencebound.maxtech.lib.client.conduit;

/** Which sprite a {@link ConduitQuad} is drawn with; the conduit model maps each to a sprite of its look. */
public enum ConduitTexture {
    /** The plain white sprite, tinted with the quad's colour. */
    FLAT,
    /** The open end of a solid conduit ({@code ConduitLook.Solid#end}). */
    END,
    /** Drawn over {@link #END} ({@code ConduitLook.Solid#overlay}), e.g. a cable's wire core. */
    OVERLAY
}
