package dev.silencebound.maxtech.lib.neoforge.platform;

import dev.silencebound.maxtech.lib.platform.ItemLookup;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.ItemCapability;
import org.jspecify.annotations.Nullable;

/**
 * The NeoForge side of an {@link ItemLookup}. Public so {@code neoforge} glue can unwrap {@link #of(ItemLookup)} and
 * register item providers in {@code RegisterCapabilitiesEvent}.
 */
public record NeoForgeItemLookup<T>(ItemCapability<T, @Nullable Void> capability) implements ItemLookup<T> {
    @Override
    public @Nullable T find(ItemStack stack) {
        return stack.isEmpty() ? null : stack.getCapability(capability);
    }

    /** Throws if {@code lookup} was not created by this platform. */
    public static <T> NeoForgeItemLookup<T> of(ItemLookup<T> lookup) {
        if (lookup instanceof NeoForgeItemLookup<T> neoForge) {
            return neoForge;
        }
        throw new IllegalArgumentException("Lookup was not created by the NeoForge platform: " + lookup);
    }
}
