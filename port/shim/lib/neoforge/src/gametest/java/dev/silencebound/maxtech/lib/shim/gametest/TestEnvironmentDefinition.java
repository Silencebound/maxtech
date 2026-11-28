package dev.silencebound.maxtech.lib.shim.gametest;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryFileCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.Unit;
import org.slf4j.Logger;
public interface TestEnvironmentDefinition<SavedDataType> {
    Codec<TestEnvironmentDefinition<?>> DIRECT_CODEC = null;
    Codec<Holder<TestEnvironmentDefinition<?>>> CODEC = null;
    static MapCodec<? extends TestEnvironmentDefinition<?>> bootstrap(Registry<MapCodec<? extends TestEnvironmentDefinition<?>>> registry) { throw new UnsupportedOperationException("port stub"); }
    SavedDataType setup(ServerLevel level);
    void teardown(final ServerLevel level, final SavedDataType saveData);
    MapCodec<? extends TestEnvironmentDefinition<SavedDataType>> codec();
    static <T> TestEnvironmentDefinition.Activation<T> activate(TestEnvironmentDefinition<T> environment, ServerLevel level) { throw new UnsupportedOperationException("port stub"); }
    public static class Activation<T> {
        public void teardown() { throw new UnsupportedOperationException("port stub"); }
    }
    public record AllOf(List<Holder<TestEnvironmentDefinition<?>>> definitions)
        implements TestEnvironmentDefinition<List<? extends TestEnvironmentDefinition.Activation<?>>> {
        public static final MapCodec<TestEnvironmentDefinition.AllOf> CODEC = null;
        public AllOf(TestEnvironmentDefinition<?>... defs) { this(List.of()); }
        public List<? extends TestEnvironmentDefinition.Activation<?>> setup(ServerLevel level) { throw new UnsupportedOperationException("port stub"); }
        public void teardown(ServerLevel level, List<? extends TestEnvironmentDefinition.Activation<?>> activations) { throw new UnsupportedOperationException("port stub"); }
        @Override
        public MapCodec<TestEnvironmentDefinition.AllOf> codec() { throw new UnsupportedOperationException("port stub"); }
    }
    public record ClockTime(Holder<?> clock, int time) implements TestEnvironmentDefinition<Long> {
        public static final MapCodec<TestEnvironmentDefinition.ClockTime> CODEC = null;
        public Long setup(ServerLevel level) { throw new UnsupportedOperationException("port stub"); }
        public void teardown(ServerLevel level, Long saveData) { throw new UnsupportedOperationException("port stub"); }
        @Override
        public MapCodec<TestEnvironmentDefinition.ClockTime> codec() { throw new UnsupportedOperationException("port stub"); }
    }
    public record Functions(Optional<ResourceLocation> setupFunction, Optional<ResourceLocation> teardownFunction) implements TestEnvironmentDefinition<Unit> {
        public static final MapCodec<TestEnvironmentDefinition.Functions> CODEC = null;
        public Unit setup(ServerLevel level) { throw new UnsupportedOperationException("port stub"); }
        public void teardown(ServerLevel level, Unit saveData) { throw new UnsupportedOperationException("port stub"); }
        @Override
        public MapCodec<TestEnvironmentDefinition.Functions> codec() { throw new UnsupportedOperationException("port stub"); }
    }
    public record SetGameRules(Object gameRulesMap) implements TestEnvironmentDefinition<Object> {
        public static final MapCodec<TestEnvironmentDefinition.SetGameRules> CODEC = null;
        public Object setup(ServerLevel level) { throw new UnsupportedOperationException("port stub"); }
        public void teardown(ServerLevel level, Object saveData) { throw new UnsupportedOperationException("port stub"); }
        @Override
        public MapCodec<TestEnvironmentDefinition.SetGameRules> codec() { throw new UnsupportedOperationException("port stub"); }
    }
    public record Timelines(List<Holder<?>> timelines) implements TestEnvironmentDefinition<Object> {
        public static final MapCodec<TestEnvironmentDefinition.Timelines> CODEC = null;
        public Object setup(ServerLevel level) { throw new UnsupportedOperationException("port stub"); }
        public void teardown(ServerLevel level, Object saveData) { throw new UnsupportedOperationException("port stub"); }
        @Override
        public MapCodec<TestEnvironmentDefinition.Timelines> codec() { throw new UnsupportedOperationException("port stub"); }
    }
    public record Weather(TestEnvironmentDefinition.Weather.Type weather) implements TestEnvironmentDefinition<TestEnvironmentDefinition.Weather.Type> {
        public static final MapCodec<TestEnvironmentDefinition.Weather> CODEC = null;
        public TestEnvironmentDefinition.Weather.Type setup(ServerLevel level) { throw new UnsupportedOperationException("port stub"); }
        public void teardown(ServerLevel level, TestEnvironmentDefinition.Weather.Type saveData) { throw new UnsupportedOperationException("port stub"); }
        @Override
        public MapCodec<TestEnvironmentDefinition.Weather> codec() { throw new UnsupportedOperationException("port stub"); }
        public static enum Type implements StringRepresentable {
            CLEAR("clear", 100000, 0, false, false), RAIN("rain", 0, 100000, true, false), THUNDER("thunder", 0, 100000, true, true);
            private Type(String name, int clearTime, int rainTime, boolean raining, boolean thundering) {}
            public static final Codec<TestEnvironmentDefinition.Weather.Type> CODEC = null;
            void apply(ServerLevel level) { throw new UnsupportedOperationException("port stub"); }
            @Override
            public String getSerializedName() { throw new UnsupportedOperationException("port stub"); }
        }
    }
}
