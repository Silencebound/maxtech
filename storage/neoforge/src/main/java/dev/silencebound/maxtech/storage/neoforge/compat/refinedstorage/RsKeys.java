package dev.silencebound.maxtech.storage.neoforge.compat.refinedstorage;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import org.jspecify.annotations.Nullable;

/**
 * Converts between Storage's keys and Refined Storage's resources. RS's item and fluid resources ({@code ItemResource},
 * {@code FluidResource}) are classes of its common module marked internal, but they are the only item and fluid keys RS
 * has and its own addons use them. Both sides count fluids in mB (RS's NeoForge bucket is
 * {@code FluidType.BUCKET_VOLUME}), so amounts pass through unchanged.
 */
final class RsKeys {
    /** Who RS's trackers and listeners see moving things through a bridge. */
    static final Actor ACTOR = () -> "MaxTech Storage bridge";

    private RsKeys() {
    }

    /** RS's resource for {@code key}; every Storage key has one. */
    static ResourceKey resource(StackKey key) {
        if (key instanceof FluidKey fluid) {
            return new FluidResource(fluid.fluid(), fluid.components());
        }
        ItemKey item = (ItemKey) key;
        return new ItemResource(item.item(), item.components());
    }

    /** Storage's key for {@code resource}, or null for resources other than items and fluids (from RS addons). */
    static @Nullable StackKey key(ResourceKey resource) {
        if (resource instanceof ItemResource item) {
            return ItemKey.of(item.item(), item.components());
        }
        if (resource instanceof FluidResource fluid) {
            return FluidKey.of(fluid.fluid(), fluid.components());
        }
        return null;
    }

    /** {@code moved} clamped to 0 to {@code asked}: what Storage counts of an answer from RS. */
    static long clamp(long moved, long asked) {
        return moved <= 0 ? 0 : Math.min(moved, asked);
    }
}
