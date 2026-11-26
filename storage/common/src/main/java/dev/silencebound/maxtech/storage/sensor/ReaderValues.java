package dev.silencebound.maxtech.storage.sensor;

import dev.silencebound.maxtech.storage.sensor.reader.PackReaders;
import dev.silencebound.maxtech.storage.sensor.reader.ReaderType;
import dev.silencebound.maxtech.storage.sensor.reader.ReaderValue;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The pack reader values of one sensor's target: which values its block gets ({@link PackReaders#valuesFor}), made anew
 * only when the target's block or the readers' generation changes, and what the last saved-data read found for each. A
 * value whose path is missing reads 0 and is marked not found. Server thread only.
 */
final class ReaderValues {
    /** One value with what was last read for it. */
    static final class Slot {
        final String name;
        final String id;
        final String reader;
        final ReaderValue spec;
        long value;
        boolean found;
        String text = "";

        Slot(String name, String reader, ReaderValue spec) {
            this.name = name;
            this.id = SensorRef.data(name).id();
            this.reader = reader;
            this.spec = spec;
        }
    }

    private static final Slot[] NONE = new Slot[0];

    private Slot[] slots = NONE;
    private @Nullable Block block;
    private int generation = -1;

    /**
     * Makes the value list for {@code state}'s block when the block or the readers changed; true when it did (the new
     * values are unread and not found until the next saved-data read).
     */
    // Blocks are registry singletons.
    @SuppressWarnings("ReferenceEquality")
    boolean resolve(BlockState state) {
        Block now = state.getBlock();
        int current = PackReaders.generation();
        if (now == block && current == generation) {
            return false;
        }
        block = now;
        generation = current;
        List<PackReaders.Merged> merged = PackReaders.valuesFor(state);
        if (merged.isEmpty()) {
            boolean had = slots.length > 0;
            slots = NONE;
            return had;
        }
        Slot[] next = new Slot[merged.size()];
        for (int i = 0; i < next.length; i++) {
            PackReaders.Merged value = merged.get(i);
            next[i] = new Slot(value.name(), value.reader().toString(), value.value());
        }
        slots = next;
        return true;
    }

    /** Whether the target gets no reader values, so no saved-data read is needed. */
    boolean isEmpty() {
        return slots.length == 0;
    }

    /** Values the target gets from its readers. */
    int size() {
        return slots.length;
    }

    /** Forgets the readers and what was read, as before the first read. */
    void clear() {
        slots = NONE;
        block = null;
        generation = -1;
    }

    /**
     * Fills every value from {@code data}, the target's saved data ({@code null} when it has no block entity or saving
     * failed: every value is then not found). Allocates nothing beyond text values.
     */
    void read(@Nullable CompoundTag data) {
        for (Slot slot : slots) {
            Tag tag = data == null ? null : slot.spec.path().find(data);
            if (tag == null) {
                slot.found = false;
                slot.value = 0;
                slot.text = "";
                continue;
            }
            if (slot.spec.type() == ReaderType.TEXT) {
                slot.text = ReaderValue.text(tag);
                slot.found = tag instanceof StringTag || tag instanceof NumericTag;
                slot.value = 0;
                continue;
            }
            long value = slot.spec.number(tag);
            slot.found = value != Long.MIN_VALUE;
            slot.value = slot.found ? value : 0;
        }
    }

    /** The value named {@code name} (without {@code data.}); 0 when no reader gives it or it was not found. */
    long value(String name) {
        Slot slot = slot(name);
        return slot == null ? 0 : slot.value;
    }

    /** Whether a reader gives the value {@code name} and the last read found it. */
    boolean found(String name) {
        Slot slot = slot(name);
        return slot != null && slot.found;
    }

    private @Nullable Slot slot(String name) {
        for (Slot slot : slots) {
            if (slot.name.equals(name)) {
                return slot;
            }
        }
        return null;
    }

    /** Adds the values as the screen lists them, grouped by reader, while there is room below {@code max}. */
    void list(List<SensorValue> values, int max) {
        for (Slot slot : slots) {
            if (values.size() >= max) {
                return;
            }
            SensorUnit unit = switch (slot.spec.type()) {
                case NUMBER -> SensorUnit.CUSTOM;
                case YES_NO -> SensorUnit.YES_NO;
                case TEXT -> SensorUnit.TEXT;
            };
            String text = unit == SensorUnit.TEXT ? slot.text : slot.spec.unit();
            values.add(new SensorValue(slot.id, Component.literal(slot.name), slot.value, unit, text, slot.reader,
                    slot.found));
        }
    }

    /** The names of the values the last read did not find, for the info lines. Allocates. */
    void missing(List<String> names) {
        for (Slot slot : slots) {
            if (!slot.found) {
                names.add(slot.name);
            }
        }
    }
}
