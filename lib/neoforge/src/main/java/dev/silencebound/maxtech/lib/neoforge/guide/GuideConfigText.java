package dev.silencebound.maxtech.lib.neoforge.guide;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.guide.GuideConfigValues;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * What a guide's {@code <Config key="network.channels.adHoc" unit="channels" />} tag shows: a mod's config value from
 * {@link GuideConfigValues}, formatted for the page. A key without a namespace names a value of the page's mod; with
 * one ({@code maxtech_lib:energy.feRate}) a value of that mod. On a client in a world a server config's value is the
 * copy the server sent on join (NeoForge syncs every server config); outside a world it is the default.
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

    private GuideConfigText() {
    }

    private static String key(String name) {
        return "gui." + MaxtechLib.MOD_ID + ".guide.config." + name;
    }

    /**
     * The mod and path a page's {@code key} names: {@code network.channels.adHoc} in {@code namespace}, or
     * {@code maxtech_storage:network.channels.adHoc}. Empty for an empty mod id or path, or a path with a colon.
     */
    public static Optional<Ref> ref(String key, String namespace) {
        int colon = key.indexOf(':');
        String modId = colon < 0 ? namespace : key.substring(0, colon);
        String path = colon < 0 ? key : key.substring(colon + 1);
        if (modId.isEmpty() || path.isEmpty() || path.indexOf(':') >= 0) {
            return Optional.empty();
        }
        return Optional.of(new Ref(modId, path));
    }

    /** The value {@code key} names, read from its mod's {@link GuideConfigValues} source; empty when unknown. */
    public static Optional<GuideConfigValues.Value> value(String key, String namespace) {
        return ref(key, namespace).flatMap(GuideConfigText::value);
    }

    /** The value at {@code ref}; empty when its mod registered no source or the path is unknown. */
    public static Optional<GuideConfigValues.Value> value(Ref ref) {
        return GuideConfigValues.source(ref.modId()).flatMap(source -> source.value(ref.path()));
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
     * A config value's address.
     *
     * @param modId
     *            the mod whose {@link GuideConfigValues} source holds it
     * @param path
     *            its dotted path in that source
     */
    public record Ref(String modId, String path) {
    }
}
