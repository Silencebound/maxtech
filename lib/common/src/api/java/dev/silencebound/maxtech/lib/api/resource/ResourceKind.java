package dev.silencebound.maxtech.lib.api.resource;

import com.mojang.serialization.Codec;
import java.util.function.UnaryOperator;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * A kind of resource that tanks, gauges, menu values and conduit contents can carry: items, fluids and energy are built
 * in ({@link ResourceKinds}), other mods' resources (chemicals, magic sources) come from Lib's compat modules.
 * {@code K} is the kind's key type, a resource without an amount; amounts go next to it as a {@code long} in
 * {@link #unit()}. Created only by {@link ResourceKinds#register}; compared by identity. Immutable; any thread.
 *
 * @param <K>
 *            the key type, immutable with value equality
 */
@ApiStatus.Experimental
public final class ResourceKind<K> {
    private final Identifier id;
    private final Codec<K> codec;
    private final StreamCodec<RegistryFriendlyByteBuf, K> streamCodec;
    private final UnaryOperator<K> drawBase;
    private final AmountUnit unit;
    private final int index;

    ResourceKind(Identifier id, Codec<K> codec, StreamCodec<RegistryFriendlyByteBuf, K> streamCodec,
            UnaryOperator<K> drawBase, AmountUnit unit, int index) {
        this.id = id;
        this.codec = codec;
        this.streamCodec = streamCodec;
        this.drawBase = drawBase;
        this.unit = unit;
        this.index = index;
    }

    /**
     * The kind's id, which is what saves and the network name it by ({@code maxtech_lib:item}); kinds for other mods'
     * resources are in Lib's namespace too ({@code maxtech_lib:mekanism_chemical}).
     */
    public Identifier id() {
        return id;
    }

    /**
     * Saves a key. A map codec's fields sit next to {@code kind} in {@link KindKey#CODEC}; any other codec's value goes
     * under {@code value}.
     */
    public Codec<K> codec() {
        return codec;
    }

    /** Sends a key; {@link KindKey#STREAM_CODEC} frames it so a client without this kind can skip it. */
    public StreamCodec<RegistryFriendlyByteBuf, K> streamCodec() {
        return streamCodec;
    }

    /**
     * {@code key} without what only tells variants apart (an item's or fluid's components), for drawing: a gauge or
     * tube draws every variant of one base the same. Returns {@code key} itself when it has nothing to strip.
     */
    public K drawBase(K key) {
        return drawBase.apply(key);
    }

    /** How amounts of this kind are counted and shown. */
    public AmountUnit unit() {
        return unit;
    }

    /**
     * The kind's index in this game session, from 0 in registration order (the built-in kinds are 0, 1 and 2), for
     * per-kind arrays; {@link ResourceKinds#byIndex} maps it back. Never save or send it: another session or the other
     * side may number kinds differently. {@link UnknownResource#KIND} is not registered and has index -1.
     */
    public int index() {
        return index;
    }

    @Override
    public String toString() {
        return "ResourceKind[" + id + "]";
    }
}
