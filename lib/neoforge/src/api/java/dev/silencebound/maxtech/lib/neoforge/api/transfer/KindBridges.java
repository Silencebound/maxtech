package dev.silencebound.maxtech.lib.neoforge.api.transfer;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link KindBridge} of each resource kind. Register during mod construction; lookups are safe from any thread and
 * meant for when a port builds its caches, not per operation.
 */
@ApiStatus.Experimental
public final class KindBridges {
    private static final Map<ResourceKind<?>, KindBridge<?>> BRIDGES = new ConcurrentHashMap<>();

    private KindBridges() {
    }

    /** Sets {@code kind}'s bridge; throws {@link IllegalArgumentException} if it has one. */
    public static <K> void register(ResourceKind<K> kind, KindBridge<K> bridge) {
        if (BRIDGES.putIfAbsent(kind, bridge) != null) {
            throw new IllegalArgumentException(kind + " already has a transfer bridge");
        }
    }

    /** {@code kind}'s bridge, or empty if none is registered (energy, or a kind nothing bridges). */
    @SuppressWarnings("unchecked") // register pairs each kind with a bridge of its own key type
    public static <K> Optional<KindBridge<K>> get(ResourceKind<K> kind) {
        return Optional.ofNullable((KindBridge<K>) BRIDGES.get(kind));
    }
}
