package dev.silencebound.maxtech.lib.platform;

// Wrapping NeoForge's transfer types would duplicate their resource and transaction model on hot paths.
// PLATFORM-LEAK: transfer types are used directly in common (ADR-0002).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Sided lookups for other blocks' item and fluid storage, as NeoForge's transfer handlers. Cables, devices and machines
 * reach foreign inventories only through these, so every transfer call site starts here and is easy to find. Per-tick
 * code holds a {@link BlockLookup#cache} instead of calling {@link BlockLookup#find}.
 */
public interface TransferHelper {
    /** Item handlers exposed by blocks (chests, machines, other mods' storage). */
    BlockLookup<ResourceHandler<ItemResource>> items();

    /** Fluid handlers exposed by blocks (tanks, machines, other mods' storage). */
    BlockLookup<ResourceHandler<FluidResource>> fluids();
}
