package dev.silencebound.maxtech.lib.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import org.jspecify.annotations.Nullable;

/**
 * A switch that turns a module of content off without unregistering it (Storage's module switches): while it is off,
 * recipes carrying the {@code maxtech_lib:content_enabled} condition with its id do not load, and the items it
 * {@linkplain #hides hides} leave every creative tab and JEI. The items stay registered, so worlds keep their blocks;
 * what placed content does while disabled is up to the module.
 *
 * <p>
 * The switch is read from a {@link BooleanSupplier}, meant to be a COMMON config value: recipes load before a world's
 * server config (NeoForge loads COMMON config at startup and SERVER config only once the server exists, after the
 * datapacks), and clients need the same value for creative tabs and JEI without a server. It is read when recipes load
 * (world load and {@code /reload}), when a creative tab builds and when JEI starts, so a change needs a restart to
 * apply everywhere at once. Before the config file loads (datagen) a toggle reads as on.
 *
 * <p>
 * Register toggles during mod construction; everything else is thread-safe to read afterwards.
 */
public final class ContentToggle {
    private static final Map<Identifier, ContentToggle> TOGGLES = new ConcurrentHashMap<>();

    private final Identifier id;
    private final BooleanSupplier enabled;
    private final List<Supplier<? extends ItemLike>> hidden = new CopyOnWriteArrayList<>();

    private ContentToggle(Identifier id, BooleanSupplier enabled) {
        this.id = id;
        this.enabled = enabled;
    }

    /**
     * Registers the toggle {@code id}, read from {@code enabled}. Call during mod construction, once per id.
     *
     * @throws IllegalStateException
     *             if {@code id} is registered already
     */
    public static ContentToggle register(Identifier id, BooleanSupplier enabled) {
        ContentToggle toggle = new ContentToggle(id, enabled);
        if (TOGGLES.putIfAbsent(id, toggle) != null) {
            throw new IllegalStateException("Content toggle " + id + " is registered twice");
        }
        return toggle;
    }

    /** The toggle {@code id}, or {@code null} if none is registered. */
    public static @Nullable ContentToggle get(Identifier id) {
        return TOGGLES.get(id);
    }

    /**
     * Whether the toggle {@code id} is on. An unknown id reads as on, so a recipe naming the toggle of a mod that is
     * not installed is left to its other conditions.
     */
    public static boolean isEnabled(Identifier id) {
        ContentToggle toggle = TOGGLES.get(id);
        return toggle == null || toggle.isEnabled();
    }

    /** The items of every toggle that is off right now, for creative tabs and JEI; empty if all are on. */
    public static Set<Item> hiddenItems() {
        Set<Item> items = null;
        for (ContentToggle toggle : TOGGLES.values()) {
            if (toggle.hidden.isEmpty() || toggle.isEnabled()) {
                continue;
            }
            if (items == null) {
                items = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            for (Supplier<? extends ItemLike> item : toggle.hidden) {
                items.add(item.get().asItem());
            }
        }
        return items == null ? Set.of() : items;
    }

    public Identifier id() {
        return id;
    }

    /** Whether the toggle is on; on while its config is not loaded. */
    public boolean isEnabled() {
        try {
            return enabled.getAsBoolean();
        } catch (IllegalStateException notLoaded) {
            // NeoForge throws for a config value read before its file loads, e.g. in datagen; on is the default.
            return true;
        }
    }

    /**
     * Hides {@code item} from creative tabs and JEI while the toggle is off. Call during mod construction or setup; the
     * supplier is resolved only when the toggle is read, so a registry entry that is not yet bound works.
     */
    public ContentToggle hides(Supplier<? extends ItemLike> item) {
        hidden.add(item);
        return this;
    }

    /** The items this toggle hides, resolved; for tests and tooling. */
    public List<Item> items() {
        List<Item> items = new ArrayList<>(hidden.size());
        for (Supplier<? extends ItemLike> item : hidden) {
            items.add(item.get().asItem());
        }
        return items;
    }
}
