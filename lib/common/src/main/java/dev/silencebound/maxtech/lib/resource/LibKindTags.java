package dev.silencebound.maxtech.lib.resource;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.KindTags;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/**
 * Item and fluid tags for {@link KindTags}, which port filters and machine recipe inputs use: each tag resolves to its
 * {@link TagKey} once, tests read the holder's tags.
 */
public final class LibKindTags {
    private LibKindTags() {
    }

    /** Registers the tag support; Lib calls it once during its construction. */
    public static void register() {
        KindTags.register(ResourceKinds.ITEM, id -> {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
            return (ItemKey key) -> key.item().builtInRegistryHolder().is(tag);
        });
        KindTags.register(ResourceKinds.FLUID, id -> {
            TagKey<Fluid> tag = TagKey.create(Registries.FLUID, id);
            return (FluidKey key) -> key.fluid().builtInRegistryHolder().is(tag);
        });
    }
}
