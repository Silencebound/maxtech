package dev.silencebound.maxtech.lib.transfer;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.transfer.PortFilter;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/** Item and fluid tags in port filters: each tag resolves to its {@link TagKey} once, tests read the holder's tags. */
public final class PortTags {
    private PortTags() {
    }

    /** Registers the tag support; Lib calls it once during its construction. */
    public static void register() {
        PortFilter.registerTags(ResourceKinds.ITEM, id -> {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
            return (ItemKey key) -> key.item().builtInRegistryHolder().is(tag);
        });
        PortFilter.registerTags(ResourceKinds.FLUID, id -> {
            TagKey<Fluid> tag = TagKey.create(Registries.FLUID, id);
            return (FluidKey key) -> key.fluid().builtInRegistryHolder().is(tag);
        });
    }
}
