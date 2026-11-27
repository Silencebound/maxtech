package dev.silencebound.maxtech.farms.neoforge.gametest;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * {@code standalone.json} gives every server config value of Farms a default, and neither built-in preset names a value
 * no config defines. A new option without a preset entry fails here instead of at the next server start.
 */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID)
public final class PresetGameTests {
    private static final String NAME = "presets_complete";
    private static final Identifier STRUCTURE = MaxtechFarms.id("empty_7x2x3");
    private PresetGameTests() {
    }

    /** The test functions, registered on the mod bus; the GameTest source set only exists in dev runs. */
    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, MaxtechFarms.id(NAME),
                () -> (Consumer<GameTestHelper>) PresetGameTests::complete);
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechFarms.id("presets"));
        event.registerTest(MaxtechFarms.id(NAME),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechFarms.id(NAME)),
                        new TestData<>(environment, STRUCTURE, 20, 0, true)));
    }

    private static void complete(GameTestHelper helper) {
        helper.assertValueEqual(List.of(), FarmsServerConfig.presetProblems(), "preset problems");
        helper.succeed();
    }
}
