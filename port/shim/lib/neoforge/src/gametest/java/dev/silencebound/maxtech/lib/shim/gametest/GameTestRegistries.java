package dev.silencebound.maxtech.lib.shim.gametest;

import com.mojang.serialization.MapCodec;
import java.util.function.Consumer;
import net.minecraft.core.Registry;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.registries.RegistryBuilder;

/**
 * Port only (1.21.1): 26.1's {@code Registries.TEST_FUNCTION} and {@code TEST_ENVIRONMENT_DEFINITION_TYPE}, which
 * 1.21.1 lacks, as Lib's own registries (port/rules/saving.py points the 26.1 names here), so test functions and
 * environment codecs still register through Lib's registrars. Not synced: only the server runs GameTests.
 */
@EventBusSubscriber(modid = "maxtech_lib")
public final class GameTestRegistries {
    public static final ResourceKey<Registry<Consumer<GameTestHelper>>> TEST_FUNCTION = ResourceKey
            .createRegistryKey(ResourceLocation.fromNamespaceAndPath("maxtech_lib", "port_test_function"));
    public static final ResourceKey<Registry<MapCodec<? extends TestEnvironmentDefinition<?>>>> TEST_ENVIRONMENT_DEFINITION_TYPE = ResourceKey
            .createRegistryKey(ResourceLocation.fromNamespaceAndPath("maxtech_lib", "port_test_environment_definition_type"));

    public static final Registry<Consumer<GameTestHelper>> TEST_FUNCTIONS = new RegistryBuilder<>(TEST_FUNCTION)
            .sync(false).create();
    public static final Registry<MapCodec<? extends TestEnvironmentDefinition<?>>> TEST_ENVIRONMENT_DEFINITION_TYPES = new RegistryBuilder<>(
            TEST_ENVIRONMENT_DEFINITION_TYPE).sync(false).create();

    private GameTestRegistries() {
    }

    @SubscribeEvent
    static void newRegistries(NewRegistryEvent event) {
        event.register(TEST_FUNCTIONS);
        event.register(TEST_ENVIRONMENT_DEFINITION_TYPES);
    }

    /** 26.1's built-in environment kinds, as its bootstrap registers them. */
    @SubscribeEvent
    static void register(RegisterEvent event) {
        if (event.getRegistryKey().equals(TEST_ENVIRONMENT_DEFINITION_TYPE)) {
            TestEnvironmentDefinition.bootstrap(TEST_ENVIRONMENT_DEFINITION_TYPES);
        }
    }
}
