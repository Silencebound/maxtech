package dev.silencebound.maxtech.storage.save;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One saved value read on its own: a value that no longer decodes (it names an item or fluid of a removed mod) is kept
 * as it was saved instead of failing, which in an item component would fail and lose the whole stack. Encoding writes
 * the value, or the kept data unchanged, so reinstalling the mod restores it. The single-value sibling of
 * {@link KeptList}, for item components such as the encoded pattern.
 *
 * <p>
 * Immutable; exactly one of {@link #value()} and {@link #kept()} is set.
 *
 * @param value
 *            the value, or null when it could not be read
 * @param kept
 *            the saved data that could not be read, or null when {@code value} is set
 */
public record KeptValue<E>(@Nullable E value, @Nullable Tag kept) {
    private static final Logger LOGGER = LogUtils.getLogger();

    public KeptValue {
        if ((value == null) == (kept == null)) {
            throw new IllegalArgumentException("Exactly one of value and kept must be set");
        }
        kept = kept == null ? null : kept.copy();
    }

    /** A readable value. */
    public static <E> KeptValue<E> of(E value) {
        return new KeptValue<>(value, null);
    }

    /** Saved data that could not be read, kept as is. */
    public static <E> KeptValue<E> ofKept(Tag kept) {
        return new KeptValue<>(null, kept);
    }

    /** Whether the value could not be read and is only kept. */
    public boolean isKept() {
        return kept != null;
    }

    /** A codec over {@code element} that keeps a value it cannot read. */
    public static <E> Codec<KeptValue<E>> codec(Codec<E> element) {
        return new Codec<>() {
            @Override
            public <T> DataResult<T> encode(KeptValue<E> input, DynamicOps<T> ops, T prefix) {
                E value = input.value;
                if (value != null) {
                    return element.encode(value, ops, prefix);
                }
                Tag tag = input.kept;
                return tag == null
                        ? DataResult.error(() -> "Kept value without data")
                        : DataResult.success(NbtOps.INSTANCE.convertTo(ops, tag.copy()));
            }

            @Override
            public <T> DataResult<Pair<KeptValue<E>, T>> decode(DynamicOps<T> ops, T input) {
                DataResult<E> read = element.parse(ops, input);
                if (read.isSuccess()) {
                    return DataResult.success(Pair.of(of(read.getOrThrow()), ops.empty()));
                }
                LOGGER.warn("Keeping a saved value that cannot be read until its mod is back: {}",
                        read.error().map(DataResult.Error::message).orElse(""));
                Tag tag = input instanceof Tag saved ? saved.copy() : ops.convertTo(NbtOps.INSTANCE, input);
                return DataResult.success(Pair.of(ofKept(tag), ops.empty()));
            }
        };
    }

    /**
     * A stream codec over {@code element}: a flag, then the value or the kept data as NBT (read with the default quota,
     * as the client may send stacks in creative mode).
     */
    public static <B extends ByteBuf, E> StreamCodec<B, KeptValue<E>> streamCodec(StreamCodec<? super B, E> element) {
        return new StreamCodec<>() {
            @Override
            public KeptValue<E> decode(B buf) {
                return buf.readBoolean() ? of(element.decode(buf)) : ofKept(ByteBufCodecs.TAG.decode(buf));
            }

            @Override
            public void encode(B buf, KeptValue<E> input) {
                E value = input.value;
                Tag tag = input.kept;
                buf.writeBoolean(value != null);
                if (value != null) {
                    element.encode(buf, value);
                } else if (tag != null) {
                    ByteBufCodecs.TAG.encode(buf, tag);
                }
            }
        };
    }
}
