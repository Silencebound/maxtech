package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Registration shorthand for Storage's GameTests: a function in the static initialiser, a test per environment. */
final class StorageTests {
    static final Identifier SMALL = MaxtechStorage.id("empty_7x2x3");
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    /**
     * Storage's recipe serializers that make no recipe type of their own, so they need no JEI category and no KubeJS
     * schema, with the reason. A serializer is one of these, or the serializer of a recipe type the viewer and KubeJS
     * tests check.
     */
    static final Map<Identifier, String> SERIALIZERS_WITHOUT_OWN_TYPE = Map.of(MaxtechStorage.id("component_copying"),
            "a special shapeless crafting recipe with a computed result; it is a crafting_shapeless recipe, which "
                    + "recipe viewers and KubeJS already handle");

    private StorageTests() {
    }

    /** Storage's recipe serializers that are neither the serializer of a recipe type nor an exception above. */
    static Set<Identifier> uncoveredSerializers(Set<Identifier> typeIds) {
        Set<Identifier> out = new HashSet<>();
        for (Identifier id : BuiltInRegistries.RECIPE_SERIALIZER.keySet()) {
            if (id.getNamespace().equals(MaxtechStorage.MOD_ID) && !typeIds.contains(id)
                    && !SERIALIZERS_WITHOUT_OWN_TYPE.containsKey(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** Queues the test function {@code name}; call from a test class's static initialiser. */
    static void function(String name, Consumer<GameTestHelper> test) {
        FUNCTIONS.register(name, () -> test);
    }

    /** Registers the test {@code name}, whose function {@link #function} queued, in {@code environment}. */
    static void test(RegisterGameTestsEvent event, Holder<TestEnvironmentDefinition<?>> environment, String name,
            Identifier structure, int maxTicks) {
        event.registerTest(MaxtechStorage.id(name),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                        new TestData<>(environment, structure, maxTicks, 0, true)));
    }
}
