package dev.silencebound.maxtech.lib.neoforge.platform;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jspecify.annotations.Nullable;

final class NeoForgeConfigHelper implements ConfigHelper {
    /** The translation key prefix of {@code modId}'s config values and sections, as NeoForge's screen looks them up. */
    static String translationPrefix(String modId) {
        return modId + ".configuration.";
    }

    @Override
    public ConfigBuilder builder(String modId) {
        return new SpecBuilder(translationPrefix(modId));
    }

    @Override
    public ConfigFile register(String modId, ConfigType type, ConfigBuilder builder) {
        if (!(builder instanceof SpecBuilder specBuilder)) {
            throw new IllegalArgumentException("Builder was not created by this platform");
        }
        ModConfig.Type modConfigType = switch (type) {
            case SERVER -> ModConfig.Type.SERVER;
            case CLIENT -> ModConfig.Type.CLIENT;
            case COMMON -> ModConfig.Type.COMMON;
        };
        ModConfigSpec spec = specBuilder.builder.build();
        ModContainers.get(modId).registerConfig(modConfigType, spec);
        return new SpecFile(modId, spec, specBuilder.values);
    }

    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    /**
     * Wraps NeoForge's builder so every value and section gets a translation key from its full dotted path (NeoForge's
     * own fallback uses only the last segment, which repeats: {@code veinsPerChunk}, {@code tier1}). Comments and
     * restart marks are held until the value is defined, because a dotted value path is split into pushed sections
     * first and NeoForge would otherwise attach them to the first of those sections.
     */
    private static final class SpecBuilder implements ConfigBuilder {
        private final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        private final Map<String, ModConfigSpec.ConfigValue<?>> values = new LinkedHashMap<>();
        private final Deque<String> sections = new ArrayDeque<>();
        private final String prefix;
        private final List<String> comment = new ArrayList<>();
        private ModConfigSpec.RestartType restart = ModConfigSpec.RestartType.NONE;

        SpecBuilder(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public ConfigBuilder comment(String... lines) {
            comment.addAll(List.of(lines));
            return this;
        }

        @Override
        public ConfigBuilder worldRestart() {
            restart = ModConfigSpec.RestartType.WORLD;
            return this;
        }

        @Override
        public ConfigBuilder gameRestart() {
            restart = ModConfigSpec.RestartType.GAME;
            return this;
        }

        @Override
        public ConfigBuilder push(String section) {
            if (restart != ModConfigSpec.RestartType.NONE) {
                throw new IllegalStateException("A restart mark applies to a value, not to section " + section);
            }
            for (String part : section.split("\\.", -1)) {
                if (!comment.isEmpty()) {
                    builder.comment(comment.toArray(String[]::new));
                    comment.clear();
                }
                sections.addLast(part);
                builder.translation(prefix + String.join(".", sections)).push(part);
            }
            return this;
        }

        @Override
        public ConfigBuilder pop() {
            builder.pop();
            sections.removeLast();
            return this;
        }

        @Override
        public BooleanSupplier define(String path, boolean defaultValue) {
            String leaf = enter(path);
            return track(path, builder.define(leaf, defaultValue));
        }

        @Override
        public IntSupplier defineInRange(String path, int defaultValue, int min, int max) {
            String leaf = enter(path);
            return track(path, builder.defineInRange(leaf, defaultValue, min, max));
        }

        @Override
        public LongSupplier defineInRange(String path, long defaultValue, long min, long max) {
            String leaf = enter(path);
            return track(path, builder.defineInRange(leaf, defaultValue, min, max));
        }

        @Override
        public DoubleSupplier defineInRange(String path, double defaultValue, double min, double max) {
            String leaf = enter(path);
            return track(path, builder.defineInRange(leaf, defaultValue, min, max));
        }

        @Override
        public Supplier<String> define(String path, String defaultValue) {
            String leaf = enter(path);
            return track(path, builder.define(leaf, defaultValue));
        }

        @Override
        public Supplier<String> define(String path, String defaultValue, Predicate<String> valid) {
            String leaf = enter(path);
            return track(path, builder.define(leaf, defaultValue, value -> value instanceof String s && valid.test(s)));
        }

        @Override
        public <E extends Enum<E>> Supplier<E> defineEnum(String path, E defaultValue) {
            String leaf = enter(path);
            return track(path, builder.defineEnum(leaf, defaultValue));
        }

        /**
         * Pushes the sections of a dotted {@code path} (each with its key), then hands the held comment, restart mark
         * and the value's key to NeoForge's builder; returns the last segment, which the caller defines.
         */
        private String enter(String path) {
            int dot = path.lastIndexOf('.');
            if (dot >= 0) {
                for (String part : path.substring(0, dot).split("\\.", -1)) {
                    sections.addLast(part);
                    builder.translation(prefix + String.join(".", sections)).push(part);
                }
            }
            String leaf = path.substring(dot + 1);
            if (!comment.isEmpty()) {
                builder.comment(comment.toArray(String[]::new));
                comment.clear();
            }
            switch (restart) {
                case WORLD -> builder.worldRestart();
                case GAME -> builder.gameRestart();
                case NONE -> {
                }
            }
            restart = ModConfigSpec.RestartType.NONE;
            builder.translation(prefix + (sections.isEmpty() ? leaf : String.join(".", sections) + "." + leaf));
            return leaf;
        }

        /** Records the value under its full path and pops the sections {@link #enter} pushed for a dotted path. */
        private <V extends ModConfigSpec.ConfigValue<?>> V track(String path, V value) {
            int pushed = path.split("\\.", -1).length - 1;
            for (int i = 0; i < pushed; i++) {
                builder.pop();
                sections.removeLast();
            }
            String fullPath = sections.isEmpty() ? path : String.join(".", sections) + "." + path;
            if (values.putIfAbsent(fullPath, value) != null) {
                throw new IllegalArgumentException("Duplicate config path " + fullPath);
            }
            return value;
        }
    }

