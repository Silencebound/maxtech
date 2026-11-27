package dev.silencebound.maxtech.lib.neoforge.gametest.perf;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Each decoded perf environment is a new instance. A client joining a dev world decodes the synced environments, and
 * the registry holds values by identity, so one shared instance under several ids disconnected it.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class PerfEnvironmentGameTests {
    private static final String NAME = "environment_perf_decodes_fresh";
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAME, () -> PerfEnvironmentGameTests::decodesFresh);
    }

    private PerfEnvironmentGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechLib.id("perf_environment_codec"));
        event.registerTest(MaxtechLib.id(NAME),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechLib.id(NAME)),
                        new TestData<>(environment, MaxtechLib.id("empty_1x1x1"), 20, 0, true)));
    }

    // Identity is what the registry compares, so it is what this test checks.
    @SuppressWarnings("ReferenceEquality")
    private static void decodesFresh(GameTestHelper helper) {
        PerfEnvironment first = decode();
        PerfEnvironment second = decode();
        helper.assertFalse(first == second, "two decoded perf environments are the same instance");
        helper.succeed();
    }

    private static PerfEnvironment decode() {
        return PerfEnvironment.CODEC.codec().parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow();
    }
}
