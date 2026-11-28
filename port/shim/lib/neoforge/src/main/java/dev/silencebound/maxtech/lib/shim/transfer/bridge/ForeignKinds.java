package dev.silencebound.maxtech.lib.shim.transfer.bridge;

import com.google.common.primitives.Ints;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindHandler;
import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy.LegacyDirect;
import dev.silencebound.maxtech.lib.shim.transfer.resource.Resource;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The 1.21.1 side of {@code ResourceHandlerBridge}: a 1.21.1 handler behind a {@link LegacyDirect} wrapper becomes a
 * {@link KindHandler} that is not {@linkplain KindHandler#transactional() transactional}, so Lib's ports move through
 * it foreign side last ({@code KindMoves}): simulate it, move Lib's side in a nested transaction, carry it out at once
 * and keep only what it really moved. Its moves skip the deferred {@code LegacyLedger}, whose commit could no longer
 * match what the other side already counted. Reads go to the bridge's own wrapper. Server thread only.
 */
public final class ForeignKinds {
    private ForeignKinds() {
    }

    /**
     * {@return a non-transactional handler over {@code handler} when it is a {@link LegacyDirect} that wants direct
     * moves, else {@code wrapped}}
     */
    @SuppressWarnings("unchecked") // a ResourceHandler<R> that is a LegacyDirect is one of the same resource type
    public static <K, R extends Resource> KindHandler<K> wrap(ResourceHandler<R> handler, KindHandler<K> wrapped,
            Function<K, R> toResource) {
        if (handler instanceof LegacyDirect<?> direct && direct.direct()) {
            return new Foreign<>((LegacyDirect<R>) direct, wrapped, toResource);
        }
        return wrapped;
    }

    private static final class Foreign<K, R> implements KindHandler<K> {
        private final LegacyDirect<R> direct;
        private final KindHandler<K> reads;
        private final Function<K, R> toResource;
        private @Nullable K lastKey;
        private @Nullable R lastResource;

        Foreign(LegacyDirect<R> direct, KindHandler<K> reads, Function<K, R> toResource) {
            this.direct = direct;
            this.reads = reads;
            this.toResource = toResource;
        }

        @Override
        public int size() {
            return reads.size();
        }

        @Override
        public Optional<K> resourceAt(int index) {
            return reads.resourceAt(index);
        }

        @Override
        public long amountAt(int index) {
            return reads.amountAt(index);
        }

        @Override
        public boolean transactional() {
            return false;
        }

        @Override
        public long insert(K key, long amount, TransactionContext transaction) {
            return amount <= 0 ? 0 : direct.insertNow(resourceOf(key), Ints.saturatedCast(amount));
        }

        @Override
        public long extract(K key, long amount, TransactionContext transaction) {
            return amount <= 0 ? 0 : direct.extractNow(resourceOf(key), Ints.saturatedCast(amount));
        }

        @Override
        public long simulateInsert(K key, long amount, TransactionContext transaction) {
            return amount <= 0 ? 0 : direct.simulateInsertNow(resourceOf(key), Ints.saturatedCast(amount));
        }

        @Override
        public long simulateExtract(K key, long amount, TransactionContext transaction) {
            return amount <= 0 ? 0 : direct.simulateExtractNow(resourceOf(key), Ints.saturatedCast(amount));
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
        public String toString() {
            return "ForeignKinds.Foreign{" + direct + "}";
        }
    }
}
