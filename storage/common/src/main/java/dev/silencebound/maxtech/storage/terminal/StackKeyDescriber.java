package dev.silencebound.maxtech.storage.terminal;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.lib.sync.SyncedListIndex;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Describes terminal entries for Lib's client search and sort: an item's hover name (custom names included) or a
 * fluid's name, the namespace of its registry id, and its item or fluid tags. Uses no client-only types, so the
 * GameTests can run the same search on the server.
 */
public final class StackKeyDescriber implements SyncedListIndex.EntryDescriber<StackKey> {
    public static final StackKeyDescriber INSTANCE = new StackKeyDescriber();

    private StackKeyDescriber() {
    }

    @Override
    public String name(StackKey entry) {
        return switch (entry) {
            case ItemKey item -> item.toStack(1).getHoverName().getString();
            case FluidKey fluid -> StackKeys.toResource(fluid).getHoverName().getString();
        };
    }

    @Override
    public String modId(StackKey entry) {
        return switch (entry) {
            case ItemKey item -> BuiltInRegistries.ITEM.getKey(item.item()).getNamespace();
            case FluidKey fluid -> BuiltInRegistries.FLUID.getKey(fluid.fluid()).getNamespace();
        };
    }

    @Override
    public List<String> tags(StackKey entry) {
        List<String> tags = new ArrayList<>();
        switch (entry) {
            case ItemKey item ->
                item.item().builtInRegistryHolder().tags().forEach(tag -> tags.add(tag.location().toString()));
            case FluidKey fluid ->
                fluid.fluid().builtInRegistryHolder().tags().forEach(tag -> tags.add(tag.location().toString()));
        }
        return tags;
    }
}
