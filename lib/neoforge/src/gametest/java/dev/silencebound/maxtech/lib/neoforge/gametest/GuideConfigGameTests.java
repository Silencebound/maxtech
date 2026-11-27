package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.guide.GuideConfigValues;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.api.config.ConfigFile;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Lib's server config values by dotted path ({@link GuideConfigValues}), which a guide's {@code <Config>} tag reads.
 * Lib's own guide is retired (MGM item 3); this lookup stays for guides drawn by GuideME.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class GuideConfigGameTests {
    private static final String NAME = "guide_config_values";
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAME, () -> GuideConfigGameTests::configValues);
    }

    private GuideConfigGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("guide_config"));
        Identifier id = MaxtechLib.id(NAME);
        event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
    }

    /**
     * Every path of Lib's server config but {@code preset}, with the value in effect and the default. Reads only;
     * {@code GuideConfigSourceTest} covers the marking of changed values.
     */
    private static void configValues(GameTestHelper helper) {
        String key = "sync.listUpdatesPerSecond";
        GuideConfigValues.Source source = GuideConfigValues.source(MaxtechLib.MOD_ID)
                .orElseThrow(() -> helper.assertionException(Component.literal("Lib registers its config values")));
        helper.assertTrue(source.keys().contains(key), "the source knows " + key);
        helper.assertFalse(source.keys().contains("preset"), "the preset name is no value for pages");
        helper.assertTrue(source.value("nope.value").isEmpty(), "an unknown path has no value");
        ConfigFile file = ServerConfig.configFile();
        if (file == null) {
            throw helper.assertionException(Component.literal("Lib's server config is registered"));
        }
        GuideConfigValues.Value value = source.value(key).orElseThrow();
        helper.assertValueEqual(value.current(), file.get(key), "the value in effect");
        helper.assertValueEqual(value.defaultValue(), file.defaultValue(key), "the default");
        helper.assertValueEqual(value.changed(), !file.get(key).equals(file.defaultValue(key)), "changed or not");
        helper.succeed();
    }
}
