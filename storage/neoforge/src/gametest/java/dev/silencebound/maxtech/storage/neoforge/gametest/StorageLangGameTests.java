package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.registry.ModContent;
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
 * Every item, block and entity type the mod registers, and every line its content declares under a key of its own, has
 * an English name in the generated {@code en_us.json} the game loads. Content added without datagen fails here.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class StorageLangGameTests {
    /** GameTest-only fixtures (the {@code test_} blocks and items) have no English names. */
    private static final String TEST_CONTENT = "test_";
    private static final String NAME = "lang_keys_resolve";
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_7x2x3");
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAME, () -> StorageLangGameTests::resolve);
    }

    private StorageLangGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("lang"));
        event.registerTest(MaxtechStorage.id(NAME),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(NAME)),
                        new TestData<>(environment, STRUCTURE, 20, 0, true)));
    }

    private static void resolve(GameTestHelper helper) {
        List<String> missing = new ArrayList<>();
        BuiltInRegistries.ITEM.forEach(item -> check(BuiltInRegistries.ITEM, item.getDescriptionId(), item, missing));
        BuiltInRegistries.BLOCK
                .forEach(block -> check(BuiltInRegistries.BLOCK, block.getDescriptionId(), block, missing));
        BuiltInRegistries.ENTITY_TYPE
                .forEach(type -> check(BuiltInRegistries.ENTITY_TYPE, type.getDescriptionId(), type, missing));
        for (String key : ModContent.CONTENT.translations().keySet()) {
            if (!Language.getInstance().has(key)) {
                missing.add(key);
            }
        }
        helper.assertValueEqual(List.of(), missing, "lang keys with no English line");
        helper.succeed();
    }

    private static <T> void check(Registry<T> registry, String key, T entry, List<String> missing) {
        Identifier id = registry.getKey(entry);
        if (id != null && id.getNamespace().equals(MaxtechStorage.MOD_ID) && !id.getPath().startsWith(TEST_CONTENT)
                && !Language.getInstance().has(key)) {
            missing.add(key);
        }
    }
}
