package dev.silencebound.maxtech.storage.guide;

import dev.silencebound.maxtech.lib.api.config.ConfigFile;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * What the guide's {@code <Config key="network.channels.adHoc" unit="channels" />} tag shows: a value of Storage's
 * server config, formatted for the page. The value comes from {@link StorageServerConfig}'s file: on a client in a
 * world that is the copy the server sent on join (NeoForge syncs every server config), so the page shows the numbers of
 * the server the player is on; outside a world (the guide opened from the title screen) it is the default. The GuideME
 * tag itself lives in the neoforge module ({@code StorageConfigTagCompiler}), since common cannot import GuideME.
 * <p>
 * Threading: any thread; reads the config only when asked, never per frame (GuideME compiles a page once).
 */
public final class GuideConfigText {
    /** Tooltip line with the default: "Default: %s". */
    public static final String DEFAULT_KEY = key("default");
    /** Tooltip line on a value the server changed. */
    public static final String CHANGED_KEY = key("changed");
    /** A {@code true} value. */
    public static final String ON_KEY = key("on");
    /** A {@code false} value. */
    public static final String OFF_KEY = key("off");
    private static final String PREFIX = MaxtechStorage.MOD_ID + ":";
    private static final String PRESET_PATH = "preset";

    private GuideConfigText() {
    }

    private static String key(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".guide.config." + name;
    }

    /** Declares the lang keys; once, during mod construction, so datagen writes them. */
    public static void register() {
        ModContent.CONTENT.translation(DEFAULT_KEY, "Default: %s");
        ModContent.CONTENT.translation(CHANGED_KEY, "Changed by this server");
        ModContent.CONTENT.translation(ON_KEY, "on");
        ModContent.CONTENT.translation(OFF_KEY, "off");
    }

    /**
     * The config path a page's {@code key} names: {@code network.channels.adHoc} or
     * {@code maxtech_storage:network.channels.adHoc}. Empty for another mod's namespace or an empty path.
     */
    public static Optional<String> path(String key) {
        String path = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        if (path.isEmpty() || path.indexOf(':') >= 0) {
            return Optional.empty();
        }
        return Optional.of(path);
    }

    /** The value at {@code path} of Storage's server config; empty when the path is unknown. */
    public static Optional<Value> value(String path) {
        return value(StorageServerConfig.configFile().orElse(null), path);
    }

    /** {@link #value(String)} over {@code file}; pure, for tests. */
    @SuppressWarnings("IllegalCatch") // the file may unload between isLoaded() and get() (ConfigFile#isLoaded)
    static Optional<Value> value(@Nullable ConfigFile file, String path) {
        if (file == null || path.equals(PRESET_PATH) || !file.paths().contains(path)) {
            return Optional.empty();
        }
        Object fallback = file.defaultValue(path);
        Object current = fallback;
        if (file.isLoaded()) {
            try {
                current = file.get(path);
            } catch (RuntimeException e) {
                current = fallback;
            }
        }
        return Optional.of(new Value(current, fallback));
    }

    /**
     * {@code value} as the page shows it, followed by a space and {@code unit} unless that is empty: whole numbers with
     * thousands grouped ({@code 140,000 MU}), decimals without trailing zeros ({@code 0.5}), booleans as on/off.
     */
    public static Component text(Object value, String unit) {
        MutableComponent text = switch (value) {
            case Boolean on -> Component.translatable(on ? ON_KEY : OFF_KEY);
            default -> Component.literal(number(value));
        };
        return unit.isEmpty() ? text : text.append(" " + unit);
    }

    /** A number with thousands grouped; anything else as its string. */
    static String number(Object value) {
        return switch (value) {
            case Integer i -> String.format(Locale.ROOT, "%,d", i);
            case Long l -> String.format(Locale.ROOT, "%,d", l);
            case Double d when Double.isFinite(d) -> decimal(d);
            default -> value.toString();
        };
    }

    private static String decimal(double value) {
        BigDecimal exact = BigDecimal.valueOf(value).stripTrailingZeros();
        if (exact.scale() <= 0) {
            return String.format(Locale.ROOT, "%,d", exact.longValue());
        }
        return exact.toPlainString();
    }

    /**
     * One config value.
     *
     * @param current
     *            the value in effect: the server's on a client in a world, else the default
     * @param defaultValue
     *            the value the config declares as its default
     */
    public record Value(Object current, Object defaultValue) {
        /** Whether the value in effect differs from the default. */
        public boolean changed() {
            return !current.equals(defaultValue);
        }
    }
}
