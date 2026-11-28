package dev.silencebound.maxtech.lib.shim.gametest;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
public class FunctionGameTestInstance {
    public static final MapCodec<FunctionGameTestInstance> CODEC = null;
    public FunctionGameTestInstance(ResourceKey<Consumer<net.minecraft.gametest.framework.GameTestHelper>> function, TestData<Holder<TestEnvironmentDefinition<?>>> info) { throw new UnsupportedOperationException("port stub"); }
    public void run(net.minecraft.gametest.framework.GameTestHelper helper) { throw new UnsupportedOperationException("port stub"); }
    @Override
    public MapCodec<FunctionGameTestInstance> codec() { throw new UnsupportedOperationException("port stub"); }
    protected MutableComponent typeDescription() { throw new UnsupportedOperationException("port stub"); }
    public Component describe() { throw new UnsupportedOperationException("port stub"); }
}
