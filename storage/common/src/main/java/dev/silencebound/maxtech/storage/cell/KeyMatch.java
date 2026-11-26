package dev.silencebound.maxtech.storage.cell;

import java.util.Comparator;
import java.util.List;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/**
 * The matching rules filters share: a cell's partition ({@link CellFilter}) and a port's filter entries. A tag entry
 * matches what the item's or fluid's registry holder lists in that tag (the holder caches its tags, so a test is a set
 * lookup); a mod entry matches the namespace of the registry id. Nothing here allocates. Any thread once tags are
 * bound.
 */
public final class KeyMatch {
    private KeyMatch() {
    }

    /** Whether {@code item} is in {@code tag}. */
    // builtInRegistryHolder is deprecated for registry-agnostic code; items always have one.
    @SuppressWarnings("deprecation")
    public static boolean inTag(Item item, TagKey<Item> tag) {
        return item.builtInRegistryHolder().is(tag);
    }

    /** Whether {@code fluid} is in {@code tag}. */
    // As above, for fluids.
    @SuppressWarnings("deprecation")
    public static boolean inFluidTag(Fluid fluid, TagKey<Fluid> tag) {
        return fluid.builtInRegistryHolder().is(tag);
    }

    /** Whether {@code item}'s registry id has the namespace {@code mod}. */
    // As above.
    @SuppressWarnings("deprecation")
    public static boolean fromMod(Item item, String mod) {
        return item.builtInRegistryHolder().key().identifier().getNamespace().equals(mod);
    }

    /** Whether {@code fluid}'s registry id has the namespace {@code mod}. */
    // As above.
    @SuppressWarnings("deprecation")
    public static boolean fluidFromMod(Fluid fluid, String mod) {
        return fluid.builtInRegistryHolder().key().identifier().getNamespace().equals(mod);
    }

    /** The namespace of {@code item}'s registry id. */
    // As above.
    @SuppressWarnings("deprecation")
    public static String mod(Item item) {
        return item.builtInRegistryHolder().key().identifier().getNamespace();
    }

    /** The namespace of {@code fluid}'s registry id. */
    // As above.
    @SuppressWarnings("deprecation")
    public static String mod(Fluid fluid) {
        return fluid.builtInRegistryHolder().key().identifier().getNamespace();
    }

    /**
     * {@code item}'s tags ordered by id, the order both sides use when an index into them travels to a client (tags are
     * synced, so both list the same). Allocates; for settings, never for matching.
     */
    // As above.
    @SuppressWarnings("deprecation")
    public static List<TagKey<Item>> tags(Item item) {
        return item.builtInRegistryHolder().tags().sorted(Comparator.comparing(tag -> tag.location().toString()))
                .toList();
    }

    /** {@link #tags(Item)} for a fluid. */
    // As above.
    @SuppressWarnings("deprecation")
    public static List<TagKey<Fluid>> fluidTags(Fluid fluid) {
        return fluid.builtInRegistryHolder().tags().sorted(Comparator.comparing(tag -> tag.location().toString()))
                .toList();
    }
}
