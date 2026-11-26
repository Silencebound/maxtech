package dev.silencebound.maxtech.lib.resource;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;

/**
 * The saved form of a container's contents (PRD 7.2, Storage data model): each distinct key once in {@code keys}, and
 * per entry its index into that palette and its count. A container that holds a key in several places (slots) repeats
 * the index, not the key. In NBT the two arrays are an int array and a long array, so a cell with thousands of kinds
 * costs one key each plus 12 bytes per entry.
 *
 * <p>
 * Built and read at save and load time only; the arrays are not copied, so neither side may change them afterwards.
 */
public final class KeyPalette<K> {
    private final List<K> keys;
    private final int[] indices;
    private final long[] counts;

    /**
     * A palette of the distinct {@code keys} and, per entry, an index into them and a count; {@code indices} and
     * {@code counts} have the same length (checked when decoding).
     */
    public KeyPalette(List<K> keys, int[] indices, long[] counts) {
        this.keys = List.copyOf(keys);
        this.indices = indices;
        this.counts = counts;
    }

    /** The distinct keys. */
    public List<K> keys() {
        return keys;
    }

    /** Per entry, an index into {@link #keys()}; do not change it. */
    public int[] indices() {
        return indices;
    }

    /** Per entry, its count; do not change it. */
    public long[] counts() {
        return counts;
    }

    /** An empty palette. */
    public static <K> KeyPalette<K> empty() {
        return new KeyPalette<>(List.of(), new int[0], new long[0]);
    }

    /**
     * A codec for palettes of keys saved with {@code keyCodec}: {@code {keys: [...], indices: [I; ...], counts: [L;
     * ...]}}. Decoding fails for arrays of different lengths, an index outside the palette or a negative count.
     */
    public static <K> Codec<KeyPalette<K>> codec(Codec<K> keyCodec) {
        Codec<KeyPalette<K>> raw = RecordCodecBuilder
                .create(i -> i.group(keyCodec.listOf().fieldOf("keys").forGetter(KeyPalette::keys),
                        Codec.INT_STREAM.xmap(IntStream::toArray, Arrays::stream).fieldOf("indices")
                                .forGetter(KeyPalette::indices),
                        Codec.LONG_STREAM.xmap(LongStream::toArray, Arrays::stream).fieldOf("counts")
                                .forGetter(KeyPalette::counts))
                        .apply(i, KeyPalette::new));
        return raw.validate(KeyPalette::check);
    }

    /** Entries in the palette. */
    public int entries() {
        return indices.length;
    }

    private static <K> DataResult<KeyPalette<K>> check(KeyPalette<K> palette) {
        if (palette.indices.length != palette.counts.length) {
            return DataResult.error(() -> "Palette has " + palette.indices.length + " indices but "
                    + palette.counts.length + " counts");
        }
        int size = palette.keys.size();
        for (int i = 0; i < palette.indices.length; i++) {
            int index = palette.indices[i];
            long count = palette.counts[i];
            if (index < 0 || index >= size) {
                return DataResult.error(() -> "Palette index " + index + " outside " + size + " keys");
            }
            if (count < 0) {
                return DataResult.error(() -> "Negative count " + count + " in palette");
            }
        }
        return DataResult.success(palette);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof KeyPalette<?> palette && keys.equals(palette.keys)
                && Arrays.equals(indices, palette.indices) && Arrays.equals(counts, palette.counts);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * keys.hashCode() + Arrays.hashCode(indices)) + Arrays.hashCode(counts);
    }

    @Override
    public String toString() {
        return "KeyPalette[" + keys.size() + " keys, " + indices.length + " entries]";
    }
}
