package dev.silencebound.maxtech.lib.client.conduit;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.client.resource.ResourceLook;
import dev.silencebound.maxtech.lib.api.client.resource.ResourceLooks;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.conduit.ResourceLookIndex;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The client's look index for conduit contents: packs a conduit's {@link ConduitContents} with the session's look
 * indices and holds the {@link ContentsPaint} resolved for each from the kind's {@link ResourceLook}. Client only; the
 * conduit block entity packs on the client's main thread, and the conduit model reads paints while meshing.
 */
public final class ConduitLooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<ResourceKind<?>> WARNED = ConcurrentHashMap.newKeySet();

    /** Indices by resource draw base; Lib's client glue repaints it on a resource reload and clears it on logout. */
    public static final ResourceLookIndex<KindKey<?>, ContentsPaint> INDEX = new ResourceLookIndex<>(
            ConduitLooks::paint);

    private ConduitLooks() {
    }

    /** {@code contents} packed with this session's look indices, adding its resource if new; main thread. */
    public static int pack(ConduitContents contents) {
        return INDEX.pack(contents.resource(), contents.fill(), contents.upstream(), contents.lane());
    }

    /**
     * What the contents of one resource are drawn with: the {@link ConduitContentsGeometry.Paint} by sprite id plus the
     * sprites themselves, which the model bakes with.
     */
    public record ContentsPaint(ConduitContentsGeometry.Paint paint, TextureAtlasSprite still,
            TextureAtlasSprite flowing) {
    }

    /** The paint of {@code key} from its kind's look, or null (logged once per kind) when the kind has none. */
    static @Nullable ContentsPaint paint(KindKey<?> key) {
        return paintOf(key);
    }

    private static <K> @Nullable ContentsPaint paintOf(KindKey<K> key) {
        Optional<ResourceLook<K>> registered = ResourceLooks.get(key.kind());
        if (registered.isEmpty()) {
            if (WARNED.add(key.kind())) {
                LOGGER.warn("Resource kind {} has no client look; conduit contents of it are drawn as nothing",
                        key.kindId());
            }
            return null;
        }
        ResourceLook<K> look = registered.get();
        TextureAtlasSprite still = look.sprite(key.key());
        TextureAtlasSprite flowing = look.flowingSprite(key.key()).orElse(still);
        int light = Math.clamp(look.lightEmission(key.key()), 0, ConduitQuad.MAX_LIGHT);
        int argb = look.tint(key.key()) | 0xFF000000;
        return new ContentsPaint(
                new ConduitContentsGeometry.Paint(still.contents().name(), flowing.contents().name(), argb, light),
                still, flowing);
    }
}
