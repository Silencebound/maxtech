package dev.silencebound.maxtech.lib.neoforge;

import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.inventory.JournalTank;
import dev.silencebound.maxtech.lib.inventory.LibFluidTank;
import dev.silencebound.maxtech.lib.inventory.LibItemSlots;
import dev.silencebound.maxtech.lib.inventory.LibResourceTank;
import dev.silencebound.maxtech.lib.inventory.MachineMoves;
import dev.silencebound.maxtech.lib.neoforge.api.inventory.Handlers;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** {@link Handlers} over the handlers Lib's inventories already have in common. */
final class LibHandlerBridge implements Handlers.Bridge {
    @Override
    public ResourceHandler<ItemResource> items(ItemSlots slots) {
        if (!(slots instanceof LibItemSlots lib)) {
            throw new IllegalArgumentException("Not Lib's slots: " + slots.getClass().getName());
        }
        return lib.automation();
    }

    @Override
    public ResourceHandler<FluidResource> fluids(FluidTank tank) {
        if (!(tank instanceof LibFluidTank lib)) {
            throw new IllegalArgumentException("Not Lib's tank: " + tank.getClass().getName());
        }
        return lib.handler();
    }

    /** Lib's slots themselves: as a handler they are the machine's view, roles not applied. */
    @Override
    public ResourceHandler<ItemResource> machineItems(ItemSlots slots) {
        if (!(slots instanceof LibItemSlots lib)) {
            throw new IllegalArgumentException("Not Lib's slots: " + slots.getClass().getName());
        }
        return lib;
    }

    @Override
    public <K> Handlers.MachineTank<K> machineTank(ResourceTank<K> tank) {
        if (!(tank instanceof LibResourceTank<K> lib)) {
            throw new IllegalArgumentException("Not Lib's tank: " + tank.getClass().getName());
        }
        return lib.machineView(MachineTankView::new);
    }

    @Override
    public boolean insertAll(ResourceHandler<ItemResource> handler, int from, int to, List<ItemStack> stacks,
            TransactionContext transaction) {
        return MachineMoves.insertAll(handler, from, to, stacks, transaction);
    }

    @Override
    public boolean extractAll(ResourceHandler<ItemResource> handler, int from, int to, List<ItemStack> stacks,
            TransactionContext transaction) {
        return MachineMoves.extractAll(handler, from, to, stacks, transaction);
    }

    /** {@link Handlers.MachineTank} over the tank's common {@link JournalTank}. */
    private record MachineTankView<K>(JournalTank<K> tank) implements Handlers.MachineTank<K> {
        @Override
        public long insert(K key, long amount, TransactionContext transaction) {
            return tank.insert(key, amount, transaction);
        }

        @Override
        public long extract(K key, long amount, TransactionContext transaction) {
            return tank.extract(key, amount, transaction);
        }

        @Override
        public boolean insertExactly(K key, long amount, TransactionContext transaction) {
            return tank.insertExactly(key, amount, transaction);
        }

        @Override
        public boolean extractExactly(K key, long amount, TransactionContext transaction) {
            return tank.extractExactly(key, amount, transaction);
        }
    }
}
