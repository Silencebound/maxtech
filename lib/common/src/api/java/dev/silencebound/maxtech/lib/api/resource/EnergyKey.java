package dev.silencebound.maxtech.lib.api.resource;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.ApiStatus;

/**
 * The one key of {@link ResourceKinds#ENERGY}: MaxTech energy has no variants, so the key only says "energy" and the
 * amount next to it is MU. For gauges and menu values; energy moves through {@code lib.api.energy}, never as a
 * resource. Immutable; any thread.
 */
@ApiStatus.Experimental
public final class EnergyKey {
    /** The only instance. */
    public static final EnergyKey INSTANCE = new EnergyKey();
    /** Saves no fields, so a kind key of energy is just its {@code kind}. */
    public static final MapCodec<EnergyKey> MAP_CODEC = MapCodec.unit(INSTANCE);
    /** {@link #MAP_CODEC} on its own: an empty map. */
    public static final Codec<EnergyKey> CODEC = MAP_CODEC.codec();
    /** Writes nothing. */
    public static final StreamCodec<RegistryFriendlyByteBuf, EnergyKey> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    private EnergyKey() {
    }

    @Override
    public String toString() {
        return "EnergyKey";
    }
}
