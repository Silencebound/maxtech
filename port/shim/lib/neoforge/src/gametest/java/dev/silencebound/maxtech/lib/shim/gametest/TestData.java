package dev.silencebound.maxtech.lib.shim.gametest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.block.Rotation;

/**
 * 26.1's {@code net.minecraft.gametest.framework.TestData}: a GameTest's environment, structure, timing and retry
 * settings. On 1.21.1 {@link PortGameTests} turns it into a {@code TestFunction}; {@code padding} (the space 26.1 keeps
 * around the structure) has no 1.21.1 counterpart and is ignored there.
 */
public record TestData<EnvironmentType>(
        EnvironmentType environment,
        ResourceLocation structure,
        int maxTicks,
        int setupTicks,
        boolean required,
        Rotation rotation,
        boolean manualOnly,
        int maxAttempts,
        int requiredSuccesses,
        boolean skyAccess,
        int padding) {
    public static final MapCodec<TestData<Holder<TestEnvironmentDefinition<?>>>> CODEC = RecordCodecBuilder
            .mapCodec(i -> i.group(
                    TestEnvironmentDefinition.CODEC.fieldOf("environment").forGetter(TestData::environment),
                    ResourceLocation.CODEC.fieldOf("structure").forGetter(TestData::structure),
                    ExtraCodecs.POSITIVE_INT.fieldOf("max_ticks").forGetter(TestData::maxTicks),
                    ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("setup_ticks", 0).forGetter(TestData::setupTicks),
                    Codec.BOOL.optionalFieldOf("required", true).forGetter(TestData::required),
                    Rotation.CODEC.optionalFieldOf("rotation", Rotation.NONE).forGetter(TestData::rotation),
                    Codec.BOOL.optionalFieldOf("manual_only", false).forGetter(TestData::manualOnly),
                    ExtraCodecs.POSITIVE_INT.optionalFieldOf("max_attempts", 1).forGetter(TestData::maxAttempts),
                    ExtraCodecs.POSITIVE_INT.optionalFieldOf("required_successes", 1)
                            .forGetter(TestData::requiredSuccesses),
                    Codec.BOOL.optionalFieldOf("sky_access", false).forGetter(TestData::skyAccess),
                    ExtraCodecs.intRange(0, 128).optionalFieldOf("padding", 0).forGetter(TestData::padding))
                    .apply(i, TestData::new));

    public TestData(EnvironmentType environment, ResourceLocation structure, int maxTicks, int setupTicks,
            boolean required, Rotation rotation) {
        this(environment, structure, maxTicks, setupTicks, required, rotation, false, 1, 1, false, 0);
    }

    public TestData(EnvironmentType environment, ResourceLocation structure, int maxTicks, int setupTicks,
            boolean required) {
        this(environment, structure, maxTicks, setupTicks, required, Rotation.NONE);
    }

    public <T> TestData<T> map(Function<EnvironmentType, T> mapper) {
        return new TestData<>(mapper.apply(environment), structure, maxTicks, setupTicks, required, rotation,
                manualOnly, maxAttempts, requiredSuccesses, skyAccess, padding);
    }
}
