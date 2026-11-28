package dev.silencebound.maxtech.lib.neoforge.transfer;

import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindBridge;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindHandler;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.platform.KindLookup;
import dev.silencebound.maxtech.lib.platform.KindStorage;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A kind's {@link KindBridge} as the {@link KindLookup} Lib's ports use in common code: each cache wraps the bridge's
 * handler once and keeps the wrapper while the handler instance stays the same.
 */
public final class BridgeLookup<K> implements KindLookup<K> {
    private final KindBridge<K> bridge;

    public BridgeLookup(KindBridge<K> bridge) {
        this.bridge = bridge;
    }

    @Override
    public CachedBlockLookup<KindStorage<K>> cache(ServerLevel level, BlockPos pos, Direction side,
            BooleanSupplier isValid, Runnable onInvalidate) {
        KindBridge.Cache<K> cache = bridge.cache(level, pos, side, isValid, onInvalidate);
        return new CachedBlockLookup<>() {
            private @Nullable KindHandler<K> last;
            private @Nullable KindStorage<K> wrapped;

            @Override
            @SuppressWarnings("ReferenceEquality") // the same handler instance keeps its wrapper
            public @Nullable KindStorage<K> find() {
                KindHandler<K> current = cache.handler().orElse(null);
                if (current != last) {
                    last = current;
                    wrapped = current == null ? null : new Storage<>(current);
                }
                return wrapped;
            }
        };
    }

    private record Storage<K>(KindHandler<K> handler) implements KindStorage<K> {
        @Override
        public int size() {
            return handler.size();
        }

        @Override
        public @Nullable K resourceAt(int index) {
            return handler.resourceAt(index).orElse(null);
        }

        @Override
        public long amountAt(int index) {
            return handler.amountAt(index);
        }

        @Override
        public long insert(K key, long amount, TransactionContext transaction) {
            return handler.insert(key, amount, transaction);
        }

        @Override
        public long extract(K key, long amount, TransactionContext transaction) {
            return handler.extract(key, amount, transaction);
        }

        @Override
        public boolean transactional() {
            return handler.transactional();
        }

        @Override
        public long simulateInsert(K key, long amount, TransactionContext transaction) {
            return handler.simulateInsert(key, amount, transaction);
        }

        @Override
        public long simulateExtract(K key, long amount, TransactionContext transaction) {
            return handler.simulateExtract(key, amount, transaction);
        }
    }
}
