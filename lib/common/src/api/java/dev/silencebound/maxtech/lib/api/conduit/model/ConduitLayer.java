package dev.silencebound.maxtech.lib.api.conduit.model;

import org.jetbrains.annotations.ApiStatus;

/** Which chunk layer a {@link ConduitQuad} is drawn in. */
@ApiStatus.Experimental
public enum ConduitLayer {
    /**
     * The layer the quad's sprite asks for: solid for an opaque sprite, cutout with transparent pixels, translucent
     * with translucent ones. What every Lib conduit uses.
     */
    AUTO,
    /** Always translucent, whatever the sprite, e.g. a tinted glass pane. */
    TRANSLUCENT,
    /** Always cutout: pixels are drawn fully or not at all. */
    CUTOUT
}
