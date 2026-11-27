package dev.silencebound.maxtech.lib.neoforge.api.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitParts;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * Draws an addon conduit marked {@code customModel} on its builder, registered in {@link RegisterConduitModelsEvent}.
 * Lib's conduit model still decides which parts a conduit shows, caches each baked part by its key for the lifetime of
 * the baked model, and draws a facade instead while one hides the conduit; the hook only supplies the quads of a part
 * the first time its key is seen. Equal keys must give equal quads.
 * <p>
 * Threading: {@link #sprites()} is called once per model bake, on the model-bake worker thread, so it must not touch
 * the level or the main thread; {@link #body} and {@link #face} are called later on chunk meshing threads, possibly at
 * once, so they must not touch the level or any shared mutable state.
 * <p>
 * Breaking and landing particles use the end sprite of the conduit's {@link ConduitLook} (Lib's
 * {@code ConduitModel.particleMaterial()}), which a hook cannot change yet; pick a look whose end sprite suits the
 * conduit, or accept that the particles do not match a custom model's sprites.
 * <p>
 * Experimental (Lib 0.14.0, for Farms, FM1).
 */
@ApiStatus.Experimental
public interface ConduitModelHook {
    /**
     * The atlas ids of the sprites the hook's quads name through {@link ConduitQuad#sprite()} (block atlas, e.g.
     * {@code farms:block/tube_glass} for {@code assets/farms/textures/block/tube_glass.png}). Sprites must live under
     * {@code textures/block/**}: only that is stitched into the block atlas, and any other sprite becomes the missing
     * texture with a log warning. Lib resolves them when the model bakes, on the model-bake worker thread; a quad
     * naming any other sprite fails when its part bakes. Empty if the hook only uses Lib's
     * {@link dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture}s.
     */
    Set<Identifier> sprites();

    /**
     * The quads of the conduit body for {@code key}: core and arms. {@code look} is the block's look from its block
     * state file; {@code labels} is the player's colour-label setting, for a hook that shows Lib's dye letters (Lib
     * caches the two separately). Lib's own body by default ({@link ConduitParts#body}). Never null; may be empty.
     */
    default List<ConduitQuad> body(ConduitLook look, ConduitBodyKey key, boolean labels) {
        return ConduitParts.body(look, key, labels);
    }

    /**
     * The quads of one face decoration for {@code key}: a connector ring, or a stub on a disabled face; {@code look}
     * and {@code labels} as for {@link #body}. Lib's own rings and stubs by default ({@link ConduitParts#face}). Never
     * null; may be empty.
     */
    default List<ConduitQuad> face(ConduitLook look, ConduitFaceKey key, boolean labels) {
        return ConduitParts.face(look, key, labels);
    }
}
