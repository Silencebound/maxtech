package dev.silencebound.maxtech.lib.content;

import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;

/**
 * Items an addon asked Lib to list in a creative tab it does not own (the api conduit builder's {@code creativeTab}),
 * in request order. Requests come in during parallel mod construction; Lib's loader side reads them when a tab's
 * contents are built.
 */
public final class CreativeTabAdditions {
    private static final List<Addition> ADDITIONS = new CopyOnWriteArrayList<>();

    private CreativeTabAdditions() {
    }

    /** Lists {@code item} in {@code tab}. Call during mod construction. */
    public static void add(ResourceKey<CreativeModeTab> tab, RegistryEntry<Item, ?> item) {
        ADDITIONS.add(new Addition(tab, item));
    }

    /** Every addition so far, in request order. */
    public static List<Addition> all() {
        return ADDITIONS;
    }

    /** One item for one tab. */
    public record Addition(ResourceKey<CreativeModeTab> tab, RegistryEntry<Item, ?> item) {
    }
}
