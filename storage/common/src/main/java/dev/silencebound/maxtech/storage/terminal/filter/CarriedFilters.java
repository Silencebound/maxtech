package dev.silencebound.maxtech.storage.terminal.filter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.save.KeptList;
import java.util.List;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * The view filters an item terminal carries (#322): the {@code maxtech_storage:view_filters} component of a wireless
 * terminal or portable cell, which has no block to keep them in. Each entry is a filter item and the slot of the filter
 * strip it sits in; {@link CarriedViewFilters} reads them into the terminal menu's slots and writes them back on every
 * change. An entry that no longer loads (a component of a removed mod on the filter) is kept as saved instead of
 * failing the whole list. Immutable.
 *
 * @param entries
 *            the filters that load, by slot
 * @param kept
 *            the saved entries that did not load, written back unchanged
 */
public record CarriedFilters(List<Entry> entries, List<Tag> kept) {
    /** No filters. */
    public static final CarriedFilters EMPTY = new CarriedFilters(List.of(), List.of());
    public static final Codec<CarriedFilters> CODEC = KeptList.codec(Entry.CODEC).xmap(
            list -> new CarriedFilters(list.known(), list.kept()),
            filters -> KeptList.of(filters.entries(), filters.kept()));

    public CarriedFilters {
        entries = List.copyOf(entries);
        kept = List.copyOf(kept);
    }

    /** Whether there is nothing to keep, so the item needs no component. */
    public boolean isEmpty() {
        return entries.isEmpty() && kept.isEmpty();
    }

    /** All entries, loaded or kept. */
    public int size() {
        return entries.size() + kept.size();
    }

    /**
     * One filter in slot {@code slot} of the strip.
     *
     * @param slot
     *            the strip slot, 0 to 255; one out of the strip's range goes to a free slot when loaded
     * @param stack
     *            the filter item
     */
    public record Entry(int slot, ItemStackTemplate stack) {
        static final Codec<Entry> CODEC = RecordCodecBuilder
                .create(instance -> instance
                        .group(Codec.intRange(0, 255).fieldOf("slot").forGetter(Entry::slot),
                                ItemStackTemplate.CODEC.fieldOf("item").forGetter(Entry::stack))
                        .apply(instance, Entry::new));
    }
}
