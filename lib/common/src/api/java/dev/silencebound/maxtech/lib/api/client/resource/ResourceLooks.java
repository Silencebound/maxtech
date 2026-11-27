package dev.silencebound.maxtech.lib.api.client.resource;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.ApiStatus;

/**
 * The client's {@link ResourceLook} per {@link ResourceKind}. Register a kind's look on the client during mod
 * construction or client setup, before a world is joined; Lib registers the fluid kind's. A kind without a look is
 * drawn as nothing in conduits (Lib logs one warning per kind). Safe from any thread.
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

    /** The look registered for {@code kind}, or empty when it has none. */
    @SuppressWarnings("unchecked") // register only pairs a kind with a look of its own key type
    public static <K> Optional<ResourceLook<K>> get(ResourceKind<K> kind) {
        return Optional.ofNullable((ResourceLook<K>) LOOKS.get(kind));
    }
}
