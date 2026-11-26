package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A world device's filter: nine ghost slots, each naming an item with its data components or, once a tag is picked for
 * it, every item in one of that item's tags. Never holds real items. The matching arrays are rebuilt when a slot
 * changes, so matching allocates nothing. Server thread for a device's own filter; the client menu holds a plain
 * container.
 */
public final class WorldFilter extends SimpleContainer {
    public static final int SIZE = 9;
    /** {@link #tagIndex}'s answer for a slot that names its exact item. */
    public static final int EXACT = -1;

    private final Runnable onChanged;
    private final @Nullable TagKey<Item>[] tags = newTagArray(SIZE);
    private ItemKey[] exact = new ItemKey[0];
    private TagKey<Item>[] tagEntries = newTagArray(0);
    private final int[] tagIndices = new int[SIZE];

    public WorldFilter(Runnable onChanged) {
        super(SIZE);
        this.onChanged = onChanged;
        Arrays.fill(tagIndices, EXACT);
    }

    // A generic array of one non-generic class; nothing else is ever stored in it.
    @SuppressWarnings("unchecked")
    private static TagKey<Item>[] newTagArray(int size) {
        return (TagKey<Item>[]) new TagKey<?>[size];
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    /** A new item in a slot names itself exactly again. */
    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!ItemStack.isSameItemSameComponents(getItem(slot), stack)) {
            tags[slot] = null;
        }
        super.setItem(slot, stack);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        rebuild();
        onChanged.run();
    }

    /** The tag slot {@code slot} matches by, or null when it names its exact item. */
    public @Nullable TagKey<Item> tag(int slot) {
        return tags[slot];
    }

    /** The position of the slot's tag in {@link #tagsOf} its item, or {@link #EXACT}; the menu syncs it. */
    public int tagIndex(int slot) {
        return tagIndices[slot];
    }

    /**
     * Moves slot {@code slot} to its item's next tag (in {@link #tagsOf} order), and from the last back to the exact
     * item. An empty slot stays exact.
     */
    public void cycleTag(int slot) {
        List<TagKey<Item>> options = tagsOf(getItem(slot));
        int next = tagIndices[slot] + 1;
        tags[slot] = next < options.size() ? options.get(next) : null;
        setChanged();
    }

    /** Sets slot {@code slot}'s tag without saving or waking (loading); the tag is dropped if the item lacks it. */
    void loadTag(int slot, @Nullable Identifier tag) {
        TagKey<Item> key = tag == null ? null : TagKey.create(Registries.ITEM, tag);
        tags[slot] = key != null && tagsOf(getItem(slot)).contains(key) ? key : null;
    }

    /**
     * The item tags of {@code stack}'s item, ordered by id: the order both sides use for {@link #tagIndex}. Empty for
     * an empty stack.
     */
    // builtInRegistryHolder is deprecated for registry-agnostic code; items always have one.
    @SuppressWarnings("deprecation")
    public static List<TagKey<Item>> tagsOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return List.of();
        }
        return stack.getItem().builtInRegistryHolder().tags()
                .sorted(Comparator.comparing(tag -> tag.location().toString())).toList();
    }

    /** Re-reads the matching arrays from the slots; also after loading. */
    void rebuild() {
        ObjectArrayList<ItemKey> exactList = new ObjectArrayList<>();
        ObjectArrayList<TagKey<Item>> tagList = new ObjectArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            ItemStack stack = getItem(i);
            TagKey<Item> tag = tags[i];
            if (stack.isEmpty()) {
                tags[i] = null;
                tagIndices[i] = EXACT;
                continue;
            }
            if (tag != null) {
                tagIndices[i] = tagsOf(stack).indexOf(tag);
                if (!tagList.contains(tag)) {
                    tagList.add(tag);
                }
            } else {
                tagIndices[i] = EXACT;
                ItemKey key = ItemKey.of(stack);
                if (!exactList.contains(key)) {
                    exactList.add(key);
                }
            }
        }
        exact = exactList.toArray(new ItemKey[0]);
        tagEntries = tagList.toArray(newTagArray(0));
    }

    /** Whether no slot names anything. */
    public boolean isBlank() {
        return exact.length == 0 && tagEntries.length == 0;
    }

    /** Whether some slot names {@code key}: its exact item and components, or a tag its item is in. */
    // builtInRegistryHolder: see tagsOf.
    @SuppressWarnings("deprecation")
    public boolean matches(ItemKey key) {
        for (ItemKey named : exact) {
            if (named.equals(key)) {
                return true;
            }
        }
        if (tagEntries.length > 0) {
            var holder = key.item().builtInRegistryHolder();
            for (TagKey<Item> tag : tagEntries) {
                if (holder.is(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether some slot names {@code stack}'s item: see {@link #matches(ItemKey)}. */
    // builtInRegistryHolder: see tagsOf.
    @SuppressWarnings("deprecation")
    public boolean matches(ItemStack stack) {
        for (ItemKey named : exact) {
            if (stack.is(named.item()) && named.components().equals(stack.getComponentsPatch())) {
                return true;
            }
        }
        if (tagEntries.length > 0) {
            var holder = stack.getItem().builtInRegistryHolder();
            for (TagKey<Item> tag : tagEntries) {
                if (holder.is(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The exact keys the slots name, in slot order, each once; the array must not be changed. */
    public ItemKey[] exactKeys() {
        return exact;
    }

    /** The tags the slots name, in slot order, each once; the array must not be changed. */
    public TagKey<Item>[] tagEntries() {
        return tagEntries;
    }
}
