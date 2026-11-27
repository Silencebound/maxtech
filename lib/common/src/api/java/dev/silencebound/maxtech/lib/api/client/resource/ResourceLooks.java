package dev.silencebound.maxtech.lib.api.client.resource;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * The client's {@link ResourceLook} per {@link ResourceKind}. Register a kind's look on the client during mod
 * construction or client setup, before a world is joined; Lib registers the fluid kind's. A kind without a look is
 * drawn as nothing in conduits (Lib logs one warning per kind). Safe from any thread.
 *
 * <p>
 * Register before a world is joined: a look registered after its kind was first shown in a conduit leaves that kind's
 * paint null (drawn as nothing) until the next resource reload resolves it again.
 */
@ApiStatus.Experimental
public final class ResourceLooks {
    private static final Map<ResourceKind<?>, ResourceLook<?>> LOOKS = new ConcurrentHashMap<>();

    private ResourceLooks() {
    }

    /**
     * Registers {@code look} for {@code kind}.
     *
     * @throws IllegalStateException
     *             if the kind already has a look; one mod owns a kind's look, and a second must not replace it
     */
    public static <K> void register(ResourceKind<K> kind, ResourceLook<K> look) {
        Objects.requireNonNull(look, "look");
        if (LOOKS.putIfAbsent(Objects.requireNonNull(kind, "kind"), look) != null) {
            throw new IllegalStateException("Resource kind " + kind.id() + " already has a look");
        }
    }

    /**
     * The look registered for {@code kind}, or null when it has none; does not allocate, for render paths that run
     * every frame.
     */
    @SuppressWarnings("unchecked") // register only pairs a kind with a look of its own key type
    public static <K> @Nullable ResourceLook<K> getOrNull(ResourceKind<K> kind) {
        return (ResourceLook<K>) LOOKS.get(kind);
    }

    /** The look registered for {@code kind}, or empty when it has none; allocates, see {@link #getOrNull}. */
    public static <K> Optional<ResourceLook<K>> get(ResourceKind<K> kind) {
        return Optional.ofNullable(getOrNull(kind));
    }
}
