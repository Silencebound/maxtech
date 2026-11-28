package dev.silencebound.maxtech.farms.hub;

import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
// PLATFORM-LEAK: NeoForge's transfer types; Lib's handlers of its inventories are NeoForge handlers.
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * Lib's transactional handlers of Lib inventories ({@code lib.neoforge.api.inventory.Handlers}), which only Farms'
 * NeoForge project can see; it installs them here during construction, so Farms' common code (hand use, the fluid port,
 * the capabilities' item view) moves fluids and items through Lib's own handlers. Installed once; any thread.
 */
public final class HubHandlers {
    private static volatile @Nullable Bridge bridge;

    private HubHandlers() {
    }

    /** Sets the bridge; once, during mod construction. A second call throws {@link IllegalStateException}. */
    public static synchronized void install(Bridge source) {
        if (bridge != null) {
            throw new IllegalStateException("Hub handlers already installed");
        }
        bridge = source;
    }

    static ResourceHandler<ItemResource> items(ItemSlots slots) {
        return bridge().items(slots);
    }

    static ResourceHandler<FluidResource> fluids(FluidTank tank) {
        return bridge().fluids(tank);
    }

    private static Bridge bridge() {
        Bridge source = bridge;
        if (source == null) {
            throw new IllegalStateException("Farms has not installed its hub handlers yet");
        }
        return source;
    }

    /** Lib's {@code Handlers}, passed through. */
    public interface Bridge {
        /** {@code Handlers.items(slots)}. */
        ResourceHandler<ItemResource> items(ItemSlots slots);

        /** {@code Handlers.fluids(tank)}. */
        ResourceHandler<FluidResource> fluids(FluidTank tank);
    }
}
