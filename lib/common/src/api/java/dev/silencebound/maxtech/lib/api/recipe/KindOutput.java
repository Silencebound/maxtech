package dev.silencebound.maxtech.lib.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.ApiStatus;

/**
 * A tank output of a machine recipe: {@code amount} of exactly {@code resource}, put into the machine's output tanks.
 * Always produced (chances are for item outputs only). Immutable; safe from any thread.
 *
 * @param resource
 *            what is produced; unknown only on a client that lacks the kind (JSON refuses it), and then the recipe
 *            never runs there
 * @param amount
 *            how much per run, in the kind's unit, at least 1
 */
@ApiStatus.Experimental
public record KindOutput(KindKey<?> resource, long amount) {
    /**
     * JSON form: {@code {"resource": <KindKey>, "amount": n}}. An unknown kind or resource, or an amount below 1, fails
     * decoding, so the recipe is logged and skipped.
     */
    public static final Codec<KindOutput> CODEC = RecordCodecBuilder
            .<Raw>create(i -> i.group(KindKey.CODEC.fieldOf("resource").forGetter(Raw::resource),
                    Codec.LONG.fieldOf("amount").forGetter(Raw::amount)).apply(i, Raw::new))
            .flatXmap(KindOutput::decode, output -> DataResult.success(new Raw(output.resource, output.amount)));
    /**
     * Network form: the resource ({@link KindKey#CLIENTBOUND_STREAM_CODEC}), then the amount; an amount below 1 fails
     * with a {@link DecoderException}.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, KindOutput> STREAM_CODEC = StreamCodec.composite(
            KindKey.CLIENTBOUND_STREAM_CODEC, KindOutput::resource, ByteBufCodecs.VAR_LONG, KindOutput::amount,
            KindOutput::received);

    /** Throws {@link IllegalArgumentException} for an amount below 1. */
    public KindOutput {
        if (amount < 1) {
            throw new IllegalArgumentException("Kind output amount must be at least 1, was " + amount);
        }
    }

    private static KindOutput received(KindKey<?> resource, long amount) {
        if (amount < 1) {
            throw new DecoderException("Kind output amount " + amount);
        }
        return new KindOutput(resource, amount);
    }

    private static DataResult<KindOutput> decode(Raw raw) {
        if (raw.resource.isUnknown()) {
            return DataResult.error(() -> "Unknown resource kind " + raw.resource.kindId());
        }
        if (raw.amount < 1) {
            return DataResult.error(() -> "Kind output amount must be at least 1, was " + raw.amount);
        }
        return DataResult.success(new KindOutput(raw.resource, raw.amount));
    }

    private record Raw(KindKey<?> resource, long amount) {
    }
}
