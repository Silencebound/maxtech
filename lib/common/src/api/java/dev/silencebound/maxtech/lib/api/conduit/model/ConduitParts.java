package dev.silencebound.maxtech.lib.api.conduit.model;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * The quads of Lib's own conduit model parts, so a custom conduit model can reuse Lib's body, connector rings and
 * disabled-face stubs and add its own quads around them. Pure geometry: safe on any thread, including chunk meshing
 * threads, but each call lays the quads out anew, so a model should bake and cache the result per key as Lib's does.
 * <p>
 * Lib sets this up in its own construction; before that every method throws {@link IllegalStateException}.
 * <p>
 * Experimental (Lib 0.14.0, for Farms, FM1).
 */
@ApiStatus.Experimental
public final class ConduitParts {
    private static volatile Factory factory = new Unset();

    private ConduitParts() {
    }

    /**
     * The body Lib draws for {@code key} in {@code look}: the core and an arm per connected side. With {@code labels}
     * (the player's colour-label setting) a dyed conduit also shows its dye's letter. Returns a new list the caller may
     * change.
     */
    public static List<ConduitQuad> body(ConduitLook look, ConduitBodyKey key, boolean labels) {
        return factory.body(look, key, labels);
    }

    /**
     * The face decoration Lib draws for {@code key}: a grey stub on a disabled face, else a connector ring in the
     * mode's colour, which with {@code labels} also carries the mode's glyph. Returns a new list the caller may change.
     */
    public static List<ConduitQuad> face(ConduitLook look, ConduitFaceKey key, boolean labels) {
        return factory.face(look, key, labels);
    }

    /**
     * The faces of {@code type}'s item in {@code look}: a straight east-west piece across the whole block, drawn as the
     * block model draws a straight run of it, with both ends closed as an open end in the world is and the border
     * colour undyed; no contents, labels or rings. For an item model, which is static; boxes stay within 0 to 16.
     * Returns a new list the caller may change. Lib 0.15.0.
     */
    public static List<ConduitQuad> itemPiece(ConduitLook.Shell look, ConduitType type) {
        return factory.itemPiece(look, type);
    }

    /** Sets where the methods above get their quads; Lib calls it once during init. */
    @ApiStatus.Internal
    public static void setFactory(Factory source) {
        factory = source;
    }

    /** Lib's side of {@link ConduitParts}. */
    @ApiStatus.Internal
    public interface Factory {
        /** See {@link ConduitParts#body}. */
        List<ConduitQuad> body(ConduitLook look, ConduitBodyKey key, boolean labels);

        /** See {@link ConduitParts#face}. */
        List<ConduitQuad> face(ConduitLook look, ConduitFaceKey key, boolean labels);

        /** See {@link ConduitParts#itemPiece}. */
        List<ConduitQuad> itemPiece(ConduitLook.Shell look, ConduitType type);
    }

    private static final class Unset implements Factory {
        private static IllegalStateException unset() {
            return new IllegalStateException("MaxTech Lib has not set up conduit parts yet");
        }

        @Override
        public List<ConduitQuad> body(ConduitLook look, ConduitBodyKey key, boolean labels) {
            throw unset();
        }

        @Override
        public List<ConduitQuad> face(ConduitLook look, ConduitFaceKey key, boolean labels) {
            throw unset();
        }

        @Override
        public List<ConduitQuad> itemPiece(ConduitLook.Shell look, ConduitType type) {
            throw unset();
        }
    }
}
