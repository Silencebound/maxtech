package dev.silencebound.maxtech.lib.client.conduit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * One value per key, made by the baker the first time the key is asked for and kept until the cache is dropped (the
 * conduit model drops it with the model on a resource reload). Safe from chunk meshing threads; two threads may race to
 * make the same value, and one of them wins. A hit is one map lookup and allocates nothing.
 */
public final class PartCache<K, V> {
    private final ConcurrentHashMap<K, V> values = new ConcurrentHashMap<>();
    private final Function<K, V> baker;
    private final AtomicInteger bakes = new AtomicInteger();

    public PartCache(Function<K, V> baker) {
        this.baker = baker;
    }

    /** The value for {@code key}, made now if it was never asked for. */
    public V get(K key) {
        V value = values.get(key);
        if (value == null) {
            value = baker.apply(key);
            bakes.incrementAndGet();
            V raced = values.putIfAbsent(key, value);
            if (raced != null) {
                value = raced;
            }
        }
        return value;
    }

    /** How many values were made so far, raced ones included; for tests. */
    public int bakes() {
        return bakes.get();
    }

    /** How many keys hold a value. */
    public int size() {
        return values.size();
    }
}
