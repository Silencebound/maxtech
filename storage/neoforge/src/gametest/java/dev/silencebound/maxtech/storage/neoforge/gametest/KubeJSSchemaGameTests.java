package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.neoforge.compat.kubejs.StorageRecipeSchemas;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * Every Storage recipe type has a KubeJS schema. KubeJS is not in the test run, so this holds the schema list the
 * plugin registers ({@link StorageRecipeSchemas}) against the recipe type registry, both ways.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class KubeJSSchemaGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_7x2x3");
    private static final String NAME = "kubejs_schemas_cover_recipe_types";
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAME, () -> KubeJSSchemaGameTests::schemasCoverRecipeTypes);
    }

    private KubeJSSchemaGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("kubejs"));
        event.registerTest(MaxtechStorage.id(NAME),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(NAME)),
                        new TestData<>(environment, STRUCTURE, 20, 0, true)));
    }

    private static void schemasCoverRecipeTypes(GameTestHelper helper) {
        Set<Identifier> types = new HashSet<>();
        for (Identifier id : BuiltInRegistries.RECIPE_TYPE.keySet()) {
            if (id.getNamespace().equals(MaxtechStorage.MOD_ID)) {
                types.add(id);
            }
        }
        Set<Identifier> schemas = Set.copyOf(StorageRecipeSchemas.MACHINE_RECIPES);
        helper.assertTrue(!types.isEmpty(), Component.literal("Storage registers no recipe types"));
        helper.assertTrue(types.equals(schemas),
                Component.literal("Recipe types " + types + " and KubeJS schemas " + schemas + " differ"));
        helper.assertTrue(StorageTests.uncoveredSerializers(schemas).isEmpty(), Component.literal(
                "Recipe serializers without a KubeJS schema or an entry in StorageTests.SERIALIZERS_WITHOUT_OWN_TYPE: "
                        + StorageTests.uncoveredSerializers(schemas)));
        helper.succeed();
    }
}
