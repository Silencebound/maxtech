package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.api.config.ConfigFile;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;

/**
 * The batch environment of the energy edge GameTests: sets Lib config values for the batch in memory (no save, so
 * nothing is written or reloaded) and puts them back in teardown, and closes the edges and networks the batch's tests
 * made, also those of a failed test, so nothing keeps moving energy into later batches.
 *
 * @param option
 *            dotted Lib config paths separated by {@code ;}, or empty for none
 * @param value
 *            one value per path, as written in the config file, separated by {@code ;}
 */
record EnergyConfigEnvironment(String option, String value) implements TestEnvironmentDefinition<Object> {
    static final MapCodec<EnergyConfigEnvironment> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(Codec.STRING.fieldOf("option").forGetter(EnergyConfigEnvironment::option),
                    Codec.STRING.fieldOf("value").forGetter(EnergyConfigEnvironment::value))
            .apply(instance, EnergyConfigEnvironment::new));

    private static final ObjectArrayList<Runnable> CLEANUPS = new ObjectArrayList<>();
    private static final RegistryHelper.Registrar<MapCodec<? extends TestEnvironmentDefinition<?>>> TYPES = Platform
            .get().registry().registrar(MaxtechLib.MOD_ID, Registries.TEST_ENVIRONMENT_DEFINITION_TYPE);

    static {
        TYPES.register("energy_config", () -> CODEC);
    }

    /** Loads the class during mod construction so the codec is registered in time. */
    static void init() {
    }

    /** Runs {@code cleanup} when the current batch ends. Server thread only. */
    static void onTeardown(Runnable cleanup) {
        CLEANUPS.add(cleanup);
    }

    private static ConfigFile config() {
        ConfigFile file = ServerConfig.configFile();
        if (file == null || !file.isLoaded()) {
            throw new IllegalStateException("Lib's server config is not loaded");
        }
        return file;
    }

    @Override
    public Object setup(ServerLevel level) {
        if (option.isEmpty()) {
            return List.of();
        }
        String[] paths = option.split(";", -1);
        String[] values = value.split(";", -1);
        if (paths.length != values.length) {
            throw new IllegalArgumentException("One value per option: " + option + " = " + value);
        }
        ConfigFile file = config();
        List<Object> previous = new ArrayList<>(paths.length);
        for (int i = 0; i < paths.length; i++) {
            Object before = file.get(paths[i]);
            Object parsed = switch (before) {
                case Integer _ -> Integer.parseInt(values[i]);
                case Long _ -> Long.parseLong(values[i]);
                case Boolean _ -> Boolean.parseBoolean(values[i]);
                default -> throw new IllegalArgumentException("Unsupported option type for " + paths[i]);
            };
            previous.add(before);
            file.set(paths[i], parsed);
        }
        return previous;
    }

    @Override
    public void teardown(ServerLevel level, Object previous) {
        for (int i = 0; i < CLEANUPS.size(); i++) {
            CLEANUPS.get(i).run();
        }
        CLEANUPS.clear();
        if (!option.isEmpty() && previous instanceof List<?> values) {
            String[] paths = option.split(";", -1);
            ConfigFile file = config();
            for (int i = paths.length - 1; i >= 0; i--) {
                Object before = values.get(i);
                if (before != null) {
                    file.set(paths[i], before);
                }
            }
        }
    }

    @Override
    public MapCodec<EnergyConfigEnvironment> codec() {
        return CODEC;
    }
}
