package dev.silencebound.maxtech.lib.config;

import dev.silencebound.maxtech.lib.api.config.ConfigBuilder;
import dev.silencebound.maxtech.lib.api.config.ConfigFile;
import dev.silencebound.maxtech.lib.platform.ConfigHelper;
import dev.silencebound.maxtech.lib.api.config.ConfigType;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ConfigHelper} that builds no config file and only records each file's outline: the dotted path of every
 * value and section, and whether a value has a comment. It nests sections and attaches comments the way NeoForge's
 * helper does (a dotted path opens one section per segment; a comment belongs to the next value or pushed section), so
 * unit tests can check the config names tables ({@link LibConfigNames}, Storage's {@code StorageConfigNames}) without
 * the game: a test platform hands it out from {@code config()}, the test loads the mod's config classes and compares.
 * Datagen and the {@code config_lang_complete} GameTests check the real specs. Not thread-safe; for tests.
 */
public final class ConfigOutline implements ConfigHelper {
    /**
     * One value or section of a recorded file.
     *
     * @param commented
     *            whether a comment (the screen's tooltip) was given for it
     */
    public record Entry(String modId, ConfigType type, String path, boolean section, boolean commented) {
    }

    private final Map<String, List<Entry>> files = new LinkedHashMap<>();

    @Override
    public ConfigBuilder builder(String modId) {
        return new Recorder();
    }

    @Override
    public ConfigFile register(String modId, ConfigType type, ConfigBuilder builder) {
        if (!(builder instanceof Recorder recorder)) {
            throw new IllegalArgumentException("Builder was not created by this helper");
        }
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Node> node : recorder.nodes.entrySet()) {
            entries.add(new Entry(modId, type, node.getKey(), node.getValue().section, node.getValue().commented));
        }
        // Keyed by file, so loading a config class twice in one test JVM does not count its values twice.
        files.put(modId + "/" + type, entries);
        return new OutlineFile(recorder.defaults);
    }

    @Override
    public Path configDirectory() {
        return Path.of("config");
    }

    /** Every recorded value and section of {@code modId}'s files, in registration and declaration order. */
    public List<Entry> entries(String modId) {
        List<Entry> entries = new ArrayList<>();
        for (List<Entry> file : files.values()) {
            for (Entry entry : file) {
                if (entry.modId().equals(modId)) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    /**
     * Where {@code names} (English names by dotted path) and the recorded {@code entries} disagree: a value or section
     * without a name, a value without a comment for its tooltip, or a name for a path no file defines. Empty when they
     * agree.
     */
    public static List<String> problems(List<Entry> entries, Map<String, String> names) {
        List<String> problems = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (!paths.add(entry.path()) && entry.section()) {
                continue;
            }
            if (!names.containsKey(entry.path())) {
                problems.add(entry.path() + " has no name");
            }
            if (!entry.section() && !entry.commented()) {
                problems.add(entry.path() + " has no comment for its tooltip");
            }
        }
        for (String path : new TreeSet<>(names.keySet())) {
            if (!paths.contains(path)) {
                problems.add(path + " has a name but no config defines it");
            }
        }
        return problems;
    }

    private static final class Node {
        private final boolean section;
        private final boolean commented;

        Node(boolean section, boolean commented) {
            this.section = section;
            this.commented = commented;
        }
    }

    private static final class Recorder implements ConfigBuilder {
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<String, Object> defaults = new LinkedHashMap<>();
        private final Deque<String> sections = new ArrayDeque<>();
        private boolean comment;

        @Override
        public ConfigBuilder comment(String... lines) {
            for (String line : lines) {
                comment |= !line.isBlank();
            }
            return this;
        }

        @Override
        public ConfigBuilder worldRestart() {
            return this;
        }

        @Override
        public ConfigBuilder gameRestart() {
            return this;
        }

        @Override
        public ConfigBuilder push(String section) {
            for (String part : section.split("\\.", -1)) {
                enterSection(part, comment);
                comment = false;
            }
            return this;
        }

        @Override
        public ConfigBuilder pop() {
            sections.removeLast();
            return this;
        }

        @Override
        public BooleanSupplier define(String path, boolean defaultValue) {
            value(path, defaultValue);
            return () -> defaultValue;
        }

        @Override
        public IntSupplier defineInRange(String path, int defaultValue, int min, int max) {
            value(path, defaultValue);
            return () -> defaultValue;
        }

        @Override
        public LongSupplier defineInRange(String path, long defaultValue, long min, long max) {
            value(path, defaultValue);
            return () -> defaultValue;
        }

        @Override
        public DoubleSupplier defineInRange(String path, double defaultValue, double min, double max) {
            value(path, defaultValue);
            return () -> defaultValue;
        }

        @Override
        public Supplier<String> define(String path, String defaultValue) {
            value(path, defaultValue);
            return () -> defaultValue;
        }

        @Override
        public Supplier<String> define(String path, String defaultValue, Predicate<String> valid) {
            value(path, defaultValue);
            return () -> defaultValue;
        }

        @Override
        public <E extends Enum<E>> Supplier<E> defineEnum(String path, E defaultValue) {
            value(path, defaultValue);
            return () -> defaultValue;
        }

        private void enterSection(String part, boolean commented) {
            sections.addLast(part);
            String path = String.join(".", sections);
            Node old = nodes.get(path);
            if (old == null || (commented && !old.commented)) {
                nodes.put(path, new Node(true, commented));
            }
        }

        /** Opens the sections of a dotted {@code path}, records the value with the held comment, closes them again. */
        private void value(String path, Object defaultValue) {
            String[] parts = path.split("\\.", -1);
            for (int i = 0; i < parts.length - 1; i++) {
                enterSection(parts[i], false);
            }
            String full = sections.isEmpty()
                    ? parts[parts.length - 1]
                    : String.join(".", sections) + "." + parts[parts.length - 1];
            if (nodes.putIfAbsent(full, new Node(false, comment)) != null) {
                throw new IllegalArgumentException("Duplicate config path " + full);
            }
            defaults.put(full, defaultValue);
            comment = false;
            for (int i = 0; i < parts.length - 1; i++) {
                sections.removeLast();
            }
        }
    }

    /** The registered file: never loaded, so reads give the defaults; writes are refused. */
    private record OutlineFile(Map<String, Object> defaults) implements ConfigFile {
        @Override
        public Set<String> paths() {
            return Collections.unmodifiableSet(defaults.keySet());
        }

        @Override
        public boolean isLoaded() {
            return false;
        }

        @Override
        public Object get(String path) {
            return defaultValue(path);
        }

        @Override
        public Object defaultValue(String path) {
            Object value = defaults.get(path);
            if (value == null) {
                throw new IllegalArgumentException("Unknown config path " + path);
            }
            return value;
        }

        @Override
        public void set(String path, Object value) {
            throw new UnsupportedOperationException("An outline has no values to set");
        }

        @Override
        public void save() {
        }

        @Override
        public void onLoad(Consumer<@Nullable Path> listener) {
        }
    }
}
