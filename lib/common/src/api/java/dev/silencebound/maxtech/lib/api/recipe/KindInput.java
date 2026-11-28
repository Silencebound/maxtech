package dev.silencebound.maxtech.lib.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.KindTags;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.resource.UnknownResource;
import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * A tank input of a machine recipe: {@link #amount()} of one resource of any {@link ResourceKind} (fluids, or another
 * mod's chemicals), taken from the machine's input tanks. It matches either one exact resource, components included, or
 * every resource of a kind in a tag ({@code c:water}); a tag needs the kind's {@link KindTags} test. Always consumed.
 * Immutable; safe from any thread.
 */
@ApiStatus.Experimental
public final class KindInput {
    /**
     * JSON form: {@code {"resource": <KindKey>, "amount": n}} for one exact resource, or
     * {@code {"kind": "maxtech_lib:fluid", "tag": "c:water", "amount": n}} for a tag. Decoding fails, so the recipe is
     * logged and skipped, for an unknown kind or resource, a kind without tags, or an amount below 1.
     */
    public static final Codec<KindInput> CODEC = RecordCodecBuilder
            .<Raw>create(i -> i.group(Identifier.CODEC.optionalFieldOf("kind").forGetter(Raw::kind),
                    Identifier.CODEC.optionalFieldOf("tag").forGetter(Raw::tag),
                    KindKey.CODEC.optionalFieldOf("resource").forGetter(Raw::resource),
                    Codec.LONG.fieldOf("amount").forGetter(Raw::amount)).apply(i, Raw::new))
            .flatXmap(KindInput::decode, input -> DataResult.success(input.raw()));

    /**
     * Network form, for syncing recipes to clients: the kind id, the tag or the resource
     * ({@link KindKey#CLIENTBOUND_STREAM_CODEC}), the amount. A kind the client lacks, or one it has without a tag
     * test, decodes to an input that matches nothing rather than failing the sync.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, KindInput> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public KindInput decode(RegistryFriendlyByteBuf buffer) {
            Identifier kindId = Identifier.STREAM_CODEC.decode(buffer);
            Optional<Identifier> tag = ByteBufCodecs.optional(Identifier.STREAM_CODEC).decode(buffer);
            KindKey<?> resource = tag.isPresent() ? null : KindKey.CLIENTBOUND_STREAM_CODEC.decode(buffer);
            long amount = ByteBufCodecs.VAR_LONG.decode(buffer);
            if (amount < 1) {
                throw new DecoderException("Kind input amount " + amount);
            }
            if (resource != null) {
                return new KindInput(resource.kind(), resource.kindId(), null, resource, amount, KindInput::never);
            }
            ResourceKind<?> kind = ResourceKinds.byId(kindId).orElse(UnknownResource.KIND);
            return new KindInput(kind, kindId, tag.get(), null, amount,
                    tagTest(kind, tag.get()).orElse(KindInput::never));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, KindInput input) {
            Identifier.STREAM_CODEC.encode(buffer, input.kindId);
            ByteBufCodecs.optional(Identifier.STREAM_CODEC).encode(buffer, Optional.ofNullable(input.tag));
            if (input.resource != null) {
                KindKey.CLIENTBOUND_STREAM_CODEC.encode(buffer, input.resource);
            }
            ByteBufCodecs.VAR_LONG.encode(buffer, input.amount);
        }
    };

    private final ResourceKind<?> kind;
    private final Identifier kindId;
    private final @Nullable Identifier tag;
    private final @Nullable KindKey<?> resource;
    private final long amount;
    private final Predicate<Object> inTag;

    private KindInput(ResourceKind<?> kind, Identifier kindId, @Nullable Identifier tag, @Nullable KindKey<?> resource,
            long amount, Predicate<Object> inTag) {
        this.kind = kind;
        this.kindId = kindId;
        this.tag = tag;
        this.resource = resource;
        this.amount = amount;
        this.inTag = inTag;
    }

    /**
     * {@code amount} of exactly {@code resource}, components included.
     *
     * @throws IllegalArgumentException
     *             if {@code resource} is unknown or {@code amount} is below 1
     */
    public static KindInput of(KindKey<?> resource, long amount) {
        if (resource.isUnknown()) {
            throw new IllegalArgumentException("Kind input of an unknown resource " + resource.kindId());
        }
        checkAmount(amount);
        return new KindInput(resource.kind(), resource.kindId(), null, resource, amount, KindInput::never);
    }

    /**
     * {@code amount} of any resource of {@code kind} in {@code tag}.
     *
     * @throws IllegalArgumentException
     *             if {@code kind} has no {@link KindTags} test or {@code amount} is below 1
     */
    public static KindInput tag(ResourceKind<?> kind, Identifier tag, long amount) {
        checkAmount(amount);
        Predicate<Object> test = tagTest(kind, tag)
                .orElseThrow(() -> new IllegalArgumentException(kind + " has no tag test in KindTags"));
        return new KindInput(kind, kind.id(), tag, null, amount, test);
    }

    /** The kind every matching resource is of; {@link UnknownResource#KIND} on a client that lacks it. */
    public ResourceKind<?> kind() {
        return kind;
    }

    /** The tag this input matches by; empty for an exact resource. */
    public Optional<Identifier> tag() {
        return Optional.ofNullable(tag);
    }

    /** The exact resource this input matches; empty for a tag. */
    public Optional<KindKey<?>> resource() {
        return Optional.ofNullable(resource);
    }

    /** How much each run takes, in the kind's unit (mB for fluids), at least 1. */
    public long amount() {
        return amount;
    }

    /** Whether {@code key} is a resource this input accepts; ignores amounts. Allocates nothing. */
    public boolean test(KindKey<?> key) {
        if (resource != null) {
            return resource.equals(key);
        }
        return key.kind() == kind && inTag.test(key.key());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KindInput input && kindId.equals(input.kindId) && Objects.equals(tag, input.tag)
                && Objects.equals(resource, input.resource) && amount == input.amount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kindId, tag, resource, amount);
    }

    @Override
    public String toString() {
        return "KindInput[" + (resource != null ? resource : kindId + " #" + tag) + " x" + amount + "]";
    }

    @SuppressWarnings("unchecked") // the test only ever sees keys of its own kind, see test(KindKey)
    private static Optional<Predicate<Object>> tagTest(ResourceKind<?> kind, Identifier tag) {
        return KindTags.test((ResourceKind<Object>) kind, tag);
    }

    /** The tag test of an exact input, or of a tag of a kind the client lacks: nothing matches. */
    private static boolean never(Object key) {
        return false;
    }

    private static void checkAmount(long amount) {
        if (amount < 1) {
            throw new IllegalArgumentException("Kind input amount must be at least 1, was " + amount);
        }
    }

    private static DataResult<KindInput> decode(Raw raw) {
        if (raw.amount < 1) {
            return DataResult.error(() -> "Kind input amount must be at least 1, was " + raw.amount);
        }
        if (raw.resource.isPresent()) {
            KindKey<?> resource = raw.resource.get();
            if (raw.kind.isPresent() || raw.tag.isPresent()) {
                return DataResult.error(() -> "Kind input has a resource and a kind or tag; use one");
            }
            if (resource.isUnknown()) {
                return DataResult.error(() -> "Unknown resource kind " + resource.kindId());
            }
            return DataResult.success(of(resource, raw.amount));
        }
        if (raw.kind.isEmpty() || raw.tag.isEmpty()) {
            return DataResult.error(() -> "Kind input needs a resource, or a kind and a tag");
        }
        Identifier kindId = raw.kind.get();
        Optional<ResourceKind<?>> kind = ResourceKinds.byId(kindId);
        if (kind.isEmpty()) {
            return DataResult.error(() -> "Unknown resource kind " + kindId);
        }
        if (!KindTags.supports(kind.get())) {
            return DataResult.error(() -> "Resource kind " + kindId + " has no tags");
        }
        return DataResult.success(tag(kind.get(), raw.tag.get(), raw.amount));
    }

    private Raw raw() {
        return resource != null
                ? new Raw(Optional.empty(), Optional.empty(), Optional.of(resource), amount)
                : new Raw(Optional.of(kindId), Optional.ofNullable(tag), Optional.empty(), amount);
    }

    private record Raw(Optional<Identifier> kind, Optional<Identifier> tag, Optional<KindKey<?>> resource,
            long amount) {
    }
}
