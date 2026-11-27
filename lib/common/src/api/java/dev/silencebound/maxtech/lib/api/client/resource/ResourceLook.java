package dev.silencebound.maxtech.lib.api.client.resource;

import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import java.util.Optional;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

/**
 * How the resources of one {@link ResourceKind} look: a still sprite, optionally a flowing one, the tint that goes over
 * them, a display name and the light they give off. Registered once per kind on the client
 * ({@link ResourceLooks#register}); conduit contents ({@link ConduitContents}) and screens draw with it. Sprites live
 * on the block atlas and may be animated.
 * <p>
 * Called on the client's main thread only: conduit contents resolve a look into a paint when a resource is first shown
 * and again after a resource reload, never while chunks are meshed. A look sees a resource's draw base for conduit
 * contents and the full key in screens.
 *
 * @param <K>
 *            the kind's key type
 */
@ApiStatus.Experimental
public interface ResourceLook<K> {
    /** The resource's still sprite, on the block atlas; the missing sprite when it has none. */
    TextureAtlasSprite sprite(K key);

    /**
     * The sprite drawn where the resource flows, on the block atlas; it moves towards +v, like vanilla's flowing fluid
     * sprites. Empty (the default) to draw the still sprite there too.
     */
    default Optional<TextureAtlasSprite> flowingSprite(K key) {
        return Optional.empty();
    }

    /** The ARGB tint to multiply the sprites with, opaque; white (the default) for none. */
    default int tint(K key) {
        return 0xFFFFFFFF;
    }

    /** The resource's display name. */
    Component name(K key);

    /**
     * The light the resource gives off where it is drawn in the world, 0 to 15 (values outside are clamped); 0 (the
     * default) for none. Lit quads draw full-bright without ambient occlusion.
     */
    default int lightEmission(K key) {
        return 0;
    }
}
