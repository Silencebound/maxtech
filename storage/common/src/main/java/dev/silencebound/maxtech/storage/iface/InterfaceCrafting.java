package dev.silencebound.maxtech.storage.iface;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.NetworkState;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The interface's hook for the crafting card (M7 C21; the card's behaviour is M7c area D's). An interface has one card
 * slot that takes items in {@link #CARDS}. When its stock task finds a stock slot waiting for a key the network lacks
 * and the slot holds a card, it asks the registered {@link Handler}, which may request a crafting job for the missing
 * amount. The task keeps waiting either way; the key's arrival in the network wakes it. Server thread only.
 */
public final class InterfaceCrafting {
    /** Items the interface's card slot takes; the crafting card joins it through datagen. */
    public static final TagKey<Item> CARDS = TagKey.create(Registries.ITEM, MaxtechStorage.id("interface_cards"));

    /** What the crafting card registers. */
    public interface Handler {
        /**
         * Stock slot {@code slot} of {@code device} wants {@code missing} more of {@code key}, which {@code network}
         * holds none of, and {@code card} sits in the interface's card slot. Called from the stock task each time it
         * runs and finds the slot waiting, so a handler that requests a job must remember it and not request again
         * while it runs. Must not block.
         */
        void missing(InterfaceDevice device, NetworkState network, ItemStack card, int slot, StackKey key,
                long missing);
    }

    private static @Nullable Handler handler;

    private InterfaceCrafting() {
    }

    /** Sets the handler (area D's crafting card); null removes it. */
    public static void setHandler(@Nullable Handler value) {
        handler = value;
    }

    /** The handler, or null when no card behaviour is registered. */
    public static @Nullable Handler handler() {
        return handler;
    }
}
