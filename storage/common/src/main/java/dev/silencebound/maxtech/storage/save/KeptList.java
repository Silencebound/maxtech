package dev.silencebound.maxtech.storage.save;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.ListBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

/**
 * A saved list read entry by entry: an entry that no longer decodes (an item or fluid of a removed mod, a removed
 * Storage item) is kept as it was saved instead of failing the whole list, which would lose every other entry with it.
 * Encoding writes the readable entries, then the kept ones unchanged, so reinstalling the mod restores them. Owners
 * keep {@link #kept()} beside their live state and hand it back when they save.
 *
 * <p>
 * Immutable; for save and load only.
 *
 * @param known
 *            the entries that decoded, in saved order
 * @param kept
 *            the entries that did not, as their saved NBT
 */
public record KeptList<E>(List<E> known, List<Tag> kept) {
    private static final Logger LOGGER = LogUtils.getLogger();

    public KeptList {
        known = List.copyOf(known);
        kept = List.copyOf(kept);
    }

    /** {@code known} with nothing kept. */
    public static <E> KeptList<E> of(List<E> known) {
        return new KeptList<>(known, List.of());
    }

    /** {@code known} and the entries kept from the last load. */
    public static <E> KeptList<E> of(List<E> known, List<Tag> kept) {
        return new KeptList<>(known, kept);
    }

    /** A list codec over {@code element} that keeps the entries it cannot read. */
    public static <E> Codec<KeptList<E>> codec(Codec<E> element) {
        return new Codec<>() {
            @Override
            public <T> DataResult<T> encode(KeptList<E> list, DynamicOps<T> ops, T prefix) {
                ListBuilder<T> builder = ops.listBuilder();
                for (E entry : list.known) {
                    builder.add(element.encodeStart(ops, entry));
                }
                for (Tag tag : list.kept) {
                    builder.add(NbtOps.INSTANCE.convertTo(ops, tag.copy()));
                }
                return builder.build(prefix);
            }

            @Override
            public <T> DataResult<Pair<KeptList<E>, T>> decode(DynamicOps<T> ops, T input) {
                List<T> values = new ArrayList<>();
                DataResult<?> read = ops.getList(input).map(consumer -> {
                    consumer.accept(values::add);
                    return values;
                });
                if (read.isError()) {
                    return DataResult.error(() -> "Not a list: " + input);
                }
                List<E> known = new ArrayList<>(values.size());
                List<Tag> kept = new ArrayList<>();
                for (T value : values) {
                    DataResult<E> entry = element.parse(ops, value);
                    if (entry.isSuccess()) {
                        known.add(entry.getOrThrow());
                    } else {
                        LOGGER.warn("Keeping a saved entry that cannot be read until its mod is back: {}",
                                entry.error().map(DataResult.Error::message).orElse(""));
                        kept.add(value instanceof Tag tag ? tag.copy() : ops.convertTo(NbtOps.INSTANCE, value));
                    }
                }
                return DataResult.success(Pair.of(new KeptList<>(known, kept), ops.empty()));
            }
        };
    }
}
