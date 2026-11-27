package dev.silencebound.maxtech.lib.api.inventory;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import java.util.Objects;
import java.util.function.Predicate;
import org.jetbrains.annotations.ApiStatus;

/**
 * A {@link ResourceTank} of fluids, amounts in mB: the tank Lib's fluid handlers, bucket clicks in menus and tank
 * gauges work with. Made by {@link #create}; implemented by Lib only. Same threading and rules as {@link ResourceTank}.
 */
@ApiStatus.NonExtendable
public interface FluidTank extends ResourceTank<FluidKey> {
    /** An empty fluid tank of {@code capacity} mB (at least 1) taking any fluid. */
    static FluidTank create(long capacity, Runnable onChange) {
        return create(capacity, key -> true, onChange);
    }

    /** An empty fluid tank of {@code capacity} mB (at least 1) taking what {@code filter} accepts. */
    static FluidTank create(long capacity, Predicate<? super FluidKey> filter, Runnable onChange) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(onChange, "onChange");
        if (capacity < 1) {
            throw new IllegalArgumentException("Tank capacity must be at least 1, got " + capacity);
        }
        return InventoryFactory.get().fluidTank(capacity, filter, onChange);
    }
}
