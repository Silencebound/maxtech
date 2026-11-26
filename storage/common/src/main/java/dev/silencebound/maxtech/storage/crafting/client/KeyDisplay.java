package dev.silencebound.maxtech.storage.crafting.client;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** How the crafting screens draw a key: its item (a fluid as its bucket), its name and amounts. Client only. */
public final class KeyDisplay {
    private KeyDisplay() {
    }

    /** The stack to draw for {@code key}; a fluid without a bucket draws as an empty bucket. */
    public static ItemStack icon(StackKey key) {
        return switch (key) {
            case ItemKey item -> item.toStack(1);
            case FluidKey fluid -> {
                ItemStack bucket = new ItemStack(fluid.fluid().getBucket());
                yield bucket.isEmpty() ? new ItemStack(Items.BUCKET) : bucket;
            }
        };
    }

    public static Component name(StackKey key) {
        return switch (key) {
            case ItemKey item -> item.toStack(1).getHoverName();
            case FluidKey fluid -> StackKeys.toResource(fluid).getHoverName();
        };
    }

    /** {@code amount} of {@code key} in short form: mB for fluids. */
    public static String amount(StackKey key, long amount) {
        return key instanceof FluidKey ? AmountFormat.fluid(amount) : AmountFormat.compact(amount);
    }
}
