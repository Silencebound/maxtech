package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.lib.neoforge.platform.ConfigTranslations;
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

/**
 * {@code standalone.json} gives every server config value of Storage a default, and neither built-in preset names a
 * value no config defines. A new option without a preset entry fails here instead of at the next server start.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class PresetGameTests {
    private static final String NAME = "presets_complete";
    private static final String LANG = "config_lang_complete";
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_7x2x3");
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAME, () -> PresetGameTests::complete);
        FUNCTIONS.register(LANG, () -> PresetGameTests::langComplete);
    }

    private PresetGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("presets"));
        event.registerTest(MaxtechStorage.id(NAME),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(NAME)),
                        new TestData<>(environment, STRUCTURE, 20, 0, true)));
        event.registerTest(MaxtechStorage.id(LANG),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(LANG)),
                        new TestData<>(environment, STRUCTURE, 20, 0, true)));
    }

    private static void complete(GameTestHelper helper) {
        helper.assertValueEqual(List.of(), StorageServerConfig.presetProblems(), "preset problems");
        helper.succeed();
    }

    /**
     * Every value and section of every config file (client, common and server) has a name in {@code en_us}, every value
     * a tooltip, and the tooltips match the comments, so NeoForge's config screen shows no raw keys.
     */
    private static void langComplete(GameTestHelper helper) {
        helper.assertValueEqual(List.of(),
                ConfigTranslations.problems(MaxtechStorage.MOD_ID,
                        ConfigTranslations.englishLang(StorageServerConfig.class, MaxtechStorage.MOD_ID)),
                "config lang problems");
        helper.succeed();
    }
}
