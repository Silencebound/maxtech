package dev.silencebound.maxtech.storage.cell;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.save.KeptList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

/**
 * The keys a cell's partition and void overflow add-ons act on (PRD 7.2, the cell builder): up to {@link #SIZE}
 * entries, each an item or fluid key, or a tag of the key's item or fluid. In fuzzy mode a key entry also matches the
 * key's variants with other data components (damaged, enchanted, named). In deny mode the filter names everything
 * except what its entries match (B09 item 10); a blank filter names nothing in either mode, so a blank deny list
 * neither limits a partition nor makes void overflow destroy anything. Immutable; part of {@link CellData}; cells saved
 * before deny mode load as allow lists.
 *
 * @param entries
 *            the entries in the order the workbench shows them, at most {@link #SIZE}
 * @param fuzzy
 *            whether key entries ignore data components
 * @param deny
 *            whether the entries are excluded instead of named: the filter names everything else
 * @param kept
 *            saved entries that no longer load (an item of a removed mod), kept as their NBT and written back
 *            unchanged; they match nothing but keep the filter from being blank, so an allow list of only such entries
 *            takes nothing instead of everything. They count towards {@link #SIZE}.
 */
public record CellFilter(List<Entry> entries, boolean fuzzy, boolean deny, List<Tag> kept) {
    /** Most entries a filter holds. */
    public static final int SIZE = 9;
    public static final CellFilter EMPTY = new CellFilter(List.of(), false, false);

