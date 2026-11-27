package dev.silencebound.maxtech.lib.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitParts;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import java.util.List;

/** {@link ConduitParts} on {@link ConduitGeometry}. */
public final class LibConduitParts implements ConduitParts.Factory {
    @Override
    public List<ConduitQuad> body(ConduitLook look, ConduitBodyKey key, boolean labels) {
        return ConduitGeometry.body(look, key, labels);
    }

    @Override
    public List<ConduitQuad> face(ConduitLook look, ConduitFaceKey key, boolean labels) {
        return ConduitGeometry.face(look, key, labels);
    }
}
