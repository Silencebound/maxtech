package dev.silencebound.maxtech.lib.shim.gametest;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.Unit;
import org.slf4j.Logger;

/**
 * 26.1's {@code net.minecraft.gametest.framework.TestEnvironmentDefinition}: what a batch of GameTests sets up before
 * its tests and tears down after them. On 1.21.1 an environment is a batch ({@link PortGameTests}).
 *
 * <p>Of 26.1's built-in kinds only {@code all_of}, {@code function} and {@code weather} are here; {@code clock_time},
 * {@code game_rules} and {@code timelines} need 26.1-only world clocks, typed game rules and timelines, and Lib uses none.
 */
public interface TestEnvironmentDefinition<SavedDataType> {
    Codec<TestEnvironmentDefinition<?>> DIRECT_CODEC = Codec.lazyInitialized(() -> GameTestRegistries
            .TEST_ENVIRONMENT_DEFINITION_TYPES.byNameCodec().dispatch(TestEnvironmentDefinition::codec, c -> c));
    /** 26.1 reads a holder from the test environment registry; 1.21.1 has none, so the holder is direct. */
    Codec<Holder<TestEnvironmentDefinition<?>>> CODEC = DIRECT_CODEC.xmap(Holder::direct, Holder::value);

    static MapCodec<? extends TestEnvironmentDefinition<?>> bootstrap(
            Registry<MapCodec<? extends TestEnvironmentDefinition<?>>> registry) {
        Registry.register(registry, "all_of", AllOf.CODEC);
        Registry.register(registry, "function", Functions.CODEC);
        return Registry.register(registry, "weather", Weather.CODEC);
    }

    SavedDataType setup(ServerLevel level);

    void teardown(ServerLevel level, SavedDataType saveData);

    MapCodec<? extends TestEnvironmentDefinition<SavedDataType>> codec();

    static <T> Activation<T> activate(TestEnvironmentDefinition<T> environment, ServerLevel level) {
        return new Activation<>(environment.setup(level), environment, level);
    }

    /** A set-up environment, with what its setup returned for the teardown. */
    class Activation<T> {
        private final T value;
        private final TestEnvironmentDefinition<T> definition;
        private final ServerLevel level;

        private Activation(T value, TestEnvironmentDefinition<T> definition, ServerLevel level) {
            this.value = value;
            this.definition = definition;
            this.level = level;
        }

        public void teardown() {
            definition.teardown(level, value);
        }
    }

    /** Sets up every definition in order and tears them down in reverse. */
    record AllOf(List<Holder<TestEnvironmentDefinition<?>>> definitions)
            implements TestEnvironmentDefinition<List<? extends Activation<?>>> {
        public static final MapCodec<AllOf> CODEC = RecordCodecBuilder.mapCodec(i -> i
                .group(TestEnvironmentDefinition.CODEC.listOf().fieldOf("definitions").forGetter(AllOf::definitions))
                .apply(i, AllOf::new));

        public AllOf(TestEnvironmentDefinition<?>... defs) {
            this(Arrays.stream(defs).<Holder<TestEnvironmentDefinition<?>>>map(Holder::direct).toList());
        }

        @Override
        public List<? extends Activation<?>> setup(ServerLevel level) {
            List<Activation<?>> activations = new ArrayList<>(definitions.size());
            for (Holder<TestEnvironmentDefinition<?>> definition : definitions) {
                activations.add(activate(definition.value(), level));
            }
            return activations;
        }

        @Override
        public void teardown(ServerLevel level, List<? extends Activation<?>> activations) {
            for (int i = activations.size() - 1; i >= 0; i--) {
                activations.get(i).teardown();
            }
        }

        @Override
        public MapCodec<AllOf> codec() {
            return CODEC;
        }
    }

    /** Runs a function before and another after the batch, as the server at permission level 2, silently. */
    record Functions(Optional<ResourceLocation> setupFunction, Optional<ResourceLocation> teardownFunction)
            implements TestEnvironmentDefinition<Unit> {
        private static final Logger LOGGER = LogUtils.getLogger();
        public static final MapCodec<Functions> CODEC = RecordCodecBuilder.mapCodec(i -> i
                .group(ResourceLocation.CODEC.optionalFieldOf("setup").forGetter(Functions::setupFunction),
                        ResourceLocation.CODEC.optionalFieldOf("teardown").forGetter(Functions::teardownFunction))
                .apply(i, Functions::new));

        @Override
        public Unit setup(ServerLevel level) {
            setupFunction.ifPresent(id -> run(level, id));
            return Unit.INSTANCE;
        }

        @Override
        public void teardown(ServerLevel level, Unit saveData) {
            teardownFunction.ifPresent(id -> run(level, id));
        }

        private static void run(ServerLevel level, ResourceLocation id) {
            MinecraftServer server = level.getServer();
            server.getFunctions().get(id).ifPresentOrElse(function -> {
                CommandSourceStack source = server.createCommandSourceStack().withPermission(2).withSuppressedOutput()
                        .withLevel(level);
                server.getFunctions().execute(function, source);
            }, () -> LOGGER.error("Test Batch failed for non-existent function {}", id));
        }

        @Override
        public MapCodec<Functions> codec() {
            return CODEC;
        }
    }

    /** Sets the weather for the batch and puts the previous weather back afterwards. */
    record Weather(Weather.Type weather) implements TestEnvironmentDefinition<Weather.Type> {
        public static final MapCodec<Weather> CODEC = RecordCodecBuilder
                .mapCodec(i -> i.group(Type.CODEC.fieldOf("weather").forGetter(Weather::weather)).apply(i, Weather::new));

        @Override
        public Type setup(ServerLevel level) {
            Type previous = level.isThundering() ? Type.THUNDER : level.isRaining() ? Type.RAIN : Type.CLEAR;
            weather.apply(level);
            return previous;
        }

        @Override
        public void teardown(ServerLevel level, Type saveData) {
            level.resetWeatherCycle();
            saveData.apply(level);
        }

        @Override
        public MapCodec<Weather> codec() {
            return CODEC;
        }

        public enum Type implements StringRepresentable {
            CLEAR("clear", 100000, 0, false, false), RAIN("rain", 0, 100000, true, false), THUNDER("thunder", 0, 100000,
                    true, true);

            public static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);
            private final String name;
            private final int clearTime;
            private final int rainTime;
            private final boolean raining;
            private final boolean thundering;

            Type(String name, int clearTime, int rainTime, boolean raining, boolean thundering) {
                this.name = name;
                this.clearTime = clearTime;
                this.rainTime = rainTime;
                this.raining = raining;
                this.thundering = thundering;
            }

            void apply(ServerLevel level) {
                level.setWeatherParameters(clearTime, rainTime, raining, thundering);
            }

            @Override
            public String getSerializedName() {
                return name;
            }
        }
    }
}
