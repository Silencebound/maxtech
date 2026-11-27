package dev.silencebound.maxtech.lib.api.resource;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * A saved resource of a kind no loaded mod registers, usually because its mod was removed. It keeps the whole saved
 * tag, {@code kind} included, and saves it back unchanged, so the contents come back when the mod does. Holders mark it
 * as unknown, choose how its amount shows (its kind's unit says nothing about the missing kind), and never move it.
 * Immutable as long as nobody changes {@link #raw()}; any thread.
 *
 * <p>
 * Over the network only the kind id is sent; a receiver gets a {@code raw} that holds only {@code kind}, which still
 * saves and loads as this kind.
 *
 * @param kind
 *            the id of the missing kind
 * @param raw
 *            the saved tag as it was loaded, with {@code kind} naming the missing kind
 */
@ApiStatus.Experimental
public record UnknownResource(Identifier kind, CompoundTag raw) {
    private static final Codec<UnknownResource> RAW_CODEC = new RawCodec();

    /**
     * The kind of every unknown resource, {@code maxtech_lib:unknown}. Not registered: {@link ResourceKinds#byId} never
     * returns it, its {@link ResourceKind#index()} is -1, and {@link KindKey#CODEC} saves its keys from {@link #raw()}
     * under the missing kind's id instead of its own. Its codec and its fields are both the whole saved tag, kind
     * included.
     */
    public static final ResourceKind<UnknownResource> KIND = new ResourceKind<>(
            Identifier.fromNamespaceAndPath("maxtech_lib", "unknown"), RAW_CODEC, MapCodec.assumeMapUnsafe(RAW_CODEC),
            StreamCodec.of(UnknownResource::refuseEncode, UnknownResource::refuseDecode), key -> key, AmountUnit.ITEMS,
            -1);

    /**
     * Throws {@link NullPointerException} for a null kind or tag and {@link IllegalArgumentException} when
     * {@code raw}'s {@code kind} is not {@code kind}: without it the tag would save as nothing that loads.
     */
    public UnknownResource {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(raw, "raw");
        if (!Objects.equals(Identifier.tryParse(raw.getStringOr(KindKey.KIND_FIELD, "")), kind)) {
            throw new IllegalArgumentException(
                    "Unknown resource of kind " + kind + " with a tag of kind " + raw.get(KindKey.KIND_FIELD));
        }
    }

    /** {@code raw} saved under the missing kind {@code kind}, as the key {@link KindKey#CODEC} decodes it to. */
    public static KindKey<UnknownResource> key(Identifier kind, CompoundTag raw) {
        return new KindKey<>(KIND, new UnknownResource(kind, raw));
    }

    /** What a receiver gets for a kind it does not have: a tag with only the kind id. */
    static KindKey<UnknownResource> received(Identifier kind) {
        CompoundTag raw = new CompoundTag();
        raw.putString(KindKey.KIND_FIELD, kind.toString());
        return key(kind, raw);
    }

    private static void refuseEncode(RegistryFriendlyByteBuf buffer, UnknownResource resource) {
        throw new EncoderException("Unknown resources are sent through KindKey.CLIENTBOUND_STREAM_CODEC only");
    }

    private static UnknownResource refuseDecode(RegistryFriendlyByteBuf buffer) {
        throw new DecoderException("Unknown resources are received through KindKey.CLIENTBOUND_STREAM_CODEC only");
    }

    /** The saved tag both ways; decoding reads the kind id from the tag's {@code kind}. */
    private static final class RawCodec implements Codec<UnknownResource> {
        @Override
        public <T> DataResult<Pair<UnknownResource, T>> decode(DynamicOps<T> ops, T input) {
            return ops.getMap(input).flatMap(map -> {
                T kind = map.get(KindKey.KIND_FIELD);
                if (kind == null) {
                    return DataResult.error(() -> "No " + KindKey.KIND_FIELD + " in " + input);
                }
                return Identifier.CODEC.parse(ops, kind).flatMap(id -> {
                    if (!(ops.convertTo(NbtOps.INSTANCE, input) instanceof CompoundTag raw)) {
                        return DataResult.error(() -> "Not a compound: " + input);
                    }
                    return DataResult.success(Pair.of(new UnknownResource(id, raw), ops.empty()));
                });
            });
        }

        @Override
        public <T> DataResult<T> encode(UnknownResource resource, DynamicOps<T> ops, T prefix) {
            T saved = NbtOps.INSTANCE.convertTo(ops, resource.raw);
            return prefix.equals(ops.empty())
                    ? DataResult.success(saved)
                    : ops.getMap(saved).flatMap(map -> ops.mergeToMap(prefix, map));
        }
    }
}
