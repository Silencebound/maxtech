package dev.silencebound.maxtech.lib.api.resource;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * The registered kinds behind {@link ResourceKinds}, separate so JUnit tests can build their own. Registration locks;
 * lookups read copy-on-write snapshots without locking.
 */
final class KindTable {
    private volatile ResourceKind<?>[] byIndex = new ResourceKind<?>[0];
    private volatile Map<Identifier, ResourceKind<?>> byId = Map.of();
    private volatile boolean frozen;

    /** A kind saved as its fields next to {@code kind}; refuses a field named {@code kind}. */
    <K> ResourceKind<K> register(Identifier id, MapCodec<K> fields, StreamCodec<RegistryFriendlyByteBuf, K> streamCodec,
            UnaryOperator<K> drawBase, AmountUnit unit) {
        if (fields.keys(NbtOps.INSTANCE).anyMatch(StringTag.valueOf(KindKey.KIND_FIELD)::equals)) {
            throw new IllegalArgumentException("Resource kind " + id + " has a field named " + KindKey.KIND_FIELD
                    + ", which KindKey saves the kind id in; rename the field or register a Codec, saved under "
                    + KindKey.VALUE_FIELD);
        }
        return add(id, fields.codec(), fields, streamCodec, drawBase, unit);
    }

    /** A kind saved under {@code value} next to {@code kind}. */
    <K> ResourceKind<K> register(Identifier id, Codec<K> codec, StreamCodec<RegistryFriendlyByteBuf, K> streamCodec,
            UnaryOperator<K> drawBase, AmountUnit unit) {
        return add(id, codec, codec.fieldOf(KindKey.VALUE_FIELD), streamCodec, drawBase, unit);
    }

    private synchronized <K> ResourceKind<K> add(Identifier id, Codec<K> codec, MapCodec<K> fields,
            StreamCodec<RegistryFriendlyByteBuf, K> streamCodec, UnaryOperator<K> drawBase, AmountUnit unit) {
        if (frozen) {
            throw new IllegalStateException("Resource kind " + id + " registered after common setup");
        }
        if (id.equals(UnknownResource.KIND.id())) {
            throw new IllegalArgumentException("Resource kind id " + id + " is reserved");
        }
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("Resource kind " + id + " is already registered");
        }
        ResourceKind<?>[] old = byIndex;
        ResourceKind<K> kind = new ResourceKind<>(id, codec, fields, streamCodec, drawBase, unit, old.length);
        ResourceKind<?>[] grown = new ResourceKind<?>[old.length + 1];
        System.arraycopy(old, 0, grown, 0, old.length);
        grown[old.length] = kind;
        Map<Identifier, ResourceKind<?>> ids = new HashMap<>(byId);
        ids.put(id, kind);
        byId = Map.copyOf(ids);
        byIndex = grown;
        return kind;
    }

    synchronized void freeze() {
        frozen = true;
    }

    boolean isFrozen() {
        return frozen;
    }

    Optional<ResourceKind<?>> byId(Identifier id) {
        return Optional.ofNullable(byId.get(id));
    }

    ResourceKind<?> byIndex(int index) {
        ResourceKind<?>[] kinds = byIndex;
        if (index < 0 || index >= kinds.length) {
            throw new IndexOutOfBoundsException("No resource kind with index " + index + " of " + kinds.length);
        }
        return kinds[index];
    }

    int count() {
        return byIndex.length;
    }
}
