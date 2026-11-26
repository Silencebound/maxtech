package dev.silencebound.maxtech.lib.platform;

import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** An item capability without context. */
public interface ItemLookup<T> {
    /** The capability of {@code stack}, or {@code null} if it has none (always {@code null} for an empty stack). */
    @Nullable
    T find(ItemStack stack);
}
