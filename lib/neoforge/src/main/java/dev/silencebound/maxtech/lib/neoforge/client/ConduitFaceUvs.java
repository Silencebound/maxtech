package dev.silencebound.maxtech.lib.neoforge.client;

import com.mojang.math.Quadrant;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import org.jspecify.annotations.Nullable;

/** Maps a quad's {@link ConduitUv} to the UVs and turn {@code FaceBakery} bakes it with. */
final class ConduitFaceUvs {
    /** The whole sprite, for a quad without UVs of its own. */
    static final CuboidFace.UVs WHOLE = new CuboidFace.UVs(0.0F, 0.0F, 16.0F, 16.0F);
    private static final Quadrant[] TURNS = Quadrant.values();

    private ConduitFaceUvs() {
    }

    /** The face UVs of {@code uv}, the whole sprite for none. */
    static CuboidFace.UVs uvs(@Nullable ConduitUv uv) {
        return uv == null ? WHOLE : new CuboidFace.UVs(uv.u0(), uv.v0(), uv.u1(), uv.v1());
    }

    /** The face turn of {@code uv}, none for none; {@code Quadrant}'s order is its quarter turns. */
    static Quadrant rotation(@Nullable ConduitUv uv) {
        return uv == null ? Quadrant.R0 : TURNS[uv.quarterTurns()];
    }
}
