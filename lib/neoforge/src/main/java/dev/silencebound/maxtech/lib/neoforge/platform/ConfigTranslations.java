package dev.silencebound.maxtech.lib.neoforge.platform;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jspecify.annotations.Nullable;

/**
 * The lang keys of a mod's config files as NeoForge's config screen looks them up: one per value and section, from the
 * specs FML registered. Datagen writes the English names and tooltips from it, and GameTests check the committed lang
 * file against it. Works before the files load (in datagen too); any thread once mods are constructed.
 */
public final class ConfigTranslations {
    private static final String TOOLTIP = ".tooltip";

    private ConfigTranslations() {
    }

    /**
     * One value or section of a config file.
     *
     * @param type
     *            the file's type
     * @param path
     *            the dotted path in the file
     * @param key
     *            the translation key the screen uses; empty when the spec set none
     * @param tooltip
     *            the comment as one line, without the {@code Range:}, {@code Default:} and {@code Allowed Values:}
     *            lines NeoForge adds, or empty
     * @param section
     *            whether this is a section rather than a value
     * @param restart
     *            the restart a change needs; {@code NONE} for sections
     */
    public record Entry(ModConfig.Type type, String path, String key, String tooltip, boolean section,
            ModConfigSpec.RestartType restart) {
        /** The key of the tooltip line. */
        public String tooltipKey() {
            return key + TOOLTIP;
        }
    }

    /** Every value and section of every config file {@code modId} registered, in declaration order. */
    public static List<Entry> entries(String modId) {
        List<Entry> entries = new ArrayList<>();
        for (ModConfig config : ModConfigs.getModConfigs(modId)) {
            if (config.getSpec() instanceof ModConfigSpec spec) {
                walk(config.getType(), spec, spec.getSpec(), new ArrayList<>(), entries);
            }
        }
        return entries;
    }

    /**
     * The English lang lines for {@code modId}'s config: each key with its name from {@code names} (by dotted path),
     * and each value's and commented section's tooltip from its comment. Throws, listing them, when a name or a value's
     * comment is missing or when two files give one key different text.
     */
    public static Map<String, String> english(String modId, Map<String, String> names) {
        Map<String, String> lines = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Entry entry : entries(modId)) {
            String name = names.get(entry.path());
            if (entry.key().isEmpty()) {
                problems.add(entry.path() + " has no translation key");
                continue;
            }
            if (name == null) {
                problems.add(entry.path() + " has no name");
            } else {
                put(lines, entry.key(), name, problems);
            }
            if (!entry.tooltip().isEmpty()) {
                put(lines, entry.tooltipKey(), entry.tooltip(), problems);
            } else if (!entry.section()) {
                problems.add(entry.path() + " has no comment for its tooltip");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Config lang of " + modId + ": " + String.join("; ", problems));
        }
        return lines;
    }

    /**
     * Where {@code lang} falls short of {@code modId}'s config screen: a value or section without a name, a value or
     * commented section without a tooltip, or a tooltip that no longer matches its comment (datagen not run after a
     * change). Empty when there is nothing.
     */
    public static List<String> problems(String modId, Map<String, String> lang) {
        List<String> problems = new ArrayList<>();
        for (Entry entry : entries(modId)) {
            if (entry.key().isEmpty()) {
                problems.add(entry.path() + " has no translation key");
                continue;
            }
            if (!lang.containsKey(entry.key())) {
                problems.add("no name: " + entry.key());
            }
            String tooltip = lang.get(entry.tooltipKey());
            if ((!entry.section() || !entry.tooltip().isEmpty()) && tooltip == null) {
                problems.add("no tooltip: " + entry.tooltipKey());
            } else if (tooltip != null && !entry.tooltip().isEmpty() && !tooltip.equals(entry.tooltip())) {
                problems.add("tooltip differs from the comment (run datagen): " + entry.tooltipKey());
            }
        }
        return problems;
    }

    /**
     * The {@code en_us} lang file of {@code modId}, read through {@code anchor}, a class from the mod's own jar; empty
     * when there is none.
     */
    public static Map<String, String> englishLang(Class<?> anchor, String modId) {
        Map<String, String> lang = new LinkedHashMap<>();
        try (InputStream in = anchor.getResourceAsStream("/assets/" + modId + "/lang/en_us.json")) {
            if (in == null) {
                return lang;
            }
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (Map.Entry<String, JsonElement> line : json.entrySet()) {
                lang.put(line.getKey(), line.getValue().getAsString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return lang;
    }

    private static void put(Map<String, String> lines, String key, String text, List<String> problems) {
        String old = lines.putIfAbsent(key, text);
        if (old != null && !old.equals(text)) {
            problems.add(key + " has two texts: \"" + old + "\" and \"" + text + "\"");
        }
    }

    private static void walk(ModConfig.Type type, ModConfigSpec spec, UnmodifiableConfig level, List<String> path,
            List<Entry> entries) {
        for (UnmodifiableConfig.Entry child : level.entrySet()) {
            path.add(child.getKey());
            String dotted = String.join(".", path);
            Object raw = child.getRawValue();
            if (raw instanceof ModConfigSpec.ValueSpec value) {
                entries.add(new Entry(type, dotted, orEmpty(value.getTranslationKey()), tooltip(value.getComment()),
                        false, value.restartType()));
            } else if (raw instanceof UnmodifiableConfig section) {
                entries.add(new Entry(type, dotted, orEmpty(spec.getLevelTranslationKey(path)),
                        tooltip(spec.getLevelComment(path)), true, ModConfigSpec.RestartType.NONE));
                walk(type, spec, section, path, entries);
            }
            path.removeLast();
        }
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    /** The comment's lines joined with spaces, without the range, default and allowed-values lines NeoForge adds. */
    static String tooltip(@Nullable String comment) {
        if (comment == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String line : comment.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("Range:") || trimmed.startsWith("Default:")
                    || trimmed.startsWith("Allowed Values:")) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(trimmed);
        }
        return text.toString();
    }
}
