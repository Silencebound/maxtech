package dev.silencebound.maxtech.lib.api.guide;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * The config values a mod shows in its guide pages with {@code <Config key="controller.inputPerTick" />}. A mod
 * registers one {@link Source} per mod id; a page names a value by its dotted path, which is looked up in the page's
 * namespace, or in another mod's with {@code key="<modid>:<path>"}. The guide shows the value in effect: a server
 * config's values come from the server the player is on (NeoForge sends every server config to joining clients), and a
 * value that differs from its default is marked, with both in its tooltip.
 * <p>
 * Threading: {@link #register} during mod construction, on both sides; {@link #source} from any thread. The guide reads
 * a source on the client thread while it lays a page out, never per frame. Experimental (Lib 0.6.0).
 */
@ApiStatus.Experimental
public final class GuideConfigValues {
    private static volatile Map<String, Source> sources = Map.of();

    private GuideConfigValues() {
    }

    /**
     * Registers the values of {@code modId}. The first registration for a mod id wins; a later one is ignored and
     * returns false.
     */
    public static synchronized boolean register(String modId, Source source) {
        if (sources.containsKey(modId)) {
            return false;
        }
        Map<String, Source> grown = new LinkedHashMap<>(sources);
        grown.put(modId, source);
        sources = Map.copyOf(grown);
        return true;
    }

    /** The values of {@code modId}; empty when the mod registered none. Any thread. */
    public static Optional<Source> source(String modId) {
        return Optional.ofNullable(sources.get(modId));
    }

    /** A mod's config values by dotted path. */
    public interface Source {
        /** Every path the source knows; never null, empty when it has none. */
        Set<String> keys();

        /**
         * The value at {@code key}: empty when the path is unknown. When the value cannot be read right now (no world,
         * so no server config), {@link Value#current()} is the default.
         */
        Optional<Value> value(String key);
    }

    /**
     * One value. Both are a {@code Boolean}, {@code Integer}, {@code Long}, {@code Double}, {@code String} or enum
     * constant; the guide formats numbers with thousands grouped.
     *
     * @param current
     *            the value in effect
     * @param defaultValue
     *            the value the mod ships with
     */
    public record Value(Object current, Object defaultValue) {
        /** Whether the value in effect differs from the default. */
        public boolean changed() {
            return !current.equals(defaultValue);
        }
    }
}