    public static final Codec<CellFilter> CODEC = RecordCodecBuilder.create(i -> i
            .group(KeptList.codec(Entry.CODEC).optionalFieldOf("entries", KeptList.of(List.of()))
                    .forGetter(filter -> KeptList.of(filter.entries, filter.kept)),
                    Codec.BOOL.optionalFieldOf("fuzzy", false).forGetter(CellFilter::fuzzy),
                    Codec.BOOL.optionalFieldOf("deny", false).forGetter(CellFilter::deny))
            .apply(i, (entries, fuzzy, deny) -> new CellFilter(entries.known(), fuzzy, deny, entries.kept())));
    public static final StreamCodec<RegistryFriendlyByteBuf, CellFilter> STREAM_CODEC = StreamCodec.composite(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list(SIZE)), CellFilter::entries, ByteBufCodecs.BOOL,
            CellFilter::fuzzy, ByteBufCodecs.BOOL, CellFilter::deny, ByteBufCodecs.TAG.apply(ByteBufCodecs.list(SIZE)),
            CellFilter::kept, CellFilter::new);

    public CellFilter {
        kept = List.copyOf(kept.size() > SIZE ? kept.subList(0, SIZE) : kept);
        int room = SIZE - kept.size();
        entries = List.copyOf(entries.size() > room ? entries.subList(0, room) : entries);
    }

    /** A filter of {@code entries} with nothing kept. */
    public CellFilter(List<Entry> entries, boolean fuzzy, boolean deny) {
        this(entries, fuzzy, deny, List.of());
    }

    /** An allow list of {@code entries}. */
    public CellFilter(List<Entry> entries, boolean fuzzy) {
        this(entries, fuzzy, false);
    }

    /**
     * One entry: {@code key}, or with {@code tag} set, everything in that item tag (for an item key) or fluid tag (for
     * a fluid key); the key then only shows which tag it was picked from.
     */
    public record Entry(StackKey key, Optional<Identifier> tag) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder
                .create(i -> i.group(StackKey.CODEC.fieldOf("key").forGetter(Entry::key),
                        Identifier.CODEC.optionalFieldOf("tag").forGetter(Entry::tag)).apply(i, Entry::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                StackKey.STREAM_CODEC, Entry::key, Identifier.STREAM_CODEC.apply(ByteBufCodecs::optional), Entry::tag,
                Entry::new);

        /** An entry for {@code key} itself. */
        public static Entry of(StackKey key) {
            return new Entry(key, Optional.empty());
        }
    }

    /** Whether the filter has no entries, kept ones included. */
    public boolean isBlank() {
        return entries.isEmpty() && kept.isEmpty();
    }

    /** This filter with entry {@code index} replaced, appended (index = size) or removed ({@code entry} null). */
    public CellFilter with(int index, @Nullable Entry entry) {
        List<Entry> list = new ArrayList<>(entries);
        if (entry == null) {
            if (index < list.size()) {
                list.remove(index);
            }
        } else if (index < list.size()) {
            list.set(index, entry);
        } else if (list.size() + kept.size() < SIZE) {
            list.add(entry);
        }
        return new CellFilter(list, fuzzy, deny, kept);
    }

    /** This filter in or out of fuzzy mode. */
    public CellFilter withFuzzy(boolean fuzzy) {
        return new CellFilter(entries, fuzzy, deny, kept);
    }

    /** This filter as a deny list ({@code true}) or an allow list. */
    public CellFilter withDeny(boolean deny) {
        return new CellFilter(entries, fuzzy, deny, kept);
    }

    /**
     * This filter without its kept entries, freeing their slots. Only the workbench's explicit action calls this: kept
     * entries come back with their mod, so they are never dropped on their own.
     */
    public CellFilter withoutKept() {
        return new CellFilter(entries, fuzzy, deny, List.of());
    }

    /**
     * Whether a partition takes a key: a blank filter takes everything; otherwise an allow list takes what an entry
     * matches and a deny list everything an entry does not match.
     *
     * @param blank
     *            whether the filter has no entries
     * @param deny
     *            whether the filter is a deny list
     * @param matched
     *            whether an entry matches the key
     */
    public static boolean accepts(boolean blank, boolean deny, boolean matched) {
        return blank || matched != deny;
    }

    /**
     * Whether void overflow destroys what does not fit of a key: never with a blank filter, and never a key a deny list
     * matches; otherwise exactly the keys a partition with this filter would take.
     */
    public static boolean voids(boolean blank, boolean deny, boolean matched) {
        return !blank && matched != deny;
    }

    /** The filter compiled for matching on every insert. */
    public Matcher matcher() {
        return new Matcher(this);
    }

    /**
     * A filter prepared for matching: hash sets for keys and their bases, arrays of tags. Matching allocates nothing.
     * Built when a cell is mounted; any thread, immutable after construction.
     */
    public static final class Matcher {
        private final ObjectOpenHashSet<StackKey> keys = new ObjectOpenHashSet<>();
        private final ReferenceOpenHashSet<Object> bases = new ReferenceOpenHashSet<>();
        private final TagKey<Item>[] itemTags;
        private final TagKey<Fluid>[] fluidTags;
        private final boolean blank;
        private final boolean deny;

        @SuppressWarnings("unchecked") // arrays of a generic type, filled with that type only
        private Matcher(CellFilter filter) {
            ObjectArrayList<TagKey<Item>> items = new ObjectArrayList<>();
            ObjectArrayList<TagKey<Fluid>> fluids = new ObjectArrayList<>();
            for (Entry entry : filter.entries) {
                if (entry.tag.isPresent()) {
                    if (entry.key.kind() == StackKey.Kind.ITEM) {
                        items.add(TagKey.create(Registries.ITEM, entry.tag.get()));
                    } else {
                        fluids.add(TagKey.create(Registries.FLUID, entry.tag.get()));
                    }
                } else if (filter.fuzzy) {
                    bases.add(entry.key.base());
                } else {
                    keys.add(entry.key);
                }
            }
            this.itemTags = items.toArray(new TagKey[0]);
            this.fluidTags = fluids.toArray(new TagKey[0]);
            this.blank = filter.isBlank();
            this.deny = filter.deny;
        }

        /** Whether a partition with this filter takes {@code key}; see {@link CellFilter#accepts}. */
        public boolean accepts(StackKey key) {
            return CellFilter.accepts(blank, deny, !blank && matches(key));
        }

        /**
         * Whether void overflow with this filter destroys the overflow of {@code key}; see {@link CellFilter#voids}.
         */
        public boolean voids(StackKey key) {
            return !blank && CellFilter.voids(false, deny, matches(key));
        }

        /** Whether the filter names nothing. */
        public boolean blank() {
            return blank;
        }

        /**
         * Whether an entry matches {@code key}: the key itself, a variant of it in fuzzy mode, or a tag holding it.
         * Ignores deny mode; use {@link #accepts} or {@link #voids} to decide.
         */
        public boolean matches(StackKey key) {
            if (keys.contains(key) || bases.contains(key.base())) {
                return true;
            }
            switch (key) {
                case ItemKey item -> {
                    for (TagKey<Item> tag : itemTags) {
                        if (KeyMatch.inTag(item.item(), tag)) {
                            return true;
                        }
                    }
                }
                case FluidKey fluid -> {
                    for (TagKey<Fluid> tag : fluidTags) {
                        if (KeyMatch.inFluidTag(fluid.fluid(), tag)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }
}
