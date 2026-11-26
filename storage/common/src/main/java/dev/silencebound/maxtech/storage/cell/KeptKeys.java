package dev.silencebound.maxtech.storage.cell;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Palette entries of a cell whose keys no longer load: an item or fluid of a removed mod, or with a data component that
 * is gone. Each is kept as its saved key tag and count and written back unchanged, so reinstalling the mod restores it.
 * Kept entries are in no view and cannot be extracted, but they still take the bytes and the kind they took when they
 * were stored, so the cell cannot be overfilled while they wait.
 *
 * <p>
 * Immutable; built when the cell loads. Server thread only.
 */
final class KeptKeys {
    private final List<Tag> keys;
    private final long[] counts;

    /** {@code keys} (saved key tags, one per distinct key) and per key its count; both of the same length. */
    KeptKeys(List<Tag> keys, long[] counts) {
        if (keys.size() != counts.length) {
            throw new IllegalArgumentException(keys.size() + " kept keys but " + counts.length + " counts");
        }
        this.keys = List.copyOf(keys);
        this.counts = counts;
    }

    /** Distinct kept keys. */
    int size() {
        return keys.size();
    }

    /** The saved tag of kept key {@code index}; not to be changed. */
    Tag key(int index) {
        return keys.get(index);
    }

    /** The count of kept key {@code index}. */
    long count(int index) {
        return counts[index];
    }

    /**
     * Bytes the kept entries take in a cell of {@code type}, with {@code kindBytes} per kind
     * ({@code componentKindBytes} for keys saved with components). The kind and components are read from the saved tag
     * ({@code StackKey.CODEC}'s {@code kind} and {@code components} fields). {@link Long#MAX_VALUE} when {@code type}
     * stores another kind, so no capacity is enough. Saturates.
     */
    long bytes(CellType type, long kindBytes, long componentKindBytes) {
        long total = 0;
        for (int i = 0; i < keys.size(); i++) {
            StackKey.Kind kind = kind(keys.get(i));
            if (kind != type.kind()) {
                return Long.MAX_VALUE;
            }
            long bytes = CellBytes.bytes(counts[i], hasComponents(keys.get(i)) ? componentKindBytes : kindBytes,
                    type.unitsPerByte(kind));
            long sum = total + bytes;
            total = sum < total ? Long.MAX_VALUE : sum;
        }
        return total;
    }

    /** The kind a saved key tag names; an item for a tag without a readable kind (the codec's default type). */
    static StackKey.Kind kind(Tag key) {
        return key instanceof CompoundTag tag && "fluid".equals(tag.getStringOr("kind", ""))
                ? StackKey.Kind.FLUID
                : StackKey.Kind.ITEM;
    }

    /** Whether a saved key tag carries a non-empty {@code components} map. */
    static boolean hasComponents(Tag key) {
        return key instanceof CompoundTag tag && !tag.getCompoundOrEmpty("components").isEmpty();
    }
}
