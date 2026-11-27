package dev.silencebound.maxtech.lib.neoforge.api.inventory;

import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.SlotRole;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * The NeoForge handlers of Lib's inventories, for capability registration:
 *
 * <pre>{@code
 * event.registerBlockEntity(Capabilities.Item.BLOCK, LAB_HUB.get(), (hub, side) -> Handlers.items(hub.slots()));
 * event.registerBlockEntity(Capabilities.Fluid.BLOCK, LAB_HUB.get(), (hub, side) -> Handlers.fluids(hub.tank()));
 * }</pre>
 *
 * The handlers are Lib's own and take part in transfer transactions: what a pipe or hopper moves is journaled and
 * rolled back with the transaction, and the inventory's change listener runs once the root transaction commits.
 * {@link #items} applies the slots' {@link SlotRole}s (inputs take what their filter accepts, outputs give, display
 * slots do neither; every slot reports its real capacity, so comparators read a full output slot as full, and one that
 * takes nothing refuses in {@code isValid} and {@code insert}; slots holding an unknown item take nothing and give
 * nothing). {@link #fluids} takes and gives what the tank's filter allows. Each returns the same handler for the same
 * inventory every time. Server thread, like the inventories.
 */
public final class Handlers {
    private static volatile @Nullable Bridge bridge;

    private Handlers() {
    }

    /** The item handler of {@code slots}, which must come from {@link ItemSlots#builder}. */
    public static ResourceHandler<ItemResource> items(ItemSlots slots) {
        return bridge().items(slots);
    }

    /** The fluid handler of {@code tank}, which must come from {@link FluidTank#create}. */
    public static ResourceHandler<FluidResource> fluids(FluidTank tank) {
        return bridge().fluids(tank);
    }

    /** Sets Lib's bridge; Lib calls it once during its construction, and a second call throws. */
    @ApiStatus.Internal
    public static synchronized void install(Bridge source) {
        if (bridge != null) {
            throw new IllegalStateException("A handler bridge is already installed");
        }
        bridge = source;
    }

    private static Bridge bridge() {
        Bridge source = bridge;
        if (source == null) {
            throw new IllegalStateException("MaxTech Lib has not set up its handlers yet");
        }
        return source;
    }

    /** Lib's side of {@link Handlers}. */
    @ApiStatus.Internal
    public interface Bridge {
        /** See {@link Handlers#items}. */
        ResourceHandler<ItemResource> items(ItemSlots slots);

        /** See {@link Handlers#fluids}. */
        ResourceHandler<FluidResource> fluids(FluidTank tank);
    }
}
