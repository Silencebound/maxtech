package dev.silencebound.maxtech.lib.api.resource;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * A key of any {@link ResourceKind}, with its kind, for holders that take more than one kind (a tank, a gauge, conduit
 * contents). Equal when kind and key are equal. Immutable; any thread.
 *
 * @param kind
 *            the key's kind; {@link UnknownResource#KIND} for a kind no loaded mod registers
 * @param key
 *            the key
 * @param <K>
 *            the key type
 */
@ApiStatus.Experimental
public record KindKey<K>(ResourceKind<K> kind, K key) {
    /** Name of the field that holds the kind id. */
    public static final String KIND_FIELD = "kind";
    /**
     * Name of the field that holds the key of a kind registered with a {@code Codec} rather than a {@code MapCodec}.
     */
    public static final String VALUE_FIELD = "value";

    /**
     * {@code {"kind": <kind id>, ...}}: the fields of a kind registered with a {@code MapCodec} next to {@code kind},
     * the value of one registered with a {@code Codec} under {@code value}; see {@link ResourceKinds#register}. A kind
     * no loaded mod registers decodes to an {@link UnknownResource} holding the whole saved value, which encodes back
     * unchanged. Lookups go through {@link ResourceKinds#byId}.
     */
    public static final Codec<KindKey<?>> CODEC = codec(ResourceKinds::byId);

    /**
     * For keys the server sends: the kind id, an {@code int} payload length, then the payload in the kind's
     * {@link ResourceKind#streamCodec()}. A receiver without the kind skips the payload and gets an
     * {@link UnknownResource} whose tag holds only {@code kind}; an unknown resource is sent with length -1 and no
     * payload, and received the same way whether or not the client has the kind. A payload read short or long fails
     * decoding.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, KindKey<?>> CLIENTBOUND_STREAM_CODEC = streamCodec(
            ResourceKinds::byId, false);

    /**
     * For keys a client sends (filters, ghost slots): like {@link #CLIENTBOUND_STREAM_CODEC}, but an unknown resource
     * cannot be sent (an {@link EncoderException} on the client) and length -1 fails decoding on the server, so a
     * client cannot hand the server an unknown resource of a kind the server has. A key of a kind only the client has
     * still arrives as an {@link UnknownResource}.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, KindKey<?>> SERVERBOUND_STREAM_CODEC = streamCodec(
            ResourceKinds::byId, true);

    /** Throws {@link NullPointerException} for a null kind or key. */
    public KindKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
    }

    /** The id saves and the network name this key's kind by: the missing kind's id for an unknown resource. */
    public Identifier kindId() {
        return key instanceof UnknownResource unknown ? unknown.kind() : kind.id();
    }

    /** Whether this is an {@link UnknownResource}, which holders keep but never move. */
    public boolean isUnknown() {
        return kind == UnknownResource.KIND;
    }

    /** This key drawn as its base; see {@link ResourceKind#drawBase}. Returns this key when nothing changes. */
    public KindKey<K> drawBase() {
        K base = kind.drawBase(key);
        return base.equals(key) ? this : new KindKey<>(kind, base);
    }

    /** The key as {@code K2} if it is of {@code wanted}, else empty. */
    @SuppressWarnings("unchecked") // a key of `wanted` is a K2
    public <K2> Optional<K2> as(ResourceKind<K2> wanted) {
        return kind == wanted ? Optional.of((K2) key) : Optional.empty();
    }

    static Codec<KindKey<?>> codec(Function<Identifier, Optional<ResourceKind<?>>> lookup) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<KindKey<?>, T>> decode(DynamicOps<T> ops, T input) {
                return ops.getMap(input).flatMap(map -> {
                    T kindValue = map.get(KIND_FIELD);
                    if (kindValue == null) {
                        return DataResult.error(() -> "No " + KIND_FIELD + " in " + input);
                    }
                    return Identifier.CODEC.parse(ops, kindValue).flatMap(id -> {
                        Optional<ResourceKind<?>> kind = lookup.apply(id);
                        return kind.isPresent()
                                ? decodeKnown(kind.get(), ops, map)
                                : UnknownResource.KIND.codec().parse(ops, input).map(KindKey::unknown);
                    });
                }).map(key -> Pair.of(key, ops.empty()));
            }

            @Override
            public <T> DataResult<T> encode(KindKey<?> key, DynamicOps<T> ops, T prefix) {
                return encodeKey(key, ops, prefix);
            }

            @Override
            public String toString() {
                return "KindKey";
            }
        };
    }

    static StreamCodec<RegistryFriendlyByteBuf, KindKey<?>> streamCodec(
            Function<Identifier, Optional<ResourceKind<?>>> lookup, boolean serverbound) {
        return new StreamCodec<>() {
            @Override
            public KindKey<?> decode(RegistryFriendlyByteBuf buffer) {
                Identifier id = Identifier.STREAM_CODEC.decode(buffer);
                int length = buffer.readInt();
                if (length == -1) {
                    if (serverbound) {
                        throw new DecoderException("Unknown resource of kind " + id + " sent to the server");
                    }
                    return UnknownResource.received(id);
                }
                if (length < 0 || length > buffer.readableBytes()) {
                    throw new DecoderException("Resource of kind " + id + " has a bad length " + length);
                }
                Optional<ResourceKind<?>> kind = lookup.apply(id);
                if (kind.isEmpty()) {
                    buffer.skipBytes(length);
                    return UnknownResource.received(id);
                }
                int start = buffer.readerIndex();
                KindKey<?> key = decodeKnown(kind.get(), buffer);
                int read = buffer.readerIndex() - start;
                if (read != length) {
                    throw new DecoderException("Resource of kind " + id + " read " + read + " of " + length + " bytes");
                }
                return key;
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, KindKey<?> key) {
                Identifier.STREAM_CODEC.encode(buffer, key.kindId());
                if (key.isUnknown()) {
                    if (serverbound) {
                        throw new EncoderException("Unknown resource of kind " + key.kindId() + " sent to the server");
                    }
                    buffer.writeInt(-1);
                    return;
                }
                int lengthAt = buffer.writerIndex();
                buffer.writeInt(0);
                encodePayload(buffer, key);
                buffer.setInt(lengthAt, buffer.writerIndex() - lengthAt - Integer.BYTES);
            }
        };
    }

    private static KindKey<UnknownResource> unknown(UnknownResource resource) {
        return new KindKey<>(UnknownResource.KIND, resource);
    }

    private static <K, T> DataResult<KindKey<?>> decodeKnown(ResourceKind<K> kind, DynamicOps<T> ops, MapLike<T> map) {
        return kind.fields().decode(ops, map).map(key -> new KindKey<>(kind, key));
    }

    private static <K, T> DataResult<T> encodeKey(KindKey<K> key, DynamicOps<T> ops, T prefix) {
        ResourceKind<K> kind = key.kind();
        if (kind == UnknownResource.KIND) {
            return kind.codec().encode(key.key(), ops, prefix);
        }
        RecordBuilder<T> fields = kind.fields().encode(key.key(), ops, ops.mapBuilder());
        return fields.add(KIND_FIELD, ops.createString(kind.id().toString())).build(prefix);
    }

    private static <K> KindKey<K> decodeKnown(ResourceKind<K> kind, RegistryFriendlyByteBuf buffer) {
        return new KindKey<>(kind, kind.streamCodec().decode(buffer));
    }

    private static <K> void encodePayload(RegistryFriendlyByteBuf buffer, KindKey<K> key) {
        key.kind().streamCodec().encode(buffer, key.key());
    }
}
