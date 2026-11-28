package dev.silencebound.maxtech.lib.inventory;

import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.InventoryFactory;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.inventory.SlotRole;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Lib's {@link InventoryFactory.Factory}, installed in {@code MaxtechLib.init}. */
public final class LibInventoryFactory implements InventoryFactory.Factory {
    @Override
    public ItemSlots items(SlotRole[] roles, @Nullable Predicate<ItemStack>[] filters, int[] limits,
            IntConsumer onChange) {
        return new LibItemSlots(roles, filters, limits, onChange);
    }

    @Override
    @SuppressWarnings("unchecked") // a fluid tank is a tank of FluidKey, which K is when the kind is FLUID
    public <K> ResourceTank<K> tank(ResourceKind<K> kind, long capacity, Predicate<? super K> filter,
            Runnable onChange) {
        if (kind == ResourceKinds.FLUID) {
            return (ResourceTank<K>) new LibFluidTank(capacity, (Predicate<? super FluidKey>) filter, onChange);
        }
        return new LibResourceTank<>(kind, capacity, filter, onChange);
    }

    @Override
    public FluidTank fluidTank(long capacity, Predicate<? super FluidKey> filter, Runnable onChange) {
        return new LibFluidTank(capacity, filter, onChange);
    }
}
