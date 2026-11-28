package dev.silencebound.maxtech.lib.neoforge.transfer;

import com.google.common.primitives.Ints;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindBridge;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindBridges;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindHandler;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The item and fluid {@link KindBridge}s: thin wrappers over NeoForge's {@code ResourceHandler} capabilities, which
 * always take part in transactions. Amounts saturate to {@code int}. A handler wrapper keeps the last resource it read
 * and the last key it converted (by equality), so reading an unchanged handler or moving the same resource again
 * allocates nothing.
 */
public final class ResourceHandlerBridge<K, R extends Resource> implements KindBridge<K> {
    private final BlockCapability<ResourceHandler<R>, @Nullable Direction> capability;
    private final Function<R, K> toKey;
    private final Function<K, R> toResource;

    private ResourceHandlerBridge(BlockCapability<ResourceHandler<R>, @Nullable Direction> capability,
            Function<R, K> toKey, Function<K, R> toResource) {
        this.capability = capability;
        this.toKey = toKey;
        this.toResource = toResource;
    }

    /** Registers the item and fluid bridges; Lib calls it once during its construction. */
    public static void register() {
        KindBridges.register(ResourceKinds.ITEM, new ResourceHandlerBridge<>(Capabilities.Item.BLOCK, StackKeys::of,
                (ItemKey key) -> StackKeys.toResource(key)));
        KindBridges.register(ResourceKinds.FLUID, new ResourceHandlerBridge<>(Capabilities.Fluid.BLOCK, StackKeys::of,
                (FluidKey key) -> StackKeys.toResource(key)));
    }

    @Override
    public Cache<K> cache(ServerLevel level, BlockPos pos, Direction side, BooleanSupplier isValid,
            Runnable onInvalidate) {
        BlockCapabilityCache<ResourceHandler<R>, @Nullable Direction> cache = BlockCapabilityCache.create(capability,
                level, pos, side, isValid, onInvalidate);
        return new Cache<>() {
            private @Nullable ResourceHandler<R> last;
            private Optional<KindHandler<K>> wrapped = Optional.empty();

            @Override
            @SuppressWarnings("ReferenceEquality") // the same handler instance keeps its wrapper
            public Optional<KindHandler<K>> handler() {
                ResourceHandler<R> current = cache.getCapability();
                if (current != last) {
                    last = current;
                    wrapped = current == null ? Optional.empty() : Optional.of(new Wrapped(current));
                }
                return wrapped;
            }
        };
    }

    private final class Wrapped implements KindHandler<K> {
        private final ResourceHandler<R> handler;
        private @Nullable R lastRead;
        private Optional<K> lastReadKey = Optional.empty();
        private @Nullable K lastKey;
        private @Nullable R lastResource;

        Wrapped(ResourceHandler<R> handler) {
            this.handler = handler;
        }

        @Override
        public int size() {
            return handler.size();
        }

        @Override
        @SuppressWarnings("ReferenceEquality") // identity first, equals for handlers that make a new resource per read
        public Optional<K> resourceAt(int index) {
            R resource = handler.getResource(index);
            if (resource.isEmpty()) {
                return Optional.empty();
            }
            if (resource != lastRead && !resource.equals(lastRead)) {
                lastRead = resource;
                lastReadKey = Optional.of(toKey.apply(resource));
            }
            return lastReadKey;
        }

        @Override
        public long amountAt(int index) {
            return handler.getAmountAsLong(index);
        }

        @Override
        public long insert(K key, long amount, TransactionContext transaction) {
            return handler.insert(resourceOf(key), Ints.saturatedCast(amount), transaction);
        }

        @Override
        public long extract(K key, long amount, TransactionContext transaction) {
            return handler.extract(resourceOf(key), Ints.saturatedCast(amount), transaction);
        }

        private R resourceOf(K key) {
            R resource = lastResource;
            if (resource == null || !key.equals(lastKey)) {
                resource = toResource.apply(key);
                lastResource = resource;
                lastKey = key;
            }
            return resource;
        }

        @Override
        public boolean transactional() {
            return true;
        }
    }
}
