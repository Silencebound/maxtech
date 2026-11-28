package dev.silencebound.maxtech.lib.api.menu;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The current values of one open menu. On the client: what the server sent last, or zero, the enum's first constant and
 * no resource before the first values arrive (on the first tick after the menu opens). On the server: what the getters
 * returned when the menu last read them. Reads never allocate. Each method throws {@link IllegalArgumentException} for
 * a handle from another declaration (wrong position or kind). Main thread of its side.
 */
public interface MenuValueView {
    /** The int value. */
    int get(IntValue value);

    /** The long value. */
    long get(LongValue value);

    /** The enum constant; the enum's first constant before the first values arrive. */
    <E extends Enum<E>> E get(EnumValue<E> value);

    /** Stored energy in MU. */
    long stored(EnergyValue value);

    /** Energy capacity in MU. */
    long capacity(EnergyValue value);

    /** Work done. */
    long current(ProgressValue value);

    /** Work in total; zero when the host reports none. */
    long total(ProgressValue value);

    /**
     * The fluid of a tank declared as a fluid tank (the fluid {@code tank}, or a tank of {@code ResourceKinds.FLUID}),
     * with its components; empty when the tank is, or holds unknown contents. Throws {@link IllegalArgumentException}
     * for a tank of another or any kind.
     */
    Optional<FluidKey> fluid(TankValue value);

    /**
     * The resource of any tank, with its kind; empty when the tank is. Unknown contents (a kind the client lacks, or a
     * saved resource that no longer loads) are an {@link dev.silencebound.maxtech.lib.api.resource.UnknownResource
     * UnknownResource}, which screens show as unknown rather than empty.
     */
    Optional<KindKey<?>> resource(TankValue value);

    /**
     * The kind the tank was declared with: {@code ResourceKinds.FLUID} for a fluid tank, the given kind for a typed
     * tank, null for a {@code kindTank} of any kind. A screen takes the unit for an empty tank from it
     * ({@code kind.unit()}, falling back to a unit of its own when null). Loads {@code ResourceKinds} for fluid tanks.
     */
    @Nullable
    ResourceKind<?> kind(TankValue value);

    /** Amount in the resource's unit (mB for fluids). */
    long amount(TankValue value);

    /** Capacity in the resource's unit. */
    long capacity(TankValue value);
}
