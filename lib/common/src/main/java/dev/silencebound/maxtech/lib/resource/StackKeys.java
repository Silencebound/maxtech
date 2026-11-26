package dev.silencebound.maxtech.lib.resource;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
// PLATFORM-LEAK: transfers run on NeoForge's resources (ADR-0002); keys convert at that boundary.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.resource.Resource;

/**
 * Conversions between {@link StackKey}s and NeoForge's transfer resources, which carry the same item or fluid and
 * patch. Each call allocates the result (resources without components are NeoForge's shared defaults), so convert once
 * per batch, not per item. Any thread.
 */
public final class StackKeys {
    private StackKeys() {
    }

    /** The key of an item resource. Throws {@link IllegalArgumentException} for the empty resource. */
    public static ItemKey of(ItemResource resource) {
        if (resource.isEmpty()) {
            throw new IllegalArgumentException("Key of the empty item resource");
        }
        return ItemKey.of(resource.getItem(), resource.getComponentsPatch());
    }

    /** The key of a fluid resource. Throws {@link IllegalArgumentException} for the empty resource. */
    public static FluidKey of(FluidResource resource) {
        if (resource.isEmpty()) {
            throw new IllegalArgumentException("Key of the empty fluid resource");
        }
        return FluidKey.of(resource.getFluid(), resource.getComponentsPatch());
    }

    /** The key of an item or fluid resource. Throws for an empty one or another kind of resource. */
    public static StackKey of(Resource resource) {
        return switch (resource) {
            case ItemResource item -> of(item);
            case FluidResource fluid -> of(fluid);
            default -> throw new IllegalArgumentException("Not an item or fluid resource: " + resource);
        };
    }

    public static ItemResource toResource(ItemKey key) {
        return ItemResource.of(key.item(), key.components());
    }

    public static FluidResource toResource(FluidKey key) {
        return FluidResource.of(key.fluid(), key.components());
    }

    /** The item or fluid resource of {@code key}. */
    public static Resource toResource(StackKey key) {
        return switch (key) {
            case ItemKey item -> toResource(item);
            case FluidKey fluid -> toResource(fluid);
        };
    }
}
