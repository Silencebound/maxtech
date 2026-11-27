package dev.silencebound.maxtech.lib.api.resource;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * A saved resource of a kind no loaded mod registers, usually because its mod was removed. It keeps the whole saved
 * tag, {@code kind} included, and saves it back unchanged, so the contents come back when the mod does. Holders show it
 * as "Unknown" and never move it. Immutable as long as nobody changes {@link #raw()}; any thread.
 *
 * <p>
 * Over the network only the kind id is sent; a client receives an empty {@code raw}.
 *
 * @param kind
 *            the id of the missing kind
 * @param raw
 *            the saved tag as it was loaded
 */
@ApiStatus.Experimental
public record UnknownResource(Identifier kind, Tag raw) {
    /**
     * The kind of every unknown resource, {@code maxtech_lib:unknown}. Not registered: {@link ResourceKinds#byId} never
     * returns it, its {@link ResourceKind#index()} is -1, and {@link KindKey#CODEC} saves its keys from {@link #raw()}
     * under the missing kind's id instead of its own.
     */
    public static final ResourceKind<UnknownResource> KIND = new ResourceKind<>(
            Identifier.fromNamespaceAndPath("maxtech_lib", "unknown"), new RawCodec(),
            StreamCodec.of(UnknownResource::refuseEncode, UnknownResource::refuseDecode), key -> key, AmountUnit.ITEMS,
            -1);

    /** Throws {@link NullPointerException} for a null kind or tag. */
    public UnknownResource {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(raw, "raw");
    }

    /** {@code raw} saved under the missing kind {@code kind}, as the key {@link KindKey#CODEC} decodes it to. */
    public static KindKey<UnknownResource> key(Identifier kind, Tag raw) {
        return new KindKey<>(KIND, new UnknownResource(kind, raw));
    }

    /** What a client gets for a kind it does not have: the id, no tag. */
    static KindKey<UnknownResource> received(Identifier kind) {
        return key(kind, new CompoundTag());
    }

    private static void refuseEncode(RegistryFriendlyByteBuf buffer, UnknownResource resource) {
        throw new EncoderException("Unknown resources are sent through KindKey.STREAM_CODEC only");
    }

    private static UnknownResource refuseDecode(RegistryFriendlyByteBuf buffer) {
        throw new DecoderException("Unknown resources are received through KindKey.STREAM_CODEC only");
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
                return Identifier.CODEC.parse(ops, kind).map(
                        id -> Pair.of(new UnknownResource(id, ops.convertTo(NbtOps.INSTANCE, input)), ops.empty()));
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
