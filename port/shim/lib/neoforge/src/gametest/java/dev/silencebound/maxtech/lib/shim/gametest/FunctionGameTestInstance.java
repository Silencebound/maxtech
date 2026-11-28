package dev.silencebound.maxtech.lib.shim.gametest;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;

/**
 * 26.1's {@code net.minecraft.gametest.framework.FunctionGameTestInstance} (with what Lib uses of its base class
 * {@code GameTestInstance}): a GameTest that runs a registered test function. 1.21.1 has no test instances;
 * {@link PortGameTests} turns each into a {@code TestFunction}.
 */
public class FunctionGameTestInstance {
    public static final MapCodec<FunctionGameTestInstance> CODEC = RecordCodecBuilder.mapCodec(i -> i
            .group(ResourceKey.codec(GameTestRegistries.TEST_FUNCTION).fieldOf("function")
                    .forGetter(FunctionGameTestInstance::function),
                    TestData.CODEC.forGetter(FunctionGameTestInstance::info))
            .apply(i, FunctionGameTestInstance::new));
    private final ResourceKey<Consumer<GameTestHelper>> function;
    private final TestData<Holder<TestEnvironmentDefinition<?>>> info;

    public FunctionGameTestInstance(ResourceKey<Consumer<GameTestHelper>> function,
            TestData<Holder<TestEnvironmentDefinition<?>>> info) {
        this.function = function;
        this.info = info;
    }

    public void run(GameTestHelper helper) {
        Consumer<GameTestHelper> test = GameTestRegistries.TEST_FUNCTIONS.get(function);
        if (test == null) {
            throw new IllegalStateException("Trying to access missing test function: " + function.location());
        }
        test.accept(helper);
    }

    public ResourceKey<Consumer<GameTestHelper>> function() {
        return function;
    }

    public TestData<Holder<TestEnvironmentDefinition<?>>> info() {
        return info;
    }

    public MapCodec<FunctionGameTestInstance> codec() {
        return CODEC;
    }

    protected MutableComponent typeDescription() {
        return Component.translatable("test_instance.type.function");
    }

    public Component describe() {
        return typeDescription().append(Component.literal("\nfunction: " + function.location()));
    }
}
