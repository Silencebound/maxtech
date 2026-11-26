package dev.silencebound.maxtech.lib.api.resource;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * A fluid with its component patch; see {@link StackKey}. Never the empty fluid. Amounts that go with it are mB.
 * Immutable; any thread.
 */
@SuppressWarnings("ReferenceEquality") // fluids are registry singletons and do not override equals
public final class FluidKey implements StackKey {
    /** {@code id} and optional {@code components}; the empty fluid fails to decode. */
    public static final MapCodec<FluidKey> MAP_CODEC = RecordCodecBuilder
            .<FluidKey>mapCodec(
                    i -> i.group(BuiltInRegistries.FLUID.byNameCodec().fieldOf("id").forGetter(FluidKey::fluid),
                            DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY)
                                    .forGetter(FluidKey::components))
                            .apply(i, FluidKey::new))
            .validate(key -> key.fluid == Fluids.EMPTY
                    ? DataResult.error(() -> "Fluid key of the empty fluid")
                    : DataResult.success(key));
    /** The fluid's registry id, then the patch. */
    public static final StreamCodec<RegistryFriendlyByteBuf, FluidKey> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.registry(Registries.FLUID), FluidKey::fluid, DataComponentPatch.STREAM_CODEC,
            FluidKey::components, FluidKey::decoded);

    private final Fluid fluid;
    private final DataComponentPatch components;
    private final int hash;

    private FluidKey(Fluid fluid, DataComponentPatch components) {
        this.fluid = fluid;
        this.components = components;
        // Distinct from an item key with the same identity hash and patch, which can never be equal anyway.
        this.hash = 37 * System.identityHashCode(fluid) + components.hashCode();
    }

    /** The stream codec's constructor: the empty fluid from the network fails decoding, not with a plain exception. */
    private static FluidKey decoded(Fluid fluid, DataComponentPatch components) {
        if (fluid == Fluids.EMPTY) {
            throw new DecoderException("Fluid key of the empty fluid");
        }
        return new FluidKey(fluid, components);
    }

    /** The key of {@code fluid} without components. Throws {@link IllegalArgumentException} for the empty fluid. */
    public static FluidKey of(Fluid fluid) {
        return of(fluid, DataComponentPatch.EMPTY);
    }

    /**
     * The key of {@code fluid} with {@code components}. Throws {@link IllegalArgumentException} for the empty fluid.
     */
    public static FluidKey of(Fluid fluid, DataComponentPatch components) {
        if (fluid == Fluids.EMPTY) {
            throw new IllegalArgumentException("Fluid key of the empty fluid");
        }
        return new FluidKey(fluid, components);
    }

    /** The fluid, never the empty fluid. */
    public Fluid fluid() {
        return fluid;
    }

    @Override
    public DataComponentPatch components() {
        return components;
    }

    @Override
    public Kind kind() {
        return Kind.FLUID;
    }

    /** The {@link Fluid}. */
    @Override
    public Object base() {
        return fluid;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof FluidKey key && hash == key.hash && fluid == key.fluid
                && components.equals(key.components));
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "FluidKey[" + BuiltInRegistries.FLUID.getKey(fluid) + (components.isEmpty() ? "" : " " + components)
                + "]";
    }
}