    private record SpecFile(String modId, ModConfigSpec spec,
            Map<String, ModConfigSpec.ConfigValue<?>> values) implements ConfigFile {

        @Override
        public Set<String> paths() {
            return Collections.unmodifiableSet(values.keySet());
        }

        @Override
        public boolean isLoaded() {
            return spec.isLoaded();
        }

        @Override
        public Object get(String path) {
            return value(path).get();
        }

        @Override
        public Object defaultValue(String path) {
            return value(path).getDefault();
        }

        @Override
        public void set(String path, Object newValue) {
            ModConfigSpec.ConfigValue<?> value = value(path);
            if (typeOf(newValue) != typeOf(value.getDefault())) {
                throw new IllegalArgumentException("Config value " + path + " is a "
                        + typeOf(value.getDefault()).getSimpleName() + ", not " + typeOf(newValue).getSimpleName());
            }
            setUnchecked(value, newValue);
        }

        @Override
        public void save() {
            spec.save();
        }

        @Override
        public void onLoad(Consumer<@Nullable Path> listener) {
            Consumer<ModConfigEvent> handler = event -> {
                if (spec.equals(event.getConfig().getSpec())) {
                    listener.accept(event.getConfig().getFullPath());
                }
            };
            ModContainers.eventBus(modId).addListener(ModConfigEvent.Loading.class, handler::accept);
            ModContainers.eventBus(modId).addListener(ModConfigEvent.Reloading.class, handler::accept);
        }

        private ModConfigSpec.ConfigValue<?> value(String path) {
            ModConfigSpec.ConfigValue<?> value = values.get(path);
            if (value == null) {
                throw new IllegalArgumentException("Unknown config path " + path);
            }
            return value;
        }

        private static Class<?> typeOf(Object value) {
            return value instanceof Enum<?> constant ? constant.getDeclaringClass() : value.getClass();
        }

        // Safe: set() checked that newValue has the runtime type of the value's default.
        @SuppressWarnings("unchecked")
        private static <T> void setUnchecked(ModConfigSpec.ConfigValue<T> value, Object newValue) {
            value.set((T) newValue);
            // set() leaves the cache of a value marked for a restart alone; code that sets a value (presets, tests)
            // means it to apply now.
            value.clearCache();
        }
    }
}
