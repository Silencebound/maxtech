package dev.silencebound.maxtech.lib.shim.gametest;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.block.Rotation;
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
    int padding
) {
    public static final MapCodec<TestData<Holder<TestEnvironmentDefinition<?>>>> CODEC = null;
    public TestData(EnvironmentType environment, ResourceLocation structure, int maxTicks, int setupTicks, boolean required, Rotation rotation) { this(environment, structure, maxTicks, setupTicks, required, rotation, false, 1, 1, false, 0); }
    public TestData(EnvironmentType environment, ResourceLocation structure, int maxTicks, int setupTicks, boolean required) { this(environment, structure, maxTicks, setupTicks, required, Rotation.NONE); }
    public <T> TestData<T> map(Function<EnvironmentType, T> mapper) { throw new UnsupportedOperationException("port stub"); }
}
