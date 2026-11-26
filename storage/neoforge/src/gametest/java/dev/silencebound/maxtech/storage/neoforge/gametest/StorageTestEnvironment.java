package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.storage.logic.controller.LogicEvents;
import dev.silencebound.maxtech.lib.api.network.UnloadedChunkMode;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import dev.silencebound.maxtech.lib.network.NetworkGraph;
import dev.silencebound.maxtech.lib.platform.ConfigFile;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.job.CraftDelivery;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.job.CraftingMode;
import dev.silencebound.maxtech.storage.dev.Showcase;
import dev.silencebound.maxtech.storage.network.ChannelMode;
import dev.silencebound.maxtech.storage.research.ResearchPreset;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jspecify.annotations.Nullable;

/**
 * The batch environment of every Storage GameTest. In teardown it clears the areas of the batch's tests and every
 * showcase build, also those of a failed test, so devices with scheduled tasks and powered networks stop instead of
 * running through later batches (the game test server never clears finished tests). Optionally it switches one config
 * value for the batch, inside the reloading event of its own save under FML's config lock as Lib's and the addons'
 * config environments do (issue #43; their classes are not on this classpath), and turns network and scheduler timing
 * on for perf tests.
 *
 * @param option
 *            dotted config path, of Storage's server file or one of {@link #LIB_OPTIONS}, or empty for none; several
 *            paths separated by {@code ;}
 * @param value
 *            the value as written in the config file; one per path, separated by {@code ;} likewise
 * @param profiling
 *            whether to time network graphs and level schedulers
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
record StorageTestEnvironment(String option, String value,
        boolean profiling) implements TestEnvironmentDefinition<Object> {
    static final MapCodec<StorageTestEnvironment> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(Codec.STRING.fieldOf("option").forGetter(StorageTestEnvironment::option),
                    Codec.STRING.fieldOf("value").forGetter(StorageTestEnvironment::value),
                    Codec.BOOL.fieldOf("profiling").forGetter(StorageTestEnvironment::profiling))
            .apply(instance, StorageTestEnvironment::new));

    /** Lib's option, whose reloading event fires on Lib's bus. */
    static final String UNLOADED_MODE = "networks.unloadedChunkMode";
    /** Lib's option too: a network's scheduler time per tick. */
    static final String SCHEDULER_BUDGET = "scheduler.ownerBudgetMicros";
    /**
     * Channels (M7N) far above what a test places: perf tests that measure the cost of hundreds of devices lay them out
     * for density, not for channels, so they add these to their own option ({@link #roomy}).
     */
    private static final String ROOMY_OPTIONS = "network.channels.tier1;network.channels.tier2;network.channels.tier3;"
            + "network.channels.tier4;network.channels.rootLink;network.channels.adHoc";
    private static final String ROOMY_VALUES = "100000;100000;100000;100000;100000;1000";
    /** Whether channels limit networks (B09 item 12); {@link #switchChannelMode} changes it inside a test. */
    static final String CHANNEL_MODE = "network.channels.mode";
    /** The old key of the efficiency core card's percent (M7R item 8). */
    static final String MOVED_EFFICIENCY = "controller.modules.efficiencyCore.percent";
    /**
     * Lib's options, by path; Storage's are any path of its server file, set through the file by path because its
     * values read as defaults while the file is not loaded (issue #333) and so are not NeoForge's value objects.
     */
    private static final Map<String, Object> LIB_OPTIONS = Map.of(UNLOADED_MODE, ServerConfig.UNLOADED_CHUNK_MODE,
            SCHEDULER_BUDGET, ServerConfig.SCHEDULER_OWNER_BUDGET_MICROS,
            // Lib's FE rate and export switch, for Storage's MU tests (MLE item 4).
            "energy.feRateFe", ServerConfig.FE_RATE_FE, "energy.feRateMu", ServerConfig.FE_RATE_MU,
            "energy.exportEnabled", ServerConfig.EXPORT_ENABLED);
    private static final ObjectArrayList<AABB> AREAS = new ObjectArrayList<>();
    private static final ThreadLocal<@Nullable Runnable> OWN_SAVE = new ThreadLocal<>();
    private static final RegistryHelper.Registrar<MapCodec<? extends TestEnvironmentDefinition<?>>> TYPES = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.TEST_ENVIRONMENT_DEFINITION_TYPE);

    static {
        TYPES.register("storage", () -> CODEC);
        ModList.get().getModContainerById(MaxtechLib.MOD_ID).map(ModContainer::getEventBus)
                .ifPresent(bus -> bus.addListener(ModConfigEvent.Reloading.class, StorageTestEnvironment::runOwnSave));
    }

    /** An environment that only clears its tests' areas. */
    static StorageTestEnvironment plain() {
        return new StorageTestEnvironment("", "", false);
    }

    /**
     * {@code option} set to {@code value} (none when empty), with channels far above what any test places: for perf
     * tests whose hundreds of devices touch each other or share a tier-1 cable.
     */
    static StorageTestEnvironment roomy(String option, String value, boolean profiling) {
        return option.isEmpty()
                ? new StorageTestEnvironment(ROOMY_OPTIONS, ROOMY_VALUES, profiling)
                : new StorageTestEnvironment(option + ";" + ROOMY_OPTIONS, value + ";" + ROOMY_VALUES, profiling);
    }

    /**
     * Switches the channel mode in the middle of a test, by the same save and reloading event as a batch's setup, as an
     * admin editing the file on a running server does. Only for a batch whose environment sets {@link #CHANNEL_MODE}
     * itself, so its teardown puts the value from before the batch back. Server thread.
     */
    static void switchChannelMode(ChannelMode mode) {
        set(config(CHANNEL_MODE), mode);
    }

    /** Wraps {@code function} so its test's area is cleared when the batch ends. */
    static Consumer<GameTestHelper> clearing(Consumer<GameTestHelper> function) {
        return helper -> {
            AREAS.add(helper.getBounds());
            function.accept(helper);
        };
    }

    @SubscribeEvent
    static void onReloading(ModConfigEvent.Reloading event) {
        runOwnSave(event);
    }

    private static void runOwnSave(ModConfigEvent.Reloading event) {
        Runnable change = OWN_SAVE.get();
        if (change != null) {
            // Cleared first: the nested save inside the change fires this again.
            OWN_SAVE.remove();
            change.run();
        }
    }

    /** One config value the environment reads and writes. */
    private interface Option {
        Object get();

        /** Sets the value and saves the file, which fires its reloading event. */
        void setAndSave(Object value);
    }

    private static void set(Option config, Object value) {
        OWN_SAVE.set(() -> config.setAndSave(value));
        try {
            config.setAndSave(value);
            if (OWN_SAVE.get() != null) {
                throw new IllegalStateException("Saving the config fired no reloading event for " + config);
            }
        } finally {
            OWN_SAVE.remove();
        }
    }

    // The value is parsed into the type the option held before, so the cast matches.
    @SuppressWarnings("unchecked")
    private static Option config(String path) {
        if (LIB_OPTIONS.get(path) instanceof ModConfigSpec.ConfigValue<?> lib) {
            ModConfigSpec.ConfigValue<Object> config = (ModConfigSpec.ConfigValue<Object>) lib;
            return new Option() {
                @Override
                public Object get() {
                    return config.get();
                }

                @Override
                public void setAndSave(Object value) {
                    config.set(value);
                    config.save();
                }

                @Override
                public String toString() {
                    return path;
                }
            };
        }
        ConfigFile file = StorageServerConfig.configFile().orElseThrow();
        if (!file.paths().contains(path)) {
            throw new IllegalArgumentException("No config option " + path + " for a test environment");
        }
        return new Option() {
            @Override
            public Object get() {
                return file.get(path);
            }

            @Override
            public void setAndSave(Object value) {
                file.set(path, value);
                file.save();
            }

            @Override
            public String toString() {
                return path;
            }
        };
    }

    private List<String> paths() {
        return List.of(option.split(";", -1));
    }

    @Override
    public Object setup(ServerLevel level) {
        if (profiling) {
            NetworkGraph.setProfiling(true);
            LevelTasks.setProfiling(true);
        }
        if (option.isEmpty()) {
            return "";
        }
        List<String> paths = paths();
        String[] values = value.split(";", -1);
        if (values.length != paths.size()) {
            throw new IllegalArgumentException("One value per option: " + option + " = " + value);
        }
        List<Object> previous = new ArrayList<>(paths.size());
        for (int i = 0; i < paths.size(); i++) {
            Option config = config(paths.get(i));
            Object before = config.get();
            String text = values[i];
            Object parsed = switch (before) {
                case Integer _ -> Integer.parseInt(text);
                case Long _ -> Long.parseLong(text);
                case UnloadedChunkMode _ -> UnloadedChunkMode.valueOf(text.toUpperCase(Locale.ROOT));
                case ResearchPreset _ -> ResearchPreset.valueOf(text.toUpperCase(Locale.ROOT));
                case CraftingMode _ -> CraftingMode.valueOf(text.toUpperCase(Locale.ROOT));
                case ChannelMode _ -> ChannelMode.valueOf(text.toUpperCase(Locale.ROOT));
                case Double _ -> Double.parseDouble(text);
                case Boolean _ -> Boolean.parseBoolean(text);
                default -> throw new IllegalArgumentException("Unsupported option type for " + paths.get(i));
            };
            previous.add(before);
            set(config, parsed);
        }
        return previous;
    }

    @Override
    public void teardown(ServerLevel level, Object previous) {
        if (profiling) {
            NetworkGraph.setProfiling(false);
            LevelTasks.setProfiling(false);
        }
        if (!option.isEmpty() && previous instanceof List<?> values) {
            List<String> paths = paths();
            for (int i = paths.size() - 1; i >= 0; i--) {
                set(config(paths.get(i)), values.get(i));
            }
        }
        // A test's logic event listener never outlives its batch, also when the test failed: later batches (the perf
        // budgets among them) run as without KubeJS.
        LogicEvents.setListener(null);
        // Likewise a delivery test's requester lookup (CraftDeliveryGameTests).
        CraftDelivery.setPlayerLookup(null);
        // Showcase builds reach above their tests' areas, and a showcase test that failed never got to its own clear:
        // its stations would keep working through later batches, perf budgets among them.
        Showcase.clear(level);
        StockKeepingGameTests.QUEUE.forgetAll();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < AREAS.size(); i++) {
            AABB area = AREAS.get(i);
            // The bounds span whole blocks: max is one past the last block.
            for (int x = Mth.floor(area.minX); x < Mth.ceil(area.maxX); x++) {
                for (int y = Mth.floor(area.minY); y < Mth.ceil(area.maxY); y++) {
                    for (int z = Mth.floor(area.minZ); z < Mth.ceil(area.maxZ); z++) {
                        level.setBlock(pos.set(x, y, z), Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
                    }
                }
            }
        }
        AREAS.clear();
    }

    @Override
    public MapCodec<StorageTestEnvironment> codec() {
        return CODEC;
    }

    /** Loads the class during mod construction so the codec is registered in time. */
    static void init() {
    }
}
