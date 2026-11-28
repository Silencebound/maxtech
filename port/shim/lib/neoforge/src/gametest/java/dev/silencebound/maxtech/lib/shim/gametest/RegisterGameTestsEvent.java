package dev.silencebound.maxtech.lib.shim.gametest;

import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/**
 * NeoForge 26.1's {@code RegisterGameTestsEvent}, which registers test environments and test instances by id.
 * NeoForge 21.1's event of the same name takes annotated methods instead; {@link PortGameTests} posts this one to every
 * mod's bus from inside it and hands the tests to 1.21.1 as generated test functions.
 */
public class RegisterGameTestsEvent extends Event implements IModBusEvent {
    RegisterGameTestsEvent() {
    }

    public Holder<TestEnvironmentDefinition<?>> registerEnvironment(ResourceLocation name,
            TestEnvironmentDefinition<?>... definitions) {
        return registerEnvironment(name, new TestEnvironmentDefinition.AllOf(definitions));
    }

    public Holder<TestEnvironmentDefinition<?>> registerEnvironment(ResourceLocation name,
            TestEnvironmentDefinition<?> definition) {
        return PortGameTests.registerEnvironment(name, definition);
    }

    public void registerTest(ResourceLocation name,
            Function<TestData<Holder<TestEnvironmentDefinition<?>>>, FunctionGameTestInstance> factory,
            TestData<Holder<TestEnvironmentDefinition<?>>> testData) {
        registerTest(name, factory.apply(testData));
    }

    public void registerTest(ResourceLocation name, FunctionGameTestInstance test) {
        PortGameTests.registerTest(name, test);
    }
}
