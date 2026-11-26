package dev.silencebound.maxtech.storage.neoforge.compat.ae2;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Converts between Storage's keys and AE2's. Items and fluids map one to one (same item or fluid, same component patch;
 * AE2 counts fluids in mB like Storage); other AE2 key types (from addons) have no Storage key and are left out.
 * Amounts need no conversion: both sides count in {@code long}.
 *
 * <p>
 * An instance caches what it converted, since AE2 asks the mounted Storage network for its whole contents every tick a
 * terminal is open and each conversion allocates a stack. The cache starts over past {@link #LIMIT} entries and lives
 * as long as its owner (one mount), so it never outlives a server. Server thread only.
 */
final class Ae2Keys {
    /** Entries per direction before the cache starts over. */
    static final int LIMIT = 8192;

    private final Map<StackKey, AEKey> toAe = new HashMap<>();
    private final Map<AEKey, StackKey> toStorage = new HashMap<>();

    /** AE2's key for {@code key}, or null for an empty one. Not cached. */
    static @Nullable AEKey ae(StackKey key) {
        return switch (key) {
            case ItemKey item -> AEItemKey.of(StackKeys.toResource(item));
            case FluidKey fluid -> AEFluidKey.of(StackKeys.toResource(fluid));
        };
    }

    /** Storage's key for {@code key}, or null for a key type Storage does not hold. Not cached. */
    static @Nullable StackKey storage(AEKey key) {
        if (key instanceof AEItemKey item) {
            return StackKeys.of(item.toResource());
        }
        if (key instanceof AEFluidKey fluid) {
            return StackKeys.of(fluid.toResource());
        }
        return null;
    }

    /** {@link #ae(StackKey)}, cached. */
    @Nullable
    AEKey toAe(StackKey key) {
        AEKey cached = toAe.get(key);
        if (cached == null) {
            cached = ae(key);
            if (cached != null) {
                if (toAe.size() >= LIMIT) {
                    toAe.clear();
                }
                toAe.put(key, cached);
            }
        }
        return cached;
    }

    /** {@link #storage(AEKey)}, cached. */
    @Nullable
    StackKey toStorage(AEKey key) {
        StackKey cached = toStorage.get(key);
        if (cached == null) {
            cached = storage(key);
            if (cached != null) {
                if (toStorage.size() >= LIMIT) {
                    toStorage.clear();
                }
                toStorage.put(key, cached);
            }
        }
        return cached;
    }
}
