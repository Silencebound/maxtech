package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.cell.KeyMatch;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
// PLATFORM-LEAK: ports move NeoForge transfer resources; the filter matches them without converting.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.resource.Resource;
import org.jspecify.annotations.Nullable;

/**
 * A port's filter: nine ghost slots holding one item each, never real items. An item stands for itself; a filled bucket
 * stands for its fluid. Each slot also has a {@link MatchMode} (B09 item 18: exact with its data components, fuzzy, one
 * of its tags, or its whole mod) and a keep amount for export ports (B09 item 22, the regulator). Imports take anything
 * while every slot is empty, else only what a slot matches; exports move only what a slot matches. The entries are
 * compiled when a slot or its settings change, so matching allocates nothing and tags are the registry holder's cached
 * sets. Server thread for the port's own filter; the client menu holds a plain container and data slots instead.
 */
public final class PortFilter extends SimpleContainer {
    public static final int SIZE = 9;

    private final Runnable onChanged;
    private final MatchMode[] modes = new MatchMode[SIZE];
    private final @Nullable Identifier[] tags = new Identifier[SIZE];
    private final int[] keeps = new int[SIZE];
    private Entry[] itemEntries = new Entry[0];
    private Entry[] fluidEntries = new Entry[0];
    private boolean inexactItems;
    private boolean inexactFluids;
    private boolean keeping;
    /** Bumped by every rebuild, so what was worked out from the entries knows when to work it out again. */
    private int version;

