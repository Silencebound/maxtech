package dev.silencebound.maxtech.lib.shim.transfer.bridge;

import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.access.ItemAccess;
import dev.silencebound.maxtech.lib.shim.transfer.energy.EnergyHandler;
import dev.silencebound.maxtech.lib.shim.transfer.fluid.FluidResource;
import dev.silencebound.maxtech.lib.shim.transfer.item.ItemResource;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.capabilities.ItemCapability;
import org.jspecify.annotations.Nullable;

/**
 * Port stub of 26.1's {@code net.neoforged.neoforge.capabilities.Capabilities} (resource-handler capabilities). The
 * constants are null until the transfer package bridges them to 1.21.1's IItemHandler/IFluidHandler/IEnergyStorage.
 */
public final class Capabilities {
    public static final class Energy {
        public static final BlockCapability<EnergyHandler, @Nullable Direction> BLOCK = null;
        public static final EntityCapability<EnergyHandler, @Nullable Direction> ENTITY = null;
        public static final ItemCapability<EnergyHandler, ItemAccess> ITEM = null;

        private Energy() {}
    }

    public static final class Fluid {
        public static final BlockCapability<ResourceHandler<FluidResource>, @Nullable Direction> BLOCK = null;
        public static final EntityCapability<ResourceHandler<FluidResource>, @Nullable Direction> ENTITY = null;
        public static final ItemCapability<ResourceHandler<FluidResource>, ItemAccess> ITEM = null;

        private Fluid() {}
    }

    public static final class Item {
        public static final BlockCapability<ResourceHandler<ItemResource>, @Nullable Direction> BLOCK = null;
        public static final EntityCapability<ResourceHandler<ItemResource>, @Nullable Void> ENTITY = null;
        public static final EntityCapability<ResourceHandler<ItemResource>, @Nullable Direction> ENTITY_AUTOMATION = null;
        public static final ItemCapability<ResourceHandler<ItemResource>, ItemAccess> ITEM = null;

        private Item() {}
    }

    private Capabilities() {}
}
