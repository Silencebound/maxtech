/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): the resource-handler capabilities of NeoForge
 * 26.1.2's net.neoforged.neoforge.capabilities.Capabilities, package relocated to
 * dev.silencebound.maxtech.lib.shim.transfer.bridge, names moved to the maxtech_lib namespace.
 */

package dev.silencebound.maxtech.lib.shim.transfer.bridge;

import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.access.ItemAccess;
import dev.silencebound.maxtech.lib.shim.transfer.energy.EnergyHandler;
import dev.silencebound.maxtech.lib.shim.transfer.fluid.FluidResource;
import dev.silencebound.maxtech.lib.shim.transfer.item.ItemResource;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.capabilities.ItemCapability;
import org.jspecify.annotations.Nullable;

/**
 * The resource-handler capabilities of NeoForge 26.1, which NeoForge 21.1 does not have. They are separate
 * capabilities named {@code maxtech_lib:item_handler}, {@code maxtech_lib:fluid_handler} and
 * {@code maxtech_lib:energy_handler}; Lib's legacy bridge (package {@code bridge.legacy}) connects the block and item
 * ones to 21.1's {@code IItemHandler}, {@code IFluidHandler} and {@code IEnergyStorage} in both directions. The entity
 * capabilities exist with 26.1's shape but are not bridged.
 */
public final class Capabilities {
    public static final class Energy {
        public static final BlockCapability<EnergyHandler, @Nullable Direction> BLOCK = BlockCapability.createSided(create("energy_handler"), EnergyHandler.class);
        public static final EntityCapability<EnergyHandler, @Nullable Direction> ENTITY = EntityCapability.createSided(create("energy_handler"), EnergyHandler.class);
        public static final ItemCapability<EnergyHandler, ItemAccess> ITEM = ItemCapability.create(create("energy_handler"), EnergyHandler.class, ItemAccess.class);

        private Energy() {}
    }

    public static final class Fluid {
        public static final BlockCapability<ResourceHandler<FluidResource>, @Nullable Direction> BLOCK = BlockCapability.createSided(create("fluid_handler"), ResourceHandler.asClass());
        public static final EntityCapability<ResourceHandler<FluidResource>, @Nullable Direction> ENTITY = EntityCapability.createSided(create("fluid_handler"), ResourceHandler.asClass());
        public static final ItemCapability<ResourceHandler<FluidResource>, ItemAccess> ITEM = ItemCapability.create(create("fluid_handler"), ResourceHandler.asClass(), ItemAccess.class);

        private Fluid() {}
    }

    public static final class Item {
        public static final BlockCapability<ResourceHandler<ItemResource>, @Nullable Direction> BLOCK = BlockCapability.createSided(create("item_handler"), ResourceHandler.asClass());
        /**
         * Capability for the inventory of an entity.
         * If an entity has multiple inventory "subparts", this capability should give a combined view of all the subparts.
         */
        public static final EntityCapability<ResourceHandler<ItemResource>, @Nullable Void> ENTITY = EntityCapability.createVoid(create("item_handler"), ResourceHandler.asClass());
        /**
         * Capability for an inventory of entity that should be accessible to automation,
         * in the sense that droppers, hoppers, and similar modded devices will try to use it.
         */
        public static final EntityCapability<ResourceHandler<ItemResource>, @Nullable Direction> ENTITY_AUTOMATION = EntityCapability.createSided(create("item_handler_automation"), ResourceHandler.asClass());
        public static final ItemCapability<ResourceHandler<ItemResource>, ItemAccess> ITEM = ItemCapability.create(create("item_handler"), ResourceHandler.asClass(), ItemAccess.class);

        private Item() {}
    }

    private static ResourceLocation create(String path) {
        return ResourceLocation.fromNamespaceAndPath("maxtech_lib", path);
    }

    private Capabilities() {}
}