    PortFilter(Runnable onChanged) {
        super(SIZE);
        this.onChanged = onChanged;
        Arrays.fill(modes, MatchMode.EXACT);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    /** A different item in a slot matches exactly again and keeps nothing. */
    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!ItemStack.isSameItemSameComponents(getItem(slot), stack)) {
            resetSlot(slot);
        }
        super.setItem(slot, stack);
    }

    private void resetSlot(int slot) {
        modes[slot] = MatchMode.EXACT;
        tags[slot] = null;
        keeps[slot] = 0;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        rebuild();
        onChanged.run();
    }

    /** Re-reads the entries from the slots and their settings; also after loading. */
    void rebuild() {
        ObjectArrayList<Entry> itemList = new ObjectArrayList<>();
        ObjectArrayList<Entry> fluidList = new ObjectArrayList<>();
        boolean anyInexactItem = false;
        boolean anyInexactFluid = false;
        boolean anyKeep = false;
        for (int i = 0; i < SIZE; i++) {
            ItemStack stack = getItem(i);
            if (stack.isEmpty()) {
                resetSlot(i);
                continue;
            }
            Entry entry = Entry.of(i, stack, modes[i], tags[i], keeps[i]);
            // A tag mode whose tag the item lost (a data pack changed) matches exactly until it is set again.
            modes[i] = entry.mode;
            if (entry.mode != MatchMode.TAG) {
                tags[i] = null;
            }
            if (entry.fluid) {
                fluidList.add(entry);
                anyInexactFluid |= entry.mode != MatchMode.EXACT;
            } else {
                itemList.add(entry);
                anyInexactItem |= entry.mode != MatchMode.EXACT;
            }
            anyKeep |= entry.keep > 0;
        }
        itemEntries = itemList.toArray(new Entry[0]);
        fluidEntries = fluidList.toArray(new Entry[0]);
        inexactItems = anyInexactItem;
        inexactFluids = anyInexactFluid;
        keeping = anyKeep;
        version++;
    }

    /** Whether no slot names anything. */
    public boolean isBlank() {
        return itemEntries.length == 0 && fluidEntries.length == 0;
    }

    /**
     * Whether an import may take {@code resource}: everything while the filter is blank, else what an entry matches.
     */
    public boolean accepts(Resource resource) {
        if (isBlank()) {
            return true;
        }
        Entry[] entries = resource instanceof FluidResource ? fluidEntries : itemEntries;
        for (Entry entry : entries) {
            if (entry.matches(resource)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link #accepts} for a key: everything while the filter is blank, else what an entry matches. For a storage bus's
     * stores and takes; allocates nothing.
     */
    public boolean acceptsKey(StackKey key) {
        if (isBlank()) {
            return true;
        }
        Entry[] entries = key.kind() == StackKey.Kind.FLUID ? fluidEntries : itemEntries;
        for (Entry entry : entries) {
            if (entry.matches(key)) {
                return true;
            }
        }
        return false;
    }

    /** How slot {@code slot} matches. */
    public MatchMode mode(int slot) {
        return modes[slot];
    }

    /** The tag slot {@code slot} matches by, if it is in tag mode. */
    public Optional<Identifier> tag(int slot) {
        return Optional.ofNullable(tags[slot]);
    }

    /** How many of what slot {@code slot} matches an export port keeps in the inventory; 0 for no limit. */
    public int keep(int slot) {
        return keeps[slot];
    }

    /**
     * Steps slot {@code slot} through its match modes: exact, fuzzy, each of its item's or fluid's tags (in id order),
     * its mod, and back to exact ({@code forward} false steps back). An empty slot stays exact. Saves and wakes.
     */
    public void cycleMatch(int slot, boolean forward) {
        ItemStack stack = getItem(slot);
        if (stack.isEmpty()) {
            return;
        }
        List<Identifier> options = tagOptions(stack);
        int steps = options.size() + 3;
        int at = position(modes[slot], tags[slot], options);
        int next = Math.floorMod(at + (forward ? 1 : -1), steps);
        if (next == 0) {
            modes[slot] = MatchMode.EXACT;
            tags[slot] = null;
        } else if (next == 1) {
            modes[slot] = MatchMode.FUZZY;
            tags[slot] = null;
        } else if (next == steps - 1) {
            modes[slot] = MatchMode.MOD;
            tags[slot] = null;
        } else {
            modes[slot] = MatchMode.TAG;
            tags[slot] = options.get(next - 2);
        }
        setChanged();
    }

    /**
     * Sets how slot {@code slot} matches; {@code tag} counts in tag mode only, and a tag its item or fluid is not in
     * makes the slot exact. An empty slot stays exact. Saves and wakes.
     */
    public void setMatch(int slot, MatchMode mode, @Nullable Identifier tag) {
        if (getItem(slot).isEmpty()) {
            return;
        }
        modes[slot] = mode;
        tags[slot] = mode == MatchMode.TAG ? tag : null;
        setChanged();
    }

    /** Where {@code mode} and {@code tag} are in {@link #cycleMatch}'s steps. */
    private static int position(MatchMode mode, @Nullable Identifier tag, List<Identifier> options) {
        return switch (mode) {
            case EXACT -> 0;
            case FUZZY -> 1;
            case TAG -> tag == null ? 0 : Math.max(0, options.indexOf(tag)) + 2;
            case MOD -> options.size() + 2;
        };
    }

    /** Sets slot {@code slot}'s keep amount (negative counts as 0); saves and wakes when it changed. */
    public void setKeep(int slot, int keep) {
        int clamped = Math.max(0, keep);
        if (getItem(slot).isEmpty() || keeps[slot] == clamped) {
            return;
        }
        keeps[slot] = clamped;
        setChanged();
    }

    /**
     * Slot {@code slot}'s mode and tag as one number for a menu data slot: the mode's id in the low two bits, above
     * them the tag's position in {@link #tagOptions} plus one (0 for none).
     */
    public int matchData(int slot) {
        int tag = 0;
        Identifier named = tags[slot];
        if (modes[slot] == MatchMode.TAG && named != null) {
            tag = tagOptions(getItem(slot)).indexOf(named) + 1;
        }
        return modes[slot].id() | (tag << 2);
    }

    /** The mode in a {@link #matchData} number. */
    public static MatchMode dataMode(int data) {
        return MatchMode.byId(data & 3);
    }

    /** The tag in a {@link #matchData} number for a slot holding {@code stack}; empty for none. */
    public static Optional<Identifier> dataTag(int data, ItemStack stack) {
        int index = (data >>> 2) - 1;
        if (dataMode(data) != MatchMode.TAG || index < 0) {
            return Optional.empty();
        }
        List<Identifier> options = tagOptions(stack);
        return index < options.size() ? Optional.of(options.get(index)) : Optional.empty();
    }

    /**
     * The tags a slot holding {@code stack} can match by, ordered by id: the item's tags, or for a filled bucket its
     * fluid's. Allocates; for settings and screens, never for matching.
     */
    public static List<Identifier> tagOptions(ItemStack stack) {
        if (stack.isEmpty()) {
            return List.of();
        }
        List<Identifier> out = new ArrayList<>();
        Fluid fluid = fluidOf(stack);
        if (fluid != null) {
            for (TagKey<Fluid> tag : KeyMatch.fluidTags(fluid)) {
                out.add(tag.location());
            }
        } else {
            for (TagKey<Item> tag : KeyMatch.tags(stack.getItem())) {
                out.add(tag.location());
            }
        }
        return out;
    }

    /** The namespace a mod entry for {@code stack} matches: its fluid's for a filled bucket, else its item's. */
    public static String modOf(ItemStack stack) {
        Fluid fluid = fluidOf(stack);
        return fluid != null ? KeyMatch.mod(fluid) : KeyMatch.mod(stack.getItem());
    }

    /** The fluid a filled bucket stands for; null for anything else. */
    static @Nullable Fluid fluidOf(ItemStack stack) {
        return stack.getItem() instanceof BucketItem bucket && !bucket.content.isSame(Fluids.EMPTY)
                ? bucket.content
                : null;
    }

    /** The slots' settings that differ from a fresh slot's, in slot order; what saves and items hold. */
    public List<PortEntrySettings> settings() {
        List<PortEntrySettings> out = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            PortEntrySettings settings = new PortEntrySettings(i, modes[i], Optional.ofNullable(tags[i]), keeps[i]);
            if (!getItem(i).isEmpty() && !settings.isDefault()) {
                out.add(settings);
            }
        }
        return out;
    }

    /**
     * Sets every slot's settings from {@code settings} (slots it leaves out are exact and keep nothing) without saving
     * or waking; call {@link #rebuild} after the items are in, as loading does.
     */
    void loadSettings(List<PortEntrySettings> settings) {
        for (int i = 0; i < SIZE; i++) {
            resetSlot(i);
        }
        for (PortEntrySettings each : settings) {
            int slot = each.slot();
            if (slot < 0 || slot >= SIZE) {
                continue;
            }
            modes[slot] = each.match();
            tags[slot] = each.match() == MatchMode.TAG ? each.tag().orElse(null) : null;
            keeps[slot] = Math.max(0, each.keep());
        }
    }

    /** The item entries in slot order; the array must not be changed. */
    Entry[] itemEntries() {
        return itemEntries;
    }

    /** The fluid entries (filled buckets) in slot order; the array must not be changed. */
    Entry[] fluidEntries() {
        return fluidEntries;
    }

    /** Whether an item entry (or with {@code fluid}, a fluid entry) matches more than its own key. */
    boolean inexact(boolean fluid) {
        return fluid ? inexactFluids : inexactItems;
    }

    /** Whether an entry keeps an amount (the regulator). */
    boolean keeping() {
        return keeping;
    }

    /** Bumped by every rebuild. */
    int version() {
        return version;
    }

    /**
     * One compiled entry: its slot, mode and keep amount, the key it names, and what it matches by (the base item or
     * fluid, a tag, a namespace). Immutable.
     */
    static final class Entry {
        final int slot;
        final MatchMode mode;
        final boolean fluid;
        final StackKey key;
        final Resource resource;
        final Object base;
        final @Nullable TagKey<Item> itemTag;
        final @Nullable TagKey<Fluid> fluidTag;
        final String mod;
        final int keep;

        private Entry(int slot, MatchMode mode, boolean fluid, StackKey key, Resource resource, Object base,
                @Nullable TagKey<Item> itemTag, @Nullable TagKey<Fluid> fluidTag, String mod, int keep) {
            this.slot = slot;
            this.mode = mode;
            this.fluid = fluid;
            this.key = key;
            this.resource = resource;
            this.base = base;
            this.itemTag = itemTag;
            this.fluidTag = fluidTag;
            this.mod = mod;
            this.keep = keep;
        }

        static Entry of(int slot, ItemStack stack, MatchMode mode, @Nullable Identifier tag, int keep) {
            Fluid fluid = fluidOf(stack);
            MatchMode effective = mode;
            if (mode == MatchMode.TAG && (tag == null || !tagOptions(stack).contains(tag))) {
                effective = MatchMode.EXACT;
            }
            if (fluid != null) {
                FluidKey key = FluidKey.of(fluid);
                TagKey<Fluid> fluidTag = effective == MatchMode.TAG && tag != null
                        ? TagKey.create(Registries.FLUID, tag)
                        : null;
                return new Entry(slot, effective, true, key, StackKeys.toResource(key), key.base(), null, fluidTag,
                        KeyMatch.mod(fluid), keep);
            }
            ItemKey key = ItemKey.of(stack);
            TagKey<Item> itemTag = effective == MatchMode.TAG && tag != null
                    ? TagKey.create(Registries.ITEM, tag)
                    : null;
            return new Entry(slot, effective, false, key, StackKeys.toResource(key), key.base(), itemTag, null,
                    KeyMatch.mod(stack.getItem()), keep);
        }

        /** Whether the entry matches {@code resource}; allocates nothing. */
        // The base is an Item or Fluid, compared by identity like the registries do.
        @SuppressWarnings("ReferenceEquality")
        boolean matches(Resource resource) {
            if (fluid) {
                if (!(resource instanceof FluidResource other)) {
                    return false;
                }
                return switch (mode) {
                    case EXACT -> this.resource.equals(other);
                    case FUZZY -> other.getFluid() == base;
                    case TAG -> fluidTag != null && KeyMatch.inFluidTag(other.getFluid(), fluidTag);
                    case MOD -> KeyMatch.fluidFromMod(other.getFluid(), mod);
                };
            }
            if (!(resource instanceof ItemResource other)) {
                return false;
            }
            return switch (mode) {
                case EXACT -> this.resource.equals(other);
                case FUZZY -> other.getItem() == base;
                case TAG -> itemTag != null && KeyMatch.inTag(other.getItem(), itemTag);
                case MOD -> KeyMatch.fromMod(other.getItem(), mod);
            };
        }

        /** Whether the entry matches {@code other}; allocates nothing. */
        // As above.
        @SuppressWarnings("ReferenceEquality")
        boolean matches(StackKey other) {
            if (mode == MatchMode.EXACT) {
                return key.equals(other);
            }
            if (mode == MatchMode.FUZZY) {
                return other.base() == base;
            }
            return switch (other) {
                case ItemKey item -> !fluid && (mode == MatchMode.TAG
                        ? itemTag != null && KeyMatch.inTag(item.item(), itemTag)
                        : KeyMatch.fromMod(item.item(), mod));
                case FluidKey liquid -> fluid && (mode == MatchMode.TAG
                        ? fluidTag != null && KeyMatch.inFluidTag(liquid.fluid(), fluidTag)
                        : KeyMatch.fluidFromMod(liquid.fluid(), mod));
            };
        }
    }
}
