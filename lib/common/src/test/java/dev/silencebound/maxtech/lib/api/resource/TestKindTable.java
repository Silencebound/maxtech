package dev.silencebound.maxtech.lib.api.resource;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * A kind registry of its own for JUnit tests outside this package, which cannot load {@link ResourceKinds} (its
 * built-in kinds need registries).
 */
public final class TestKindTable {
    private final KindTable table = new KindTable();

    /**
     * A kind saved under {@code value}; see
     * {@link ResourceKinds#register(Identifier, Codec, StreamCodec, java.util.function.UnaryOperator, AmountUnit)}.
     */
    public <K> ResourceKind<K> register(Identifier id, Codec<K> codec,
            StreamCodec<RegistryFriendlyByteBuf, K> streamCodec, AmountUnit unit) {
        return table.register(id, codec, streamCodec, key -> key, unit);
    }

    /** {@link KindKey#CLIENTBOUND_STREAM_CODEC} over this table. */
    public StreamCodec<RegistryFriendlyByteBuf, KindKey<?>> clientbound() {
        return KindKey.streamCodec(table::byId, false);
    }
}
