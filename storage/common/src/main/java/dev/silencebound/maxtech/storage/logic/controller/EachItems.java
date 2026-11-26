package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * The items of item tags as a For each node goes through them (M7L2 row 18): each tag's items in the tag's order, as
 * logic keys, worked out once per tag and kept until the server's tags are bound anew ({@link #tagsChanged}, on
 * {@code /reload} and server start), so a run looks a tag up in a map and allocates nothing. Server thread only.
 */
public final class EachItems {
    private static final Object2ObjectOpenHashMap<TagKey<Item>, List<LogicKey>> TAGS = new Object2ObjectOpenHashMap<>();

    private EachItems() {
    }

    /** The items of {@code tag} in the tag's order; empty for a tag the server does not know. */
    static List<LogicKey> of(TagKey<Item> tag) {
        List<LogicKey> items = TAGS.get(tag);
        if (items == null) {
            List<LogicKey> found = new ArrayList<>();
            BuiltInRegistries.ITEM.get(tag).ifPresent(set -> {
                for (Holder<Item> holder : set) {
                    found.add(LogicKey.of(ItemKey.of(holder.value())));
                }
            });
            items = List.copyOf(found);
            TAGS.put(tag, items);
        }
        return items;
    }

    /** Forgets every tag's items; the loader calls it when the server's tags are bound anew. */
    public static void tagsChanged() {
        TAGS.clear();
    }
}
