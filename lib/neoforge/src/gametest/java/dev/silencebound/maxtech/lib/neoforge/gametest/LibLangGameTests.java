package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Every item, block and entity type the mod registers has an English name in the generated {@code en_us.json} the game
 * loads. Content added without datagen fails here.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class LibLangGameTests {
    /** GameTest-only fixtures (the {@code test_} blocks) have no English names. */
    private static final String TEST_CONTENT = "test_";
    private static final String NAME = "lang_keys_resolve";
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_1x1x1");
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAME, () -> LibLangGameTests::resolve);
    }

    private LibLangGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("lang"));
        event.registerTest(MaxtechLib.id(NAME),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechLib.id(NAME)),
                        new TestData<>(environment, STRUCTURE, 20, 0, true)));
    }

    private static void resolve(GameTestHelper helper) {
        List<String> missing = new ArrayList<>();
        BuiltInRegistries.ITEM.forEach(item -> check(BuiltInRegistries.ITEM, item.getDescriptionId(), item, missing));
        BuiltInRegistries.BLOCK
                .forEach(block -> check(BuiltInRegistries.BLOCK, block.getDescriptionId(), block, missing));
        BuiltInRegistries.ENTITY_TYPE
                .forEach(type -> check(BuiltInRegistries.ENTITY_TYPE, type.getDescriptionId(), type, missing));
        helper.assertValueEqual(List.of(), missing, "lang keys with no English line");
        helper.succeed();
    }

    private static <T> void check(Registry<T> registry, String key, T entry, List<String> missing) {
        Identifier id = registry.getKey(entry);
        if (id != null && id.getNamespace().equals(MaxtechLib.MOD_ID) && !id.getPath().startsWith(TEST_CONTENT)
                && !Language.getInstance().has(key)) {
            missing.add(key);
        }
    }
}
