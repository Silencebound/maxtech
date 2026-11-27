package dev.silencebound.maxtech.lib.inventory;

import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import java.util.function.Predicate;

/** Lib's {@link FluidTank}, with the NeoForge fluid handler {@link #handler()} over it. */
public final class LibFluidTank extends LibResourceTank<FluidKey> implements FluidTank {
    private final FluidTankHandler handler = new FluidTankHandler(this);

    LibFluidTank(long capacity, Predicate<? super FluidKey> filter, Runnable onChange) {
        super(ResourceKinds.FLUID, capacity, filter, onChange);
    }

    /** The tank as a one-index NeoForge fluid handler; the same instance every time. */
    public FluidTankHandler handler() {
        return handler;
    }
}
